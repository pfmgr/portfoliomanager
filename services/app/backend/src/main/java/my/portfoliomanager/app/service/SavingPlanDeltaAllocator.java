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
		if (minRebalance.signum() > 0 && delta.abs().compareTo(minRebalance) < 0) {
			return new Allocation(Map.copyOf(current), Map.of(), Set.of(), true);
		}

		Map<AssessorEngine.PlanKey, BigDecimal> deltas = allocatePlanDeltasByWeights(
				plans,
				delta,
				minRebalance,
				minimumSavingPlanSize);
		if (deltas.isEmpty()) {
			return new Allocation(Map.copyOf(current), Map.of(), Set.of(), minRebalance.signum() > 0);
		}

		Map<AssessorEngine.PlanKey, BigDecimal> proposed = new LinkedHashMap<>();
		Set<AssessorEngine.PlanKey> discarded = new HashSet<>();
		for (Map.Entry<AssessorEngine.PlanKey, BigDecimal> entry : current.entrySet()) {
			AssessorEngine.PlanKey key = entry.getKey();
			BigDecimal currentAmount = entry.getValue();
			BigDecimal change = deltas.getOrDefault(key, BigDecimal.ZERO);
			BigDecimal newAmount = currentAmount.add(change);
			if (newAmount.signum() < 0) {
				newAmount = BigDecimal.ZERO;
				change = newAmount.subtract(currentAmount);
				deltas.put(key, change);
			}
			proposed.put(key, newAmount);
			if (currentAmount.signum() > 0 && newAmount.signum() == 0) {
				discarded.add(key);
			}
		}
		return new Allocation(Map.copyOf(proposed), Map.copyOf(deltas), Set.copyOf(discarded), false);
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
		List<ReductionCandidate> discards = selectDiscardsForNegativeDelta(reductionCandidates, total, maxReduction);
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
		return Map.of();
	}

	private List<ReductionCandidate> selectDiscardsForNegativeDelta(List<ReductionCandidate> candidates,
																   BigDecimal total,
																   BigDecimal maxReduction) {
		if (candidates == null || candidates.isEmpty() || total == null || total.signum() <= 0) {
			return List.of();
		}
		BigDecimal excess = total.subtract(maxReduction);
		if (excess.signum() <= 0) {
			return List.of();
		}
		List<ReductionCandidate> ordered = new ArrayList<>(candidates);
		ordered.sort(Comparator.comparing((ReductionCandidate candidate) -> candidate.amount().subtract(candidate.eligibleCapacity()))
				.reversed()
				.thenComparing(ReductionCandidate::weight)
				.thenComparing(candidate -> candidate.key().isin()));
		List<ReductionCandidate> discards = new ArrayList<>();
		BigDecimal covered = BigDecimal.ZERO;
		for (ReductionCandidate candidate : ordered) {
			if (covered.compareTo(excess) >= 0) {
				break;
			}
			discards.add(candidate);
			covered = covered.add(candidate.amount().subtract(candidate.eligibleCapacity()));
		}
		return discards;
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
