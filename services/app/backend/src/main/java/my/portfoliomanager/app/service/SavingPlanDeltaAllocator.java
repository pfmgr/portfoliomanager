package my.portfoliomanager.app.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SavingPlanDeltaAllocator {
	public Allocation allocateToTarget(List<PlanInput> plans,
										BigDecimal targetTotal,
										BigDecimal minimumRebalancing,
										BigDecimal minimumSavingPlanSize) {
		if (plans == null || plans.isEmpty()) {
			return new Allocation(Map.of(), Map.of(), Set.of(), false);
		}
		BigDecimal normalizedTarget = targetTotal == null ? BigDecimal.ZERO : targetTotal;
		if (normalizedTarget.signum() < 0) {
			normalizedTarget = BigDecimal.ZERO;
		}
		Map<AssessorEngine.PlanKey, BigDecimal> current = new LinkedHashMap<>();
		BigDecimal currentTotal = BigDecimal.ZERO;
		for (PlanInput plan : plans) {
			if (plan == null || plan.key() == null) {
				continue;
			}
			BigDecimal amount = safeAmount(plan.currentAmount());
			current.put(plan.key(), amount);
			currentTotal = currentTotal.add(amount);
		}
		BigDecimal delta = normalizedTarget.subtract(currentTotal);
		if (delta.signum() == 0) {
			return new Allocation(Map.copyOf(current), Map.of(), Set.of(), false);
		}
		BigDecimal minRebalance = normalizeMinimum(minimumRebalancing);
		BigDecimal minSaving = normalizeMinimum(minimumSavingPlanSize);

		Map<AssessorEngine.PlanKey, BigDecimal> strictDeltas = allocatePlanDeltasByWeights(
				plans,
				delta,
				minRebalance,
				minimumSavingPlanSize);
		Map<AssessorEngine.PlanKey, BigDecimal> softDeltas = null;

		Map<AssessorEngine.PlanKey, BigDecimal> strictProposal = strictDeltas.isEmpty()
				? null
				: finalizeProposal(current, strictDeltas, normalizedTarget, plans, minSaving);
		Set<AssessorEngine.PlanKey> strictDiscards = strictProposal == null
				? Set.of()
				: computeDiscards(current, strictProposal);

		if (minRebalance.signum() > 0) {
			softDeltas = allocatePlanDeltasByWeights(
					plans,
					delta,
					BigDecimal.ZERO,
					minimumSavingPlanSize);
		}
		Map<AssessorEngine.PlanKey, BigDecimal> softProposal = softDeltas == null || softDeltas.isEmpty()
				? null
				: finalizeProposal(current, softDeltas, normalizedTarget, plans, minSaving);
		Set<AssessorEngine.PlanKey> softDiscards = softProposal == null
				? Set.of()
				: computeDiscards(current, softProposal);

		boolean minRebalanceSuppressed = false;
		Map<AssessorEngine.PlanKey, BigDecimal> proposed = strictProposal;
		Set<AssessorEngine.PlanKey> discarded = strictDiscards;
		if (softProposal != null) {
			if (proposed == null || softDiscards.size() < strictDiscards.size()) {
				proposed = softProposal;
				discarded = softDiscards;
				minRebalanceSuppressed = true;
			}
		}
		if (proposed == null) {
			proposed = enforceTargetSum(new LinkedHashMap<>(current), normalizedTarget, plans, minSaving);
			discarded = computeDiscards(current, proposed);
			minRebalanceSuppressed = minRebalance.signum() > 0;
		}

		Map<AssessorEngine.PlanKey, BigDecimal> finalDeltas = new LinkedHashMap<>();
		for (Map.Entry<AssessorEngine.PlanKey, BigDecimal> entry : current.entrySet()) {
			AssessorEngine.PlanKey key = entry.getKey();
			BigDecimal currentAmount = entry.getValue();
			BigDecimal newAmount = proposed.getOrDefault(key, BigDecimal.ZERO);
			BigDecimal change = newAmount.subtract(currentAmount);
			finalDeltas.put(key, change);
			if (!minRebalanceSuppressed && minRebalance.signum() > 0 && change.signum() != 0
					&& change.abs().compareTo(minRebalance) < 0) {
				minRebalanceSuppressed = true;
			}
		}
		return new Allocation(Map.copyOf(proposed), Map.copyOf(finalDeltas), Set.copyOf(discarded), minRebalanceSuppressed);
	}

	private Map<AssessorEngine.PlanKey, BigDecimal> finalizeProposal(Map<AssessorEngine.PlanKey, BigDecimal> current,
																	 Map<AssessorEngine.PlanKey, BigDecimal> deltas,
																	 BigDecimal targetTotal,
																	 List<PlanInput> plans,
																	 BigDecimal minimumSavingPlanSize) {
		Map<AssessorEngine.PlanKey, BigDecimal> proposed = new LinkedHashMap<>();
		for (Map.Entry<AssessorEngine.PlanKey, BigDecimal> entry : current.entrySet()) {
			AssessorEngine.PlanKey key = entry.getKey();
			BigDecimal currentAmount = entry.getValue();
			BigDecimal change = deltas == null ? BigDecimal.ZERO : deltas.getOrDefault(key, BigDecimal.ZERO);
			BigDecimal newAmount = currentAmount.add(change);
			if (newAmount.signum() < 0) {
				newAmount = BigDecimal.ZERO;
			}
			proposed.put(key, newAmount);
		}
		return enforceTargetSum(proposed, targetTotal, plans, minimumSavingPlanSize);
	}

	private Set<AssessorEngine.PlanKey> computeDiscards(Map<AssessorEngine.PlanKey, BigDecimal> current,
														Map<AssessorEngine.PlanKey, BigDecimal> proposed) {
		Set<AssessorEngine.PlanKey> discarded = new HashSet<>();
		for (Map.Entry<AssessorEngine.PlanKey, BigDecimal> entry : current.entrySet()) {
			AssessorEngine.PlanKey key = entry.getKey();
			BigDecimal currentAmount = entry.getValue();
			BigDecimal newAmount = proposed.getOrDefault(key, BigDecimal.ZERO);
			if (currentAmount.signum() > 0 && newAmount.signum() == 0) {
				discarded.add(key);
			}
		}
		return discarded;
	}

	private Map<AssessorEngine.PlanKey, BigDecimal> enforceTargetSum(Map<AssessorEngine.PlanKey, BigDecimal> proposed,
																	 BigDecimal targetTotal,
																	 List<PlanInput> plans,
																	 BigDecimal minimumSavingPlanSize) {
		if (proposed == null || proposed.isEmpty() || targetTotal == null) {
			return proposed;
		}
		BigDecimal total = BigDecimal.ZERO;
		for (BigDecimal value : proposed.values()) {
			total = total.add(safeAmount(value));
		}
		BigDecimal residual = targetTotal.subtract(total);
		if (residual.signum() == 0) {
			return proposed;
		}
		Map<AssessorEngine.PlanKey, BigDecimal> weights = new LinkedHashMap<>();
		for (PlanInput plan : plans) {
			if (plan == null || plan.key() == null) {
				continue;
			}
			weights.put(plan.key(), normalizeWeight(plan.weight()));
		}
		Map<AssessorEngine.PlanKey, BigDecimal> adjusted = new LinkedHashMap<>(proposed);
		if (residual.signum() > 0) {
			AssessorEngine.PlanKey target = selectIncreaseTarget(adjusted, weights);
			if (target != null) {
				adjusted.put(target, adjusted.getOrDefault(target, BigDecimal.ZERO).add(residual));
			}
			return adjusted;
		}
		BigDecimal reduction = residual.abs();
		AssessorEngine.PlanKey reductionTarget = selectReductionTarget(adjusted, weights, minimumSavingPlanSize, reduction);
		if (reductionTarget != null) {
			adjusted.put(reductionTarget, adjusted.getOrDefault(reductionTarget, BigDecimal.ZERO).subtract(reduction));
			return adjusted;
		}
		List<ReductionCandidate> reductionCandidates =
				buildReductionCandidates(adjusted, weights, minimumSavingPlanSize);
		Map<AssessorEngine.PlanKey, BigDecimal> reductions =
				allocateNegativePlanDeltasWithCaps(reductionCandidates, reduction, BigDecimal.ZERO);
		if (reductions.isEmpty()) {
			List<PlanCandidate> candidates = buildCandidates(adjusted, weights);
			Map<AssessorEngine.PlanKey, BigDecimal> deltas =
					allocateNegativePlanDeltas(candidates, reduction, BigDecimal.ZERO, minimumSavingPlanSize);
			for (Map.Entry<AssessorEngine.PlanKey, BigDecimal> entry : deltas.entrySet()) {
				adjusted.put(entry.getKey(),
						adjusted.getOrDefault(entry.getKey(), BigDecimal.ZERO).add(entry.getValue()));
			}
		} else {
			for (Map.Entry<AssessorEngine.PlanKey, BigDecimal> entry : reductions.entrySet()) {
				adjusted.put(entry.getKey(),
						adjusted.getOrDefault(entry.getKey(), BigDecimal.ZERO).subtract(entry.getValue()));
			}
		}
		BigDecimal updatedTotal = BigDecimal.ZERO;
		for (BigDecimal value : adjusted.values()) {
			updatedTotal = updatedTotal.add(safeAmount(value));
		}
		BigDecimal overshoot = targetTotal.subtract(updatedTotal);
		if (overshoot.signum() > 0) {
			AssessorEngine.PlanKey target = selectIncreaseTarget(adjusted, weights);
			if (target != null) {
				adjusted.put(target, adjusted.getOrDefault(target, BigDecimal.ZERO).add(overshoot));
			}
		}
		return adjusted;
	}

	private List<PlanCandidate> buildCandidates(Map<AssessorEngine.PlanKey, BigDecimal> amounts,
												Map<AssessorEngine.PlanKey, BigDecimal> weights) {
		List<PlanCandidate> candidates = new ArrayList<>();
		if (amounts == null || amounts.isEmpty()) {
			return candidates;
		}
		for (Map.Entry<AssessorEngine.PlanKey, BigDecimal> entry : amounts.entrySet()) {
			AssessorEngine.PlanKey key = entry.getKey();
			if (key == null) {
				continue;
			}
			BigDecimal weight = weights == null ? BigDecimal.ONE : weights.getOrDefault(key, BigDecimal.ONE);
			candidates.add(new PlanCandidate(key, normalizeWeight(weight), safeAmount(entry.getValue())));
		}
		return candidates;
	}

	private List<ReductionCandidate> buildReductionCandidates(Map<AssessorEngine.PlanKey, BigDecimal> amounts,
															  Map<AssessorEngine.PlanKey, BigDecimal> weights,
															  BigDecimal minimumSavingPlanSize) {
		List<ReductionCandidate> candidates = new ArrayList<>();
		if (amounts == null || amounts.isEmpty()) {
			return candidates;
		}
		BigDecimal minSaving = normalizeMinimum(minimumSavingPlanSize);
		for (Map.Entry<AssessorEngine.PlanKey, BigDecimal> entry : amounts.entrySet()) {
			AssessorEngine.PlanKey key = entry.getKey();
			if (key == null) {
				continue;
			}
			BigDecimal amount = safeAmount(entry.getValue());
			BigDecimal weight = weights == null ? BigDecimal.ONE : weights.getOrDefault(key, BigDecimal.ONE);
			BigDecimal capacity = amount.subtract(minSaving);
			if (capacity.signum() < 0) {
				capacity = BigDecimal.ZERO;
			}
			candidates.add(new ReductionCandidate(key, normalizeWeight(weight), amount, capacity, capacity));
		}
		return candidates;
	}

	private AssessorEngine.PlanKey selectIncreaseTarget(Map<AssessorEngine.PlanKey, BigDecimal> amounts,
													   Map<AssessorEngine.PlanKey, BigDecimal> weights) {
		if (amounts == null || amounts.isEmpty()) {
			return null;
		}
		return amounts.keySet().stream()
				.filter(key -> key != null)
				.max(Comparator.<AssessorEngine.PlanKey, BigDecimal>comparing(
						key -> weights == null ? BigDecimal.ZERO : weights.getOrDefault(key, BigDecimal.ZERO))
						.thenComparing(AssessorEngine.PlanKey::isin))
				.orElse(null);
	}

	private AssessorEngine.PlanKey selectReductionTarget(Map<AssessorEngine.PlanKey, BigDecimal> amounts,
														Map<AssessorEngine.PlanKey, BigDecimal> weights,
														BigDecimal minimumSavingPlanSize,
														BigDecimal reduction) {
		if (amounts == null || amounts.isEmpty() || reduction == null || reduction.signum() <= 0) {
			return null;
		}
		BigDecimal minSaving = normalizeMinimum(minimumSavingPlanSize);
		return amounts.entrySet().stream()
				.filter(entry -> {
					BigDecimal amount = safeAmount(entry.getValue());
					BigDecimal capacity = amount.subtract(minSaving);
					return capacity.compareTo(reduction) >= 0;
				})
				.min(Comparator.<Map.Entry<AssessorEngine.PlanKey, BigDecimal>, BigDecimal>comparing(
						entry -> weights == null ? BigDecimal.ZERO : weights.getOrDefault(entry.getKey(), BigDecimal.ZERO))
						.thenComparing(entry -> entry.getKey().isin()))
				.map(Map.Entry::getKey)
				.orElse(null);
	}

	private Map<AssessorEngine.PlanKey, BigDecimal> allocatePlanDeltasByWeights(List<PlanInput> plans,
																				BigDecimal delta,
																				BigDecimal minimumRebalancing,
																				BigDecimal minimumSavingPlanSize) {
		if (plans == null || plans.isEmpty() || delta == null || delta.signum() == 0) {
			return Map.of();
		}
		List<PlanCandidate> candidates = new ArrayList<>();
		for (PlanInput plan : plans) {
			if (plan == null || plan.key() == null) {
				continue;
			}
			BigDecimal weight = normalizeWeight(plan.weight());
			candidates.add(new PlanCandidate(plan.key(), weight, safeAmount(plan.currentAmount())));
		}
		candidates.sort(Comparator.comparing(PlanCandidate::weight).reversed()
				.thenComparing(candidate -> candidate.key().isin()));
		if (delta.signum() > 0) {
			return allocatePositivePlanDeltas(candidates, delta, minimumRebalancing);
		}
		return allocateNegativePlanDeltas(candidates, delta.abs(), minimumRebalancing, minimumSavingPlanSize);
	}

	private Map<AssessorEngine.PlanKey, BigDecimal> allocatePositivePlanDeltas(List<PlanCandidate> candidates,
																			  BigDecimal total,
																			  BigDecimal minimumRebalancing) {
		List<PlanCandidate> remaining = new ArrayList<>(candidates);
		BigDecimal minRebalance = normalizeMinimum(minimumRebalancing);
		int guard = 0;
		while (!remaining.isEmpty() && guard < 12) {
			guard += 1;
			Map<AssessorEngine.PlanKey, BigDecimal> allocations = allocatePlanDeltasByWeight(remaining, total);
			boolean valid = true;
			if (minRebalance.signum() > 0) {
				for (BigDecimal value : allocations.values()) {
					if (value.signum() != 0 && value.compareTo(minRebalance) < 0) {
						valid = false;
						break;
					}
				}
			}
			if (valid) {
				return allocations;
			}
			PlanCandidate toRemove = null;
			for (int i = remaining.size() - 1; i >= 0; i--) {
				PlanCandidate candidate = remaining.get(i);
				BigDecimal value = allocations.getOrDefault(candidate.key(), BigDecimal.ZERO);
				if (value.signum() != 0 && minRebalance.signum() > 0 && value.compareTo(minRebalance) < 0) {
					toRemove = candidate;
					break;
				}
			}
			if (toRemove == null) {
				break;
			}
			remaining.remove(toRemove);
		}
		return Map.of();
	}

	private Map<AssessorEngine.PlanKey, BigDecimal> allocateNegativePlanDeltas(List<PlanCandidate> candidates,
																			  BigDecimal total,
																			  BigDecimal minimumRebalancing,
																			  BigDecimal minimumSavingPlanSize) {
		if (candidates == null || candidates.isEmpty() || total == null || total.signum() <= 0) {
			return Map.of();
		}
		BigDecimal minRebalance = normalizeMinimum(minimumRebalancing);
		BigDecimal minSaving = normalizeMinimum(minimumSavingPlanSize);
		List<ReductionCandidate> reductionCandidates = new ArrayList<>();
		BigDecimal maxReduction = BigDecimal.ZERO;
		for (PlanCandidate candidate : candidates) {
			BigDecimal amount = candidate.amount();
			BigDecimal capacity = amount.subtract(minSaving);
			if (capacity.signum() < 0) {
				capacity = BigDecimal.ZERO;
			}
			BigDecimal eligibleCapacity = capacity;
			if (minRebalance.signum() > 0 && eligibleCapacity.compareTo(minRebalance) < 0) {
				eligibleCapacity = BigDecimal.ZERO;
			}
			reductionCandidates.add(new ReductionCandidate(candidate.key(), candidate.weight(), amount, capacity, eligibleCapacity));
			maxReduction = maxReduction.add(eligibleCapacity);
		}
		List<ReductionCandidate> discards = selectDiscardsForNegativeDelta(reductionCandidates, total, maxReduction, minRebalance);
		Map<AssessorEngine.PlanKey, BigDecimal> reductions = new LinkedHashMap<>();
		BigDecimal disabledTotal = BigDecimal.ZERO;
		Set<AssessorEngine.PlanKey> discardKeys = new HashSet<>();
		for (ReductionCandidate discard : discards) {
			reductions.put(discard.key(), discard.amount());
			disabledTotal = disabledTotal.add(discard.amount());
			discardKeys.add(discard.key());
		}
		BigDecimal remainingTotal = total.subtract(disabledTotal);
		if (remainingTotal.signum() < 0) {
			remainingTotal = BigDecimal.ZERO;
		}
		if (remainingTotal.signum() > 0) {
			List<ReductionCandidate> remaining = new ArrayList<>();
			for (ReductionCandidate candidate : reductionCandidates) {
				if (!discardKeys.contains(candidate.key())) {
					remaining.add(candidate);
				}
			}
			Map<AssessorEngine.PlanKey, BigDecimal> allocations =
					allocateNegativePlanDeltasWithCaps(remaining, remainingTotal, minRebalance);
			reductions.putAll(allocations);
		}
		Map<AssessorEngine.PlanKey, BigDecimal> signed = new LinkedHashMap<>();
		for (Map.Entry<AssessorEngine.PlanKey, BigDecimal> entry : reductions.entrySet()) {
			if (entry.getValue().signum() != 0) {
				signed.put(entry.getKey(), entry.getValue().negate());
			}
		}
		return signed;
	}

	private Map<AssessorEngine.PlanKey, BigDecimal> allocateNegativePlanDeltasWithCaps(
			List<ReductionCandidate> candidates,
			BigDecimal total,
			BigDecimal minimumRebalancing) {
		if (candidates == null || candidates.isEmpty() || total == null || total.signum() <= 0) {
			return Map.of();
		}
		BigDecimal minRebalance = normalizeMinimum(minimumRebalancing);
		List<ReductionCandidate> remaining = new ArrayList<>(candidates);
		remaining.sort(Comparator.comparing(ReductionCandidate::weight).reversed()
				.thenComparing(candidate -> candidate.key().isin()));
		int guard = 0;
		while (!remaining.isEmpty() && guard < 12) {
			guard += 1;
			Map<AssessorEngine.PlanKey, BigDecimal> allocations = allocateReductionDeltasByWeight(remaining, total);
			boolean valid = true;
			for (ReductionCandidate candidate : remaining) {
				BigDecimal allocation = allocations.getOrDefault(candidate.key(), BigDecimal.ZERO);
				if (allocation.signum() == 0) {
					continue;
				}
				if (allocation.compareTo(candidate.capacity()) > 0) {
					valid = false;
					break;
				}
				if (minRebalance.signum() > 0 && allocation.compareTo(minRebalance) < 0) {
					valid = false;
					break;
				}
			}
			if (valid) {
				return allocations;
			}
			ReductionCandidate toRemove = null;
			for (int i = remaining.size() - 1; i >= 0; i--) {
				ReductionCandidate candidate = remaining.get(i);
				BigDecimal allocation = allocations.getOrDefault(candidate.key(), BigDecimal.ZERO);
				if (allocation.signum() == 0) {
					continue;
				}
				if (allocation.compareTo(candidate.capacity()) > 0
						|| (minRebalance.signum() > 0 && allocation.compareTo(minRebalance) < 0)) {
					toRemove = candidate;
					break;
				}
			}
			if (toRemove == null) {
				break;
			}
			remaining.remove(toRemove);
		}
		if (minRebalance.signum() <= 0) {
			BigDecimal capacityTotal = BigDecimal.ZERO;
			for (ReductionCandidate candidate : candidates) {
				capacityTotal = capacityTotal.add(candidate.capacity());
			}
			if (capacityTotal.compareTo(total) >= 0) {
				return allocateNegativePlanDeltasGreedy(candidates, total);
			}
		}
		return Map.of();
	}

	private Map<AssessorEngine.PlanKey, BigDecimal> allocateNegativePlanDeltasGreedy(
			List<ReductionCandidate> candidates,
			BigDecimal total) {
		if (candidates == null || candidates.isEmpty() || total == null || total.signum() <= 0) {
			return Map.of();
		}
		List<ReductionCandidate> ordered = new ArrayList<>(candidates);
		ordered.sort(Comparator.comparing(ReductionCandidate::weight)
				.thenComparing(candidate -> candidate.key().isin()));
		Map<AssessorEngine.PlanKey, BigDecimal> reductions = new LinkedHashMap<>();
		BigDecimal remaining = total;
		for (ReductionCandidate candidate : ordered) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal capacity = candidate.capacity();
			if (capacity.signum() <= 0) {
				continue;
			}
			BigDecimal reduction = capacity.min(remaining);
			reductions.put(candidate.key(), reduction);
			remaining = remaining.subtract(reduction);
		}
		return remaining.signum() == 0 ? reductions : Map.of();
	}

	private List<ReductionCandidate> selectDiscardsForNegativeDelta(List<ReductionCandidate> candidates,
																   BigDecimal total,
																   BigDecimal maxReduction,
																   BigDecimal minimumRebalancing) {
		if (candidates == null || candidates.isEmpty() || total == null || total.signum() <= 0) {
			return List.of();
		}
		BigDecimal excess = total.subtract(maxReduction);
		if (excess.signum() <= 0) {
			return List.of();
		}
		List<ReductionCandidate> ordered = new ArrayList<>();
		for (ReductionCandidate candidate : candidates) {
			BigDecimal gain = candidate.amount().subtract(candidate.eligibleCapacity());
			if (gain.signum() > 0) {
				ordered.add(candidate);
			}
		}
		if (ordered.isEmpty()) {
			return List.of();
		}
		ordered.sort(Comparator.comparing(candidate -> candidate.key().isin()));
		int size = ordered.size();
		BigDecimal[] gains = new BigDecimal[size];
		BigDecimal[] amounts = new BigDecimal[size];
		BigDecimal[] weights = new BigDecimal[size];
		List<String> isins = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			ReductionCandidate candidate = ordered.get(i);
			gains[i] = candidate.amount().subtract(candidate.eligibleCapacity());
			amounts[i] = candidate.amount();
			weights[i] = candidate.weight() == null ? BigDecimal.ZERO : candidate.weight();
			isins.add(candidate.key().isin());
		}
		int bestMask = -1;
		int bestCount = Integer.MAX_VALUE;
		BigDecimal bestWeight = null;
		BigDecimal bestOvershoot = null;
		String bestKey = null;
		int limit = 1 << size;
		for (int mask = 1; mask < limit; mask++) {
			int count = Integer.bitCount(mask);
			if (count > bestCount) {
				continue;
			}
			BigDecimal gainSum = BigDecimal.ZERO;
			BigDecimal weightSum = BigDecimal.ZERO;
			BigDecimal discardTotal = BigDecimal.ZERO;
			for (int i = 0; i < size; i++) {
				if ((mask & (1 << i)) == 0) {
					continue;
				}
				gainSum = gainSum.add(gains[i]);
				weightSum = weightSum.add(weights[i]);
				discardTotal = discardTotal.add(amounts[i]);
			}
			if (gainSum.compareTo(excess) < 0) {
				continue;
			}
			BigDecimal remainingTotal = total.subtract(discardTotal);
			if (remainingTotal.signum() < 0) {
				remainingTotal = BigDecimal.ZERO;
			}
			if (remainingTotal.signum() > 0) {
				List<ReductionCandidate> remaining = new ArrayList<>();
				for (int i = 0; i < size; i++) {
					if ((mask & (1 << i)) == 0) {
						remaining.add(ordered.get(i));
					}
				}
				Map<AssessorEngine.PlanKey, BigDecimal> reductions =
						allocateNegativePlanDeltasWithCaps(remaining, remainingTotal, minimumRebalancing);
				if (reductions.isEmpty()) {
					continue;
				}
			}
			boolean better = false;
			if (count < bestCount) {
				better = true;
			} else if (count == bestCount) {
				if (bestWeight == null || weightSum.compareTo(bestWeight) < 0) {
					better = true;
				} else if (bestWeight != null && weightSum.compareTo(bestWeight) == 0) {
					BigDecimal overshoot = discardTotal.subtract(total);
					if (overshoot.signum() < 0) {
						overshoot = BigDecimal.ZERO;
					}
					if (bestOvershoot == null || overshoot.compareTo(bestOvershoot) < 0) {
						better = true;
					} else if (bestOvershoot != null && overshoot.compareTo(bestOvershoot) == 0) {
						String key = buildSubsetKey(mask, isins);
						if (bestKey == null || key.compareTo(bestKey) < 0) {
							better = true;
						}
					}
				} else if (bestWeight == null) {
					String key = buildSubsetKey(mask, isins);
					if (bestKey == null || key.compareTo(bestKey) < 0) {
						better = true;
					}
				}
			}
			if (better) {
				bestMask = mask;
				bestCount = count;
				bestWeight = weightSum;
				BigDecimal overshoot = discardTotal.subtract(total);
				if (overshoot.signum() < 0) {
					overshoot = BigDecimal.ZERO;
				}
				bestOvershoot = overshoot;
				bestKey = buildSubsetKey(mask, isins);
			}
		}
		if (bestMask < 0) {
			return List.of();
		}
		List<ReductionCandidate> discards = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			if ((bestMask & (1 << i)) != 0) {
				discards.add(ordered.get(i));
			}
		}
		return discards;
	}

	private String buildSubsetKey(int mask, List<String> isins) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < isins.size(); i++) {
			if ((mask & (1 << i)) == 0) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append('|');
			}
			builder.append(isins.get(i));
		}
		return builder.toString();
	}

	private Map<AssessorEngine.PlanKey, BigDecimal> allocateReductionDeltasByWeight(List<ReductionCandidate> candidates,
																					BigDecimal total) {
		if (candidates == null || candidates.isEmpty() || total == null || total.signum() <= 0) {
			return Map.of();
		}
		List<DeltaCandidate> deltaCandidates = new ArrayList<>();
		List<AssessorEngine.PlanKey> keys = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			ReductionCandidate candidate = candidates.get(i);
			deltaCandidates.add(new DeltaCandidate(i, candidate.weight()));
			keys.add(candidate.key());
		}
		Map<Integer, BigDecimal> allocated = allocateDeltasByWeight(deltaCandidates, total);
		Map<AssessorEngine.PlanKey, BigDecimal> mapped = new LinkedHashMap<>();
		for (int i = 0; i < keys.size(); i++) {
			BigDecimal value = allocated.getOrDefault(i, BigDecimal.ZERO);
			mapped.put(keys.get(i), value);
		}
		return mapped;
	}

	private Map<AssessorEngine.PlanKey, BigDecimal> allocatePlanDeltasByWeight(List<PlanCandidate> candidates,
																			   BigDecimal total) {
		if (candidates == null || candidates.isEmpty() || total == null || total.signum() <= 0) {
			return Map.of();
		}
		List<DeltaCandidate> deltaCandidates = new ArrayList<>();
		List<AssessorEngine.PlanKey> keys = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			PlanCandidate candidate = candidates.get(i);
			deltaCandidates.add(new DeltaCandidate(i, candidate.weight()));
			keys.add(candidate.key());
		}
		Map<Integer, BigDecimal> allocated = allocateDeltasByWeight(deltaCandidates, total);
		Map<AssessorEngine.PlanKey, BigDecimal> mapped = new LinkedHashMap<>();
		for (int i = 0; i < keys.size(); i++) {
			BigDecimal value = allocated.getOrDefault(i, BigDecimal.ZERO);
			mapped.put(keys.get(i), value);
		}
		return mapped;
	}

	private Map<Integer, BigDecimal> allocateDeltasByWeight(List<DeltaCandidate> candidates, BigDecimal target) {
		if (candidates == null || candidates.isEmpty() || target == null || target.signum() <= 0) {
			return Map.of();
		}
		BigDecimal totalWeight = BigDecimal.ZERO;
		for (DeltaCandidate candidate : candidates) {
			totalWeight = totalWeight.add(candidate.weight());
		}
		Map<Integer, BigDecimal> raw = new LinkedHashMap<>();
		if (totalWeight.signum() == 0) {
			BigDecimal per = target.divide(BigDecimal.valueOf(candidates.size()), 8, RoundingMode.HALF_UP);
			for (DeltaCandidate candidate : candidates) {
				raw.put(candidate.index(), per);
			}
		} else {
			for (DeltaCandidate candidate : candidates) {
				BigDecimal weight = candidate.weight().divide(totalWeight, 8, RoundingMode.HALF_UP);
				raw.put(candidate.index(), target.multiply(weight));
			}
		}
		return roundByFraction(raw, target);
	}

	private Map<Integer, BigDecimal> roundByFraction(Map<Integer, BigDecimal> raw, BigDecimal total) {
		if (raw == null || raw.isEmpty()) {
			return Map.of();
		}
		BigDecimal totalRounded = total.setScale(0, RoundingMode.HALF_UP);
		Map<Integer, BigDecimal> rounded = new LinkedHashMap<>();
		Map<Integer, BigDecimal> fractions = new HashMap<>();
		BigDecimal sum = BigDecimal.ZERO;
		for (Map.Entry<Integer, BigDecimal> entry : raw.entrySet()) {
			BigDecimal value = entry.getValue() == null ? BigDecimal.ZERO : entry.getValue();
			BigDecimal floor = value.setScale(0, RoundingMode.FLOOR);
			BigDecimal fraction = value.subtract(floor);
			rounded.put(entry.getKey(), floor);
			fractions.put(entry.getKey(), fraction);
			sum = sum.add(floor);
		}
		int steps = totalRounded.subtract(sum).intValue();
		if (steps > 0 && !fractions.isEmpty()) {
			List<Map.Entry<Integer, BigDecimal>> order = new ArrayList<>(fractions.entrySet());
			order.sort(Map.Entry.<Integer, BigDecimal>comparingByValue().reversed());
			int index = 0;
			while (steps > 0) {
				Map.Entry<Integer, BigDecimal> entry = order.get(index % order.size());
				Integer key = entry.getKey();
				rounded.put(key, rounded.get(key).add(BigDecimal.ONE));
				steps -= 1;
				index += 1;
			}
		}
		return rounded;
	}

	private BigDecimal normalizeMinimum(BigDecimal value) {
		if (value == null || value.signum() <= 0) {
			return BigDecimal.ZERO;
		}
		return value;
	}

	private BigDecimal normalizeWeight(BigDecimal value) {
		if (value == null || value.signum() <= 0) {
			return BigDecimal.ONE;
		}
		return value;
	}

	private BigDecimal safeAmount(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	public record PlanInput(AssessorEngine.PlanKey key, BigDecimal currentAmount, BigDecimal weight) {
	}

	public record Allocation(Map<AssessorEngine.PlanKey, BigDecimal> proposedAmounts,
							 Map<AssessorEngine.PlanKey, BigDecimal> deltas,
							 Set<AssessorEngine.PlanKey> discardedPlans,
							 boolean minRebalanceSuppressed) {
	}

	private record DeltaCandidate(int index, BigDecimal weight) {
	}

	private record PlanCandidate(AssessorEngine.PlanKey key, BigDecimal weight, BigDecimal amount) {
	}

	private record ReductionCandidate(AssessorEngine.PlanKey key,
									  BigDecimal weight,
									  BigDecimal amount,
									  BigDecimal capacity,
									  BigDecimal eligibleCapacity) {
	}
}
