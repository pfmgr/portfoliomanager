package my.portfoliomanager.app.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AssessorEngine {
	private static final BigDecimal ZERO = BigDecimal.ZERO;
	private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
	private static final BigDecimal DEFAULT_VARIANCE_PCT = new BigDecimal("3.0");
	private static final List<Integer> LAYERS = List.of(1, 2, 3, 4, 5);
	private static final List<Integer> ONE_TIME_PRIORITY = List.of(1, 2, 3, 4);
	private static final int DEFAULT_MIN_INSTRUMENT = 25;

	public AssessorEngineResult assess(AssessorEngineInput input) {
		if (input == null) {
			return null;
		}
		BigDecimal minimumRebalancing = normalizeMinimum(input.minimumRebalancingAmount(), 10);
		BigDecimal minimumSavingPlanSize = normalizeMinimum(input.minimumSavingPlanSize(), 15);
		BigDecimal minimumInstrumentAmount = normalizeMinimum(input.minimumInstrumentAmount(), DEFAULT_MIN_INSTRUMENT);
		Map<Integer, BigDecimal> currentByLayer = initLayerAmounts();
		List<SavingPlanItem> plans = input.savingPlans() == null ? List.of() : input.savingPlans();
		BigDecimal monthlyTotal = ZERO;
		for (SavingPlanItem plan : plans) {
			if (plan == null) {
				continue;
			}
			BigDecimal amount = safeAmount(plan.amount());
			monthlyTotal = monthlyTotal.add(amount);
			currentByLayer.put(plan.layer(), currentByLayer.get(plan.layer()).add(amount));
		}
		BigDecimal savingPlanDelta = input.savingPlanAmountDelta();
		if (savingPlanDelta != null && savingPlanDelta.signum() > 0) {
			monthlyTotal = monthlyTotal.add(savingPlanDelta);
		}

		Map<Integer, BigDecimal> normalizedTargets = normalizeTargets(input.targetWeights());
		Map<Integer, BigDecimal> targetAmounts = buildTargetAmounts(normalizedTargets, monthlyTotal);
		Map<Integer, BigDecimal> effectiveTargetAmounts = targetAmounts;
		Map<Integer, BigDecimal> currentDistribution = computeDistribution(currentByLayer, monthlyTotal);
		boolean budgetEmpty = monthlyTotal.signum() <= 0;
		boolean withinTolerance = budgetEmpty || isWithinTolerance(currentDistribution, normalizedTargets, input.acceptableVariancePct());

		Diagnostics diagnostics;
		List<SavingPlanSuggestion> suggestions = List.of();
		List<String> notes = new ArrayList<>();

		if (budgetEmpty) {
			diagnostics = new Diagnostics(true, 0, ZERO, List.of("No active monthly saving plans to rebalance."));
		} else {
			MinimumSavingPlanAdjustment minimumSavingPlanAdjustment = applyMinimumSavingPlanSize(targetAmounts, minimumSavingPlanSize);
			effectiveTargetAmounts = minimumSavingPlanAdjustment.adjustedAmounts();
			if (!minimumSavingPlanAdjustment.zeroedLayers().isEmpty()) {
				notes.add("Adjusted layers below minimum saving plan size: " + minimumSavingPlanAdjustment.zeroedLayers());
			}
			if (minimumSavingPlanAdjustment.increasedLayerOneToMinimum()) {
				notes.add("Increased Layer 1 to the minimum saving plan size.");
			}
			boolean needsDeltaAllocation = savingPlanDelta != null && savingPlanDelta.signum() > 0;
			boolean effectiveWithinTolerance = withinTolerance
					&& !minimumSavingPlanAdjustment.rebalanced()
					&& !needsDeltaAllocation;
			if (effectiveWithinTolerance) {
				notes.add("Within tolerance; no saving plan changes proposed.");
				diagnostics = new Diagnostics(true, 0, ZERO, List.copyOf(notes));
			} else {
				Map<Integer, BigDecimal> proposalAmounts = minimumSavingPlanAdjustment.adjustedAmounts();
				Map<Integer, BigDecimal> deltas = computeDeltas(proposalAmounts, currentByLayer);
				LayerDeltaResult layerDeltaResult = adjustLayerDeltas(deltas, minimumRebalancing);
				notes.addAll(layerDeltaResult.redistributionNotes());
				diagnostics = new Diagnostics(withinTolerance,
						layerDeltaResult.suppressedCount(),
						layerDeltaResult.suppressedAmount(),
						List.copyOf(notes));
				suggestions = buildSavingPlanSuggestions(plans, layerDeltaResult.adjustedDeltas(),
						minimumRebalancing, minimumSavingPlanSize);
			}
		}

		OneTimeAllocation oneTimeAllocation = null;
		if (input.oneTimeAmount() != null && input.oneTimeAmount().signum() > 0) {
			oneTimeAllocation = buildOneTimeAllocation(input.oneTimeAmount(),
					normalizedTargets,
					input.holdingsByLayer(),
					plans,
					minimumRebalancing,
					minimumInstrumentAmount,
					input.instrumentAllocationEnabled());
		}

		return new AssessorEngineResult(
				input.selectedProfile(),
				monthlyTotal,
				Map.copyOf(currentByLayer),
				Map.copyOf(effectiveTargetAmounts),
				List.copyOf(suggestions),
				oneTimeAllocation,
				diagnostics
		);
	}

	static LayerDeltaResult adjustLayerDeltas(Map<Integer, BigDecimal> deltas, BigDecimal minimumRebalancing) {
		Map<Integer, BigDecimal> adjusted = initLayerAmounts();
		BigDecimal targetTotal = sumAmounts(deltas);
		if (deltas == null || deltas.isEmpty() || minimumRebalancing == null || minimumRebalancing.signum() <= 0) {
			for (int layer : LAYERS) {
				adjusted.put(layer, deltas == null ? ZERO : deltas.getOrDefault(layer, ZERO));
			}
			return new LayerDeltaResult(Map.copyOf(adjusted), 0, ZERO, List.of());
		}
		Set<Integer> suppressedLayers = new LinkedHashSet<>();
		BigDecimal suppressedTotal = ZERO;
		for (int layer : LAYERS) {
			BigDecimal delta = deltas.getOrDefault(layer, ZERO);
			if (delta.signum() != 0 && delta.abs().compareTo(minimumRebalancing) < 0) {
				adjusted.put(layer, ZERO);
				suppressedLayers.add(layer);
				suppressedTotal = suppressedTotal.add(delta.abs());
			} else {
				adjusted.put(layer, delta);
			}
		}

		List<String> notes = new ArrayList<>();
		if (!suppressedLayers.isEmpty()) {
			notes.add("Suppressed layer deltas below minimum: " + suppressedLayers);
		}

		// Re-balance toward the target total by trimming the largest deltas first.
		BigDecimal residual = sumAmounts(adjusted).subtract(targetTotal);
		int guard = 0;
		while (residual.signum() != 0 && guard < 12) {
			guard += 1;
			RedistributionResult redistribution = residual.signum() > 0
					? reducePositiveDeltas(adjusted, minimumRebalancing, residual)
					: reduceNegativeDeltas(adjusted, minimumRebalancing, residual.abs());
			if (redistribution.amountReduced().signum() == 0) {
				redistribution = residual.signum() > 0
						? increaseNegativeDeltas(adjusted, residual)
						: increasePositiveDeltas(adjusted, residual.abs());
				if (redistribution.amountReduced().signum() == 0) {
					break;
				}
			}
			notes.add(redistribution.note());
			residual = sumAmounts(adjusted).subtract(targetTotal);
		}

		return new LayerDeltaResult(Map.copyOf(adjusted), suppressedLayers.size(), suppressedTotal, List.copyOf(notes));
	}

	private List<SavingPlanSuggestion> buildSavingPlanSuggestions(List<SavingPlanItem> plans,
																   Map<Integer, BigDecimal> layerDeltas,
																   BigDecimal minimumRebalancing,
																   BigDecimal minimumSavingPlanSize) {
		if (plans == null || plans.isEmpty() || layerDeltas == null) {
			return List.of();
		}
		Map<PlanKey, SavingPlanItem> planMap = new LinkedHashMap<>();
		for (SavingPlanItem plan : plans) {
			if (plan == null) {
				continue;
			}
			planMap.put(new PlanKey(plan.isin(), plan.depotId()), plan);
		}
		Map<PlanKey, BigDecimal> deltas = new LinkedHashMap<>();
		BigDecimal targetTotal = sumAmounts(layerDeltas);
		Map<Integer, List<SavingPlanItem>> byLayer = groupByLayer(plans);

		for (int layer : LAYERS) {
			BigDecimal layerDelta = layerDeltas.getOrDefault(layer, ZERO);
			if (layerDelta.signum() == 0) {
				continue;
			}
			List<SavingPlanItem> layerPlans = byLayer.getOrDefault(layer, List.of());
			Map<PlanKey, BigDecimal> layerAllocations = allocateLayerDelta(layerPlans, layerDelta, minimumRebalancing);
			if (layerAllocations.isEmpty()) {
				continue;
			}
			BigDecimal layerTotal = sumAmounts(layerAllocations);
			layerAllocations = applyMinimumSavingPlanSize(planMap, layerAllocations, minimumSavingPlanSize, minimumRebalancing);
			layerAllocations = balancePlanDeltas(planMap, layerAllocations, minimumRebalancing, layerTotal);
			layerAllocations = applyMinimumSavingPlanSize(planMap, layerAllocations, minimumSavingPlanSize, minimumRebalancing);
			layerAllocations = balancePlanDeltas(planMap, layerAllocations, minimumRebalancing, layerTotal);
			deltas.putAll(layerAllocations);
		}
		deltas = applyResidualDelta(planMap, deltas, targetTotal, minimumSavingPlanSize);

		List<SavingPlanSuggestion> suggestions = new ArrayList<>();
		for (Map.Entry<PlanKey, BigDecimal> entry : deltas.entrySet()) {
			BigDecimal delta = entry.getValue();
			if (delta == null || delta.signum() == 0) {
				continue;
			}
			if (minimumRebalancing != null && delta.abs().compareTo(minimumRebalancing) < 0) {
				continue;
			}
			SavingPlanItem plan = planMap.get(entry.getKey());
			if (plan == null) {
				continue;
			}
			BigDecimal oldAmount = safeAmount(plan.amount());
			BigDecimal newAmount = oldAmount.add(delta);
			if (newAmount.compareTo(ZERO) < 0) {
				newAmount = ZERO;
				delta = newAmount.subtract(oldAmount);
			}
			String type = determineType(oldAmount, newAmount);
			String rationale = buildRationale(type);
			suggestions.add(new SavingPlanSuggestion(
					type,
					plan.isin(),
					plan.depotId(),
					oldAmount,
					newAmount,
					delta,
					rationale
			));
		}
		suggestions.sort(Comparator.comparing(SavingPlanSuggestion::type)
				.thenComparing(SavingPlanSuggestion::isin));
		return List.copyOf(suggestions);
	}

	private Map<PlanKey, BigDecimal> allocateLayerDelta(List<SavingPlanItem> plans,
														BigDecimal layerDelta,
														BigDecimal minimumRebalancing) {
		if (plans == null || plans.isEmpty() || layerDelta == null || layerDelta.signum() == 0) {
			return Map.of();
		}
		List<SavingPlanItem> ordered = new ArrayList<>(plans);
		ordered.sort(planAmountComparator());

		boolean negative = layerDelta.signum() < 0;
		BigDecimal target = layerDelta.abs();

		List<SavingPlanItem> candidates = new ArrayList<>(ordered);
		while (!candidates.isEmpty()) {
			Map<PlanKey, BigDecimal> allocations = allocateProportional(candidates, target);
			boolean allAboveMinimum = true;
			for (BigDecimal value : allocations.values()) {
				if (value.signum() != 0 && minimumRebalancing != null
						&& value.abs().compareTo(minimumRebalancing) < 0) {
					allAboveMinimum = false;
					break;
				}
			}
			if (allAboveMinimum) {
				Map<PlanKey, BigDecimal> signed = new LinkedHashMap<>();
				allocations.forEach((key, value) -> signed.put(key, negative ? value.negate() : value));
				if (negative) {
					Map<PlanKey, BigDecimal> capped = new LinkedHashMap<>();
					for (SavingPlanItem plan : candidates) {
						PlanKey key = new PlanKey(plan.isin(), plan.depotId());
						BigDecimal delta = signed.getOrDefault(key, ZERO);
						BigDecimal maxDecrease = safeAmount(plan.amount());
						if (delta.signum() < 0 && delta.abs().compareTo(maxDecrease) > 0) {
							delta = maxDecrease.negate();
						}
						capped.put(key, delta);
					}
					return capped;
				}
				return signed;
			}
			candidates.remove(candidates.size() - 1);
		}
		return Map.of();
	}

	private Map<PlanKey, BigDecimal> allocateProportional(List<SavingPlanItem> plans, BigDecimal target) {
		Map<PlanKey, BigDecimal> allocations = new LinkedHashMap<>();
		if (plans == null || plans.isEmpty() || target == null || target.signum() == 0) {
			return allocations;
		}
		BigDecimal total = ZERO;
		for (SavingPlanItem plan : plans) {
			total = total.add(safeAmount(plan.amount()));
		}
		Map<PlanKey, BigDecimal> raw = new LinkedHashMap<>();
		if (total.signum() == 0) {
			BigDecimal per = target.divide(new BigDecimal(plans.size()), 8, RoundingMode.HALF_UP);
			for (SavingPlanItem plan : plans) {
				raw.put(new PlanKey(plan.isin(), plan.depotId()), per);
			}
		} else {
			for (SavingPlanItem plan : plans) {
				BigDecimal weight = safeAmount(plan.amount()).divide(total, 8, RoundingMode.HALF_UP);
				raw.put(new PlanKey(plan.isin(), plan.depotId()), target.multiply(weight));
			}
		}
		Map<PlanKey, BigDecimal> rounded = roundByFraction(raw, target);
		allocations.putAll(rounded);
		return allocations;
	}

	private Map<PlanKey, BigDecimal> applyMinimumSavingPlanSize(Map<PlanKey, SavingPlanItem> plans,
																 Map<PlanKey, BigDecimal> deltas,
																 BigDecimal minimumSavingPlanSize,
																 BigDecimal minimumRebalancing) {
		if (plans == null || plans.isEmpty() || deltas == null || deltas.isEmpty()
				|| minimumSavingPlanSize == null || minimumSavingPlanSize.signum() <= 0) {
			return deltas == null ? Map.of() : Map.copyOf(deltas);
		}
		Map<PlanKey, BigDecimal> adjusted = new LinkedHashMap<>(deltas);
		for (Map.Entry<PlanKey, BigDecimal> entry : deltas.entrySet()) {
			SavingPlanItem plan = plans.get(entry.getKey());
			if (plan == null) {
				continue;
			}
			BigDecimal delta = entry.getValue();
			if (delta == null || delta.signum() == 0) {
				continue;
			}
			BigDecimal oldAmount = safeAmount(plan.amount());
			BigDecimal newAmount = oldAmount.add(delta);
			if (newAmount.signum() > 0 && newAmount.compareTo(minimumSavingPlanSize) < 0) {
				if (delta.signum() < 0 && (minimumRebalancing == null
						|| delta.abs().compareTo(minimumRebalancing) >= 0)) {
					adjusted.put(entry.getKey(), oldAmount.negate());
				} else {
					adjusted.put(entry.getKey(), ZERO);
				}
			}
		}
		return Map.copyOf(adjusted);
	}

	private Map<PlanKey, BigDecimal> balancePlanDeltas(Map<PlanKey, SavingPlanItem> plans,
													   Map<PlanKey, BigDecimal> deltas,
													   BigDecimal minimumRebalancing,
													   BigDecimal targetTotal) {
		if (plans == null || deltas == null || deltas.isEmpty()) {
			return deltas == null ? Map.of() : Map.copyOf(deltas);
		}
		Map<PlanKey, BigDecimal> adjusted = new LinkedHashMap<>(deltas);
		BigDecimal desiredTotal = targetTotal == null ? ZERO : targetTotal;
		// Keep overall plan deltas near the desired total while respecting the minimum rebalancing amount.
		BigDecimal residual = sumAmounts(adjusted).subtract(desiredTotal);
		int guard = 0;
		while (residual.signum() != 0 && guard < 18) {
			guard += 1;
			if (residual.signum() > 0) {
				BigDecimal reduced = reducePositivePlanDeltas(plans, adjusted, minimumRebalancing, residual);
				if (reduced.signum() == 0) {
					BigDecimal increased = increaseNegativePlanDeltas(plans, adjusted, residual);
					if (increased.signum() == 0) {
						break;
					}
				}
			} else {
				BigDecimal reduced = reduceNegativePlanDeltas(plans, adjusted, minimumRebalancing, residual.abs());
				if (reduced.signum() == 0) {
					BigDecimal increased = increasePositivePlanDeltas(plans, adjusted, residual.abs());
					if (increased.signum() == 0) {
						break;
					}
				}
			}
			residual = sumAmounts(adjusted).subtract(desiredTotal);
		}
		return Map.copyOf(adjusted);
	}

	private Map<PlanKey, BigDecimal> applyResidualDelta(Map<PlanKey, SavingPlanItem> plans,
														Map<PlanKey, BigDecimal> deltas,
														BigDecimal targetTotal,
														BigDecimal minimumSavingPlanSize) {
		if (plans == null || plans.isEmpty() || deltas == null || deltas.isEmpty()) {
			return deltas == null ? Map.of() : Map.copyOf(deltas);
		}
		BigDecimal desiredTotal = targetTotal == null ? ZERO : targetTotal;
		BigDecimal residual = desiredTotal.subtract(sumAmounts(deltas));
		if (residual.signum() == 0) {
			return Map.copyOf(deltas);
		}
		List<PlanKey> candidates = new ArrayList<>();
		for (Map.Entry<PlanKey, BigDecimal> entry : deltas.entrySet()) {
			if (entry.getValue() != null && entry.getValue().signum() == residual.signum()) {
				candidates.add(entry.getKey());
			}
		}
		if (candidates.isEmpty()) {
			candidates.addAll(deltas.keySet());
		}
		candidates.sort(planDeltaComparator(plans, deltas).reversed());
		PlanKey selected = null;
		for (PlanKey key : candidates) {
			SavingPlanItem plan = plans.get(key);
			if (plan == null) {
				continue;
			}
			BigDecimal current = deltas.getOrDefault(key, ZERO);
			BigDecimal updated = current.add(residual);
			BigDecimal minDelta = safeAmount(plan.amount()).negate();
			if (updated.compareTo(minDelta) < 0) {
				continue;
			}
			BigDecimal newAmount = safeAmount(plan.amount()).add(updated);
			if (newAmount.signum() > 0 && minimumSavingPlanSize != null
					&& minimumSavingPlanSize.signum() > 0
					&& newAmount.compareTo(minimumSavingPlanSize) < 0) {
				continue;
			}
			selected = key;
			break;
		}
		if (selected == null && !candidates.isEmpty()) {
			selected = candidates.get(0);
		}
		if (selected == null) {
			return Map.copyOf(deltas);
		}
		Map<PlanKey, BigDecimal> adjusted = new LinkedHashMap<>(deltas);
		adjusted.put(selected, adjusted.getOrDefault(selected, ZERO).add(residual));
		return Map.copyOf(adjusted);
	}

	private BigDecimal reducePositivePlanDeltas(Map<PlanKey, SavingPlanItem> plans,
												Map<PlanKey, BigDecimal> adjusted,
												BigDecimal minimumRebalancing,
												BigDecimal residual) {
		List<PlanKey> candidates = new ArrayList<>();
		for (Map.Entry<PlanKey, BigDecimal> entry : adjusted.entrySet()) {
			if (entry.getValue() != null && entry.getValue().signum() > 0) {
				candidates.add(entry.getKey());
			}
		}
		candidates.sort(planDeltaComparator(plans, adjusted));
		BigDecimal remaining = residual;
		for (PlanKey key : candidates) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal delta = adjusted.getOrDefault(key, ZERO);
			BigDecimal reduction = delta.min(remaining);
			BigDecimal updated = delta.subtract(reduction);
			if (updated.signum() > 0 && minimumRebalancing != null
					&& updated.abs().compareTo(minimumRebalancing) < 0) {
				reduction = delta;
				updated = ZERO;
			}
			adjusted.put(key, updated);
			remaining = remaining.subtract(reduction);
		}
		return residual.subtract(remaining);
	}

	private BigDecimal reduceNegativePlanDeltas(Map<PlanKey, SavingPlanItem> plans,
												Map<PlanKey, BigDecimal> adjusted,
												BigDecimal minimumRebalancing,
												BigDecimal residual) {
		List<PlanKey> candidates = new ArrayList<>();
		for (Map.Entry<PlanKey, BigDecimal> entry : adjusted.entrySet()) {
			if (entry.getValue() != null && entry.getValue().signum() < 0) {
				candidates.add(entry.getKey());
			}
		}
		candidates.sort(planDeltaComparator(plans, adjusted));
		BigDecimal remaining = residual;
		for (PlanKey key : candidates) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal delta = adjusted.getOrDefault(key, ZERO);
			BigDecimal reduction = delta.abs().min(remaining);
			BigDecimal updated = delta.add(reduction);
			if (updated.signum() < 0 && minimumRebalancing != null
					&& updated.abs().compareTo(minimumRebalancing) < 0) {
				reduction = delta.abs();
				updated = ZERO;
			}
			adjusted.put(key, updated);
			remaining = remaining.subtract(reduction);
		}
		return residual.subtract(remaining);
	}

	private BigDecimal increasePositivePlanDeltas(Map<PlanKey, SavingPlanItem> plans,
												  Map<PlanKey, BigDecimal> adjusted,
												  BigDecimal residual) {
		List<PlanKey> candidates = new ArrayList<>();
		for (Map.Entry<PlanKey, BigDecimal> entry : adjusted.entrySet()) {
			if (entry.getValue() != null && entry.getValue().signum() >= 0) {
				candidates.add(entry.getKey());
			}
		}
		if (candidates.isEmpty()) {
			return ZERO;
		}
		candidates.sort(planDeltaComparator(plans, adjusted).reversed());
		BigDecimal remaining = residual;
		for (PlanKey key : candidates) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal delta = adjusted.getOrDefault(key, ZERO);
			adjusted.put(key, delta.add(remaining));
			remaining = ZERO;
		}
		return residual.subtract(remaining);
	}

	private BigDecimal increaseNegativePlanDeltas(Map<PlanKey, SavingPlanItem> plans,
												  Map<PlanKey, BigDecimal> adjusted,
												  BigDecimal residual) {
		List<PlanKey> candidates = new ArrayList<>();
		for (Map.Entry<PlanKey, BigDecimal> entry : adjusted.entrySet()) {
			if (entry.getValue() != null && entry.getValue().signum() < 0) {
				candidates.add(entry.getKey());
			}
		}
		if (candidates.isEmpty()) {
			return ZERO;
		}
		candidates.sort(planDeltaComparator(plans, adjusted).reversed());
		BigDecimal remaining = residual;
		for (PlanKey key : candidates) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal delta = adjusted.getOrDefault(key, ZERO);
			adjusted.put(key, delta.subtract(remaining));
			remaining = ZERO;
		}
		return residual.subtract(remaining);
	}

	private OneTimeAllocation buildOneTimeAllocation(BigDecimal oneTimeAmount,
													 Map<Integer, BigDecimal> targetWeights,
													 Map<Integer, BigDecimal> holdingsByLayer,
													 List<SavingPlanItem> plans,
													 BigDecimal minimumRebalancing,
													 BigDecimal minimumInstrumentAmount,
													 boolean instrumentAllocationEnabled) {
		if (oneTimeAmount == null || oneTimeAmount.signum() <= 0) {
			return null;
		}
		if (minimumRebalancing != null && oneTimeAmount.compareTo(minimumRebalancing) < 0) {
			return new OneTimeAllocation(Map.of(), null);
		}
		Map<Integer, BigDecimal> layerAllocations = computeOneTimeLayers(oneTimeAmount, targetWeights, holdingsByLayer);
		layerAllocations = applyMinimumRebalancingToLayers(layerAllocations, minimumRebalancing);
		layerAllocations = applyMinimumInstrumentToLayers(layerAllocations, minimumInstrumentAmount);

		Map<String, BigDecimal> instrumentBuckets = null;
		if (instrumentAllocationEnabled && plans != null && !plans.isEmpty()) {
			instrumentBuckets = allocateInstruments(layerAllocations, plans, minimumRebalancing, minimumInstrumentAmount);
		}
		return new OneTimeAllocation(Map.copyOf(layerAllocations),
				instrumentBuckets == null ? null : Map.copyOf(instrumentBuckets));
	}

	private Map<Integer, BigDecimal> computeOneTimeLayers(BigDecimal oneTimeAmount,
														  Map<Integer, BigDecimal> targetWeights,
														  Map<Integer, BigDecimal> holdingsByLayer) {
		Map<Integer, BigDecimal> normalizedTargets = normalizeTargets(targetWeights);
		Map<Integer, BigDecimal> allocations;
		if (holdingsByLayer != null && !holdingsByLayer.isEmpty()) {
			Map<Integer, BigDecimal> holdingsDistribution = computeDistribution(holdingsByLayer, sumAmounts(holdingsByLayer));
			Map<Integer, BigDecimal> gaps = new LinkedHashMap<>();
			BigDecimal gapTotal = ZERO;
			for (int layer : ONE_TIME_PRIORITY) {
				BigDecimal target = normalizedTargets.getOrDefault(layer, ZERO);
				BigDecimal current = holdingsDistribution.getOrDefault(layer, ZERO);
				BigDecimal gap = target.subtract(current);
				if (gap.signum() > 0) {
					gaps.put(layer, gap);
					gapTotal = gapTotal.add(gap);
				}
			}
			if (gapTotal.signum() > 0) {
				allocations = allocateByWeights(oneTimeAmount, gaps);
				return allocations;
			}
		}
		Map<Integer, BigDecimal> reducedTargets = new LinkedHashMap<>();
		BigDecimal sum = ZERO;
		for (int layer : ONE_TIME_PRIORITY) {
			BigDecimal weight = normalizedTargets.getOrDefault(layer, ZERO);
			reducedTargets.put(layer, weight);
			sum = sum.add(weight);
		}
		if (sum.signum() == 0) {
			return Map.of();
		}
		Map<Integer, BigDecimal> weights = new LinkedHashMap<>();
		for (int layer : ONE_TIME_PRIORITY) {
			weights.put(layer, reducedTargets.get(layer).divide(sum, 8, RoundingMode.HALF_UP));
		}
		return allocateByWeights(oneTimeAmount, weights);
	}

	private Map<Integer, BigDecimal> allocateByWeights(BigDecimal total, Map<Integer, BigDecimal> weights) {
		Map<Integer, BigDecimal> raw = new LinkedHashMap<>();
		if (weights == null || weights.isEmpty() || total == null || total.signum() <= 0) {
			return raw;
		}
		for (Map.Entry<Integer, BigDecimal> entry : weights.entrySet()) {
			raw.put(entry.getKey(), total.multiply(entry.getValue()));
		}
		return roundLayerAmounts(raw, total);
	}

	private Map<Integer, BigDecimal> applyMinimumRebalancingToLayers(Map<Integer, BigDecimal> allocations,
																	 BigDecimal minimumRebalancing) {
		if (allocations == null || allocations.isEmpty() || minimumRebalancing == null
				|| minimumRebalancing.signum() <= 0) {
			return allocations == null ? Map.of() : allocations;
		}
		Map<Integer, BigDecimal> adjusted = new LinkedHashMap<>();
		BigDecimal suppressed = ZERO;
		for (int layer : ONE_TIME_PRIORITY) {
			BigDecimal amount = allocations.getOrDefault(layer, ZERO);
			if (amount.signum() > 0 && amount.compareTo(minimumRebalancing) < 0) {
				suppressed = suppressed.add(amount);
				adjusted.put(layer, ZERO);
			} else {
				adjusted.put(layer, amount);
			}
		}
		if (suppressed.signum() > 0) {
			for (int layer : ONE_TIME_PRIORITY) {
				BigDecimal existing = adjusted.getOrDefault(layer, ZERO);
				if (existing.signum() > 0) {
					adjusted.put(layer, existing.add(suppressed));
					suppressed = ZERO;
					break;
				}
			}
			if (suppressed.signum() > 0) {
				adjusted.put(ONE_TIME_PRIORITY.get(0), adjusted.getOrDefault(ONE_TIME_PRIORITY.get(0), ZERO).add(suppressed));
			}
		}
		return adjusted;
	}

	private Map<Integer, BigDecimal> applyMinimumInstrumentToLayers(Map<Integer, BigDecimal> allocations,
																	 BigDecimal minimumInstrumentAmount) {
		if (allocations == null || allocations.isEmpty() || minimumInstrumentAmount == null
				|| minimumInstrumentAmount.signum() <= 0) {
			return allocations == null ? Map.of() : allocations;
		}
		Map<Integer, BigDecimal> adjusted = new LinkedHashMap<>();
		for (int layer : ONE_TIME_PRIORITY) {
			adjusted.put(layer, allocations.getOrDefault(layer, ZERO));
		}
		for (int index = ONE_TIME_PRIORITY.size() - 1; index > 0; index -= 1) {
			int layer = ONE_TIME_PRIORITY.get(index);
			int previousLayer = ONE_TIME_PRIORITY.get(index - 1);
			BigDecimal amount = adjusted.getOrDefault(layer, ZERO);
			if (amount.signum() > 0 && amount.compareTo(minimumInstrumentAmount) < 0) {
				adjusted.put(layer, ZERO);
				adjusted.put(previousLayer, adjusted.getOrDefault(previousLayer, ZERO).add(amount));
			}
		}
		return adjusted;
	}

	private Map<String, BigDecimal> allocateInstruments(Map<Integer, BigDecimal> layerAllocations,
														List<SavingPlanItem> plans,
														BigDecimal minimumRebalancing,
														BigDecimal minimumInstrumentAmount) {
		Map<String, BigDecimal> instrumentBuckets = new LinkedHashMap<>();
		BigDecimal minimumAllocation = resolveMinimumAllocation(minimumRebalancing, minimumInstrumentAmount);
		Map<Integer, List<SavingPlanItem>> byLayer = groupByLayer(plans);
		for (int layer : ONE_TIME_PRIORITY) {
			BigDecimal layerAmount = layerAllocations.getOrDefault(layer, ZERO);
			if (layerAmount.signum() <= 0) {
				continue;
			}
			if (minimumAllocation != null && minimumAllocation.signum() > 0
					&& layerAmount.compareTo(minimumAllocation) < 0) {
				continue;
			}
			List<SavingPlanItem> layerPlans = byLayer.getOrDefault(layer, List.of());
			if (layerPlans.isEmpty()) {
				continue;
			}
			Map<String, BigDecimal> allocations = allocateInstrumentLayer(layerPlans, layerAmount);
			allocations = suppressInstrumentAllocations(allocations, layerPlans, minimumAllocation);
			for (Map.Entry<String, BigDecimal> entry : allocations.entrySet()) {
				BigDecimal value = entry.getValue();
				if (value != null && value.signum() > 0) {
					instrumentBuckets.put(entry.getKey(), value);
				}
			}
		}
		return instrumentBuckets;
	}

	private Map<String, BigDecimal> allocateInstrumentLayer(List<SavingPlanItem> plans, BigDecimal layerAmount) {
		BigDecimal total = ZERO;
		for (SavingPlanItem plan : plans) {
			total = total.add(safeAmount(plan.amount()));
		}
		Map<String, BigDecimal> raw = new LinkedHashMap<>();
		if (total.signum() == 0) {
			BigDecimal per = layerAmount.divide(new BigDecimal(plans.size()), 8, RoundingMode.HALF_UP);
			for (SavingPlanItem plan : plans) {
				raw.put(plan.isin(), per);
			}
		} else {
			for (SavingPlanItem plan : plans) {
				BigDecimal weight = safeAmount(plan.amount()).divide(total, 8, RoundingMode.HALF_UP);
				raw.put(plan.isin(), layerAmount.multiply(weight));
			}
		}
		return roundInstrumentAmounts(raw, layerAmount);
	}

	private Map<String, BigDecimal> suppressInstrumentAllocations(Map<String, BigDecimal> allocations,
																  List<SavingPlanItem> plans,
																  BigDecimal minimumAllocation) {
		if (allocations == null || allocations.isEmpty() || minimumAllocation == null
				|| minimumAllocation.signum() <= 0) {
			return allocations == null ? Map.of() : allocations;
		}
		Map<String, BigDecimal> adjusted = new LinkedHashMap<>(allocations);
		BigDecimal suppressed = ZERO;
		for (Map.Entry<String, BigDecimal> entry : allocations.entrySet()) {
			BigDecimal value = entry.getValue();
			if (value.signum() > 0 && value.compareTo(minimumAllocation) < 0) {
				suppressed = suppressed.add(value);
				adjusted.put(entry.getKey(), ZERO);
			}
		}
		if (suppressed.signum() > 0) {
			List<SavingPlanItem> ordered = new ArrayList<>(plans);
			ordered.sort(planAmountComparator());
			for (SavingPlanItem plan : ordered) {
				BigDecimal existing = adjusted.getOrDefault(plan.isin(), ZERO);
				if (existing.signum() > 0) {
					adjusted.put(plan.isin(), existing.add(suppressed));
					suppressed = ZERO;
					break;
				}
			}
			if (suppressed.signum() > 0 && !ordered.isEmpty()) {
				SavingPlanItem first = ordered.get(0);
				adjusted.put(first.isin(), adjusted.getOrDefault(first.isin(), ZERO).add(suppressed));
			}
		}
		return adjusted;
	}

	private Map<Integer, BigDecimal> buildTargetAmounts(Map<Integer, BigDecimal> targets, BigDecimal total) {
		Map<Integer, BigDecimal> desired = initLayerAmounts();
		if (targets == null || targets.isEmpty() || total == null) {
			return desired;
		}
		for (int layer : LAYERS) {
			BigDecimal weight = targets.getOrDefault(layer, ZERO);
			desired.put(layer, total.multiply(weight));
		}
		return roundProposalTargets(desired, total);
	}

	private Map<Integer, BigDecimal> roundProposalTargets(Map<Integer, BigDecimal> desiredTargets, BigDecimal total) {
		if (desiredTargets == null || desiredTargets.isEmpty()) {
			return desiredTargets;
		}
		BigDecimal totalRounded = total == null ? ZERO : total.setScale(0, RoundingMode.HALF_UP);
		Map<Integer, BigDecimal> rounded = new LinkedHashMap<>();
		Map<Integer, BigDecimal> fractions = new LinkedHashMap<>();
		BigDecimal sum = ZERO;

		for (int layer : LAYERS) {
			BigDecimal desired = desiredTargets.getOrDefault(layer, ZERO);
			if (desired.signum() < 0) {
				desired = ZERO;
			}
			BigDecimal floor = desired.setScale(0, RoundingMode.FLOOR);
			BigDecimal fraction = desired.subtract(floor);
			BigDecimal ceil = desired.setScale(0, RoundingMode.CEILING);
			rounded.put(layer, ceil);
			fractions.put(layer, fraction);
			sum = sum.add(ceil);
		}

		int diff = sum.subtract(totalRounded).intValue();
		if (diff > 0) {
			List<Integer> layers = sortLayersByFraction(fractions, true);
			int index = 0;
			while (diff > 0 && !layers.isEmpty()) {
				int layer = layers.get(index % layers.size());
				BigDecimal current = rounded.get(layer);
				if (current.signum() > 0) {
					rounded.put(layer, current.subtract(BigDecimal.ONE));
					diff -= 1;
				}
				index++;
			}
		} else if (diff < 0) {
			List<Integer> layers = sortLayersByFraction(fractions, false);
			int index = 0;
			while (diff < 0 && !layers.isEmpty()) {
				int layer = layers.get(index % layers.size());
				rounded.put(layer, rounded.get(layer).add(BigDecimal.ONE));
				diff += 1;
				index++;
			}
		}
		return rounded;
	}

	private MinimumSavingPlanAdjustment applyMinimumSavingPlanSize(Map<Integer, BigDecimal> proposalAmounts,
																   BigDecimal minimumSavingPlanSize) {
		if (proposalAmounts == null || minimumSavingPlanSize == null || minimumSavingPlanSize.signum() <= 0) {
			return new MinimumSavingPlanAdjustment(proposalAmounts, List.of(), false, false);
		}
		Map<Integer, BigDecimal> adjusted = new LinkedHashMap<>();
		for (int layer : LAYERS) {
			adjusted.put(layer, proposalAmounts.getOrDefault(layer, ZERO));
		}
		List<Integer> zeroedLayers = new ArrayList<>();
		for (int layer = 5; layer >= 2; layer--) {
			BigDecimal amount = adjusted.getOrDefault(layer, ZERO);
			if (amount.signum() > 0 && amount.compareTo(minimumSavingPlanSize) < 0) {
				adjusted.put(layer, ZERO);
				zeroedLayers.add(layer);
				adjusted.put(layer - 1, adjusted.getOrDefault(layer - 1, ZERO).add(amount));
			}
		}
		boolean increasedLayerOne = false;
		BigDecimal layerOneAmount = adjusted.getOrDefault(1, ZERO);
		boolean onlyLayerOne = true;
		int nonZeroLayers = 0;
		for (int layer : LAYERS) {
			if (adjusted.getOrDefault(layer, ZERO).signum() > 0) {
				nonZeroLayers += 1;
				if (layer != 1) {
					onlyLayerOne = false;
				}
			}
		}
		if (nonZeroLayers == 1 && onlyLayerOne && layerOneAmount.signum() > 0
				&& layerOneAmount.compareTo(minimumSavingPlanSize) < 0) {
			adjusted.put(1, minimumSavingPlanSize);
			increasedLayerOne = true;
		}
		boolean rebalanced = false;
		for (int layer : LAYERS) {
			BigDecimal original = proposalAmounts.getOrDefault(layer, ZERO);
			BigDecimal updated = adjusted.getOrDefault(layer, ZERO);
			if (original.compareTo(updated) != 0) {
				rebalanced = true;
				break;
			}
		}
		return new MinimumSavingPlanAdjustment(Map.copyOf(adjusted), List.copyOf(zeroedLayers), rebalanced, increasedLayerOne);
	}

	private Map<Integer, BigDecimal> computeDeltas(Map<Integer, BigDecimal> targets, Map<Integer, BigDecimal> current) {
		Map<Integer, BigDecimal> deltas = initLayerAmounts();
		for (int layer : LAYERS) {
			BigDecimal target = targets.getOrDefault(layer, ZERO);
			BigDecimal value = current.getOrDefault(layer, ZERO);
			deltas.put(layer, target.subtract(value));
		}
		return deltas;
	}

	private Map<Integer, BigDecimal> normalizeTargets(Map<Integer, BigDecimal> raw) {
		Map<Integer, BigDecimal> normalized = initLayerAmounts();
		if (raw == null || raw.isEmpty()) {
			return normalized;
		}
		BigDecimal total = ZERO;
		for (int layer : LAYERS) {
			total = total.add(raw.getOrDefault(layer, ZERO));
		}
		if (total.signum() <= 0) {
			return normalized;
		}
		for (int layer : LAYERS) {
			BigDecimal value = raw.getOrDefault(layer, ZERO);
			normalized.put(layer, value.divide(total, 8, RoundingMode.HALF_UP));
		}
		return normalized;
	}

	private Map<Integer, BigDecimal> computeDistribution(Map<Integer, BigDecimal> values, BigDecimal total) {
		Map<Integer, BigDecimal> distribution = initLayerAmounts();
		if (values == null || total == null || total.signum() == 0) {
			return distribution;
		}
		for (int layer : LAYERS) {
			BigDecimal value = values.getOrDefault(layer, ZERO);
			distribution.put(layer, value.divide(total, 8, RoundingMode.HALF_UP));
		}
		return distribution;
	}

	private boolean isWithinTolerance(Map<Integer, BigDecimal> actual, Map<Integer, BigDecimal> target, BigDecimal variancePct) {
		BigDecimal tolerance = variancePct == null ? DEFAULT_VARIANCE_PCT : variancePct;
		tolerance = tolerance.divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP);
		for (int layer : LAYERS) {
			BigDecimal actualValue = actual.getOrDefault(layer, ZERO);
			BigDecimal targetValue = target.getOrDefault(layer, ZERO);
			if (actualValue.subtract(targetValue).abs().compareTo(tolerance) > 0) {
				return false;
			}
		}
		return true;
	}

	private static Map<Integer, BigDecimal> initLayerAmounts() {
		Map<Integer, BigDecimal> map = new LinkedHashMap<>();
		for (int layer : LAYERS) {
			map.put(layer, ZERO);
		}
		return map;
	}

	private Map<Integer, List<SavingPlanItem>> groupByLayer(List<SavingPlanItem> plans) {
		Map<Integer, List<SavingPlanItem>> grouped = new LinkedHashMap<>();
		for (int layer : LAYERS) {
			grouped.put(layer, new ArrayList<>());
		}
		if (plans == null) {
			return grouped;
		}
		for (SavingPlanItem plan : plans) {
			grouped.computeIfAbsent(plan.layer(), key -> new ArrayList<>()).add(plan);
		}
		return grouped;
	}

	private Map<PlanKey, BigDecimal> roundByFraction(Map<PlanKey, BigDecimal> raw, BigDecimal total) {
		if (raw == null || raw.isEmpty()) {
			return Map.of();
		}
		BigDecimal totalRounded = total.setScale(0, RoundingMode.HALF_UP);
		Map<PlanKey, BigDecimal> rounded = new LinkedHashMap<>();
		Map<PlanKey, BigDecimal> fractions = new HashMap<>();
		BigDecimal sum = ZERO;

		for (Map.Entry<PlanKey, BigDecimal> entry : raw.entrySet()) {
			BigDecimal value = safeAmount(entry.getValue());
			BigDecimal floor = value.setScale(0, RoundingMode.FLOOR);
			BigDecimal fraction = value.subtract(floor);
			rounded.put(entry.getKey(), floor);
			fractions.put(entry.getKey(), fraction);
			sum = sum.add(floor);
		}

		int steps = totalRounded.subtract(sum).intValue();
		if (steps > 0 && !fractions.isEmpty()) {
			List<PlanKey> order = sortByFraction(fractions);
			int index = 0;
			while (steps > 0) {
				PlanKey key = order.get(index % order.size());
				rounded.put(key, rounded.get(key).add(BigDecimal.ONE));
				steps -= 1;
				index += 1;
			}
		}
		return rounded;
	}

	private Map<Integer, BigDecimal> roundLayerAmounts(Map<Integer, BigDecimal> raw, BigDecimal total) {
		if (raw == null || raw.isEmpty()) {
			return Map.of();
		}
		BigDecimal totalRounded = total.setScale(0, RoundingMode.HALF_UP);
		Map<Integer, BigDecimal> rounded = new LinkedHashMap<>();
		Map<Integer, BigDecimal> fractions = new HashMap<>();
		BigDecimal sum = ZERO;

		for (Map.Entry<Integer, BigDecimal> entry : raw.entrySet()) {
			BigDecimal value = safeAmount(entry.getValue());
			BigDecimal floor = value.setScale(0, RoundingMode.FLOOR);
			BigDecimal fraction = value.subtract(floor);
			rounded.put(entry.getKey(), floor);
			fractions.put(entry.getKey(), fraction);
			sum = sum.add(floor);
		}

		int steps = totalRounded.subtract(sum).intValue();
		if (steps > 0 && !fractions.isEmpty()) {
			List<Integer> order = sortByFractionLayers(fractions);
			int index = 0;
			while (steps > 0) {
				Integer key = order.get(index % order.size());
				rounded.put(key, rounded.get(key).add(BigDecimal.ONE));
				steps -= 1;
				index += 1;
			}
		}
		return rounded;
	}

	private Map<String, BigDecimal> roundInstrumentAmounts(Map<String, BigDecimal> raw, BigDecimal total) {
		if (raw == null || raw.isEmpty()) {
			return Map.of();
		}
		BigDecimal totalRounded = total.setScale(0, RoundingMode.HALF_UP);
		Map<String, BigDecimal> rounded = new LinkedHashMap<>();
		Map<String, BigDecimal> fractions = new HashMap<>();
		BigDecimal sum = ZERO;

		for (Map.Entry<String, BigDecimal> entry : raw.entrySet()) {
			BigDecimal value = safeAmount(entry.getValue());
			BigDecimal floor = value.setScale(0, RoundingMode.FLOOR);
			BigDecimal fraction = value.subtract(floor);
			rounded.put(entry.getKey(), floor);
			fractions.put(entry.getKey(), fraction);
			sum = sum.add(floor);
		}

		int steps = totalRounded.subtract(sum).intValue();
		if (steps > 0 && !fractions.isEmpty()) {
			List<String> order = sortByFractionIsins(fractions);
			int index = 0;
			while (steps > 0) {
				String key = order.get(index % order.size());
				rounded.put(key, rounded.get(key).add(BigDecimal.ONE));
				steps -= 1;
				index += 1;
			}
		}
		return rounded;
	}

	private BigDecimal resolveMinimumAllocation(BigDecimal minimumRebalancing, BigDecimal minimumInstrumentAmount) {
		if (minimumRebalancing == null && minimumInstrumentAmount == null) {
			return null;
		}
		if (minimumRebalancing == null) {
			return minimumInstrumentAmount;
		}
		if (minimumInstrumentAmount == null) {
			return minimumRebalancing;
		}
		return minimumRebalancing.max(minimumInstrumentAmount);
	}

	private List<PlanKey> sortByFraction(Map<PlanKey, BigDecimal> fractions) {
		List<PlanKey> keys = new ArrayList<>(fractions.keySet());
		keys.sort((a, b) -> {
			int cmp = fractions.getOrDefault(b, ZERO).compareTo(fractions.getOrDefault(a, ZERO));
			if (cmp != 0) {
				return cmp;
			}
			return a.isin().compareTo(b.isin());
		});
		return keys;
	}

	private List<Integer> sortByFractionLayers(Map<Integer, BigDecimal> fractions) {
		List<Integer> keys = new ArrayList<>(fractions.keySet());
		keys.sort((a, b) -> {
			int cmp = fractions.getOrDefault(b, ZERO).compareTo(fractions.getOrDefault(a, ZERO));
			if (cmp != 0) {
				return cmp;
			}
			return Integer.compare(a, b);
		});
		return keys;
	}

	private List<Integer> sortLayersByFraction(Map<Integer, BigDecimal> fractions, boolean ascending) {
		List<Integer> layers = new ArrayList<>(fractions.keySet());
		layers.sort((a, b) -> {
			int cmp = fractions.getOrDefault(a, ZERO).compareTo(fractions.getOrDefault(b, ZERO));
			if (cmp == 0) {
				cmp = Integer.compare(a, b);
			}
			return ascending ? cmp : -cmp;
		});
		return layers;
	}

	private List<String> sortByFractionIsins(Map<String, BigDecimal> fractions) {
		List<String> keys = new ArrayList<>(fractions.keySet());
		keys.sort((a, b) -> {
			int cmp = fractions.getOrDefault(b, ZERO).compareTo(fractions.getOrDefault(a, ZERO));
			if (cmp != 0) {
				return cmp;
			}
			return a.compareTo(b);
		});
		return keys;
	}

	private static RedistributionResult reducePositiveDeltas(Map<Integer, BigDecimal> deltas,
															 BigDecimal minimumRebalancing,
															 BigDecimal residual) {
		List<Integer> candidates = new ArrayList<>();
		for (int layer : LAYERS) {
			BigDecimal delta = deltas.getOrDefault(layer, ZERO);
			if (delta.signum() > 0) {
				candidates.add(layer);
			}
		}
		candidates.sort((a, b) -> {
			BigDecimal da = deltas.getOrDefault(a, ZERO).abs();
			BigDecimal db = deltas.getOrDefault(b, ZERO).abs();
			int cmp = db.compareTo(da);
			if (cmp != 0) {
				return cmp;
			}
			return Integer.compare(a, b);
		});
		BigDecimal remaining = residual;
		List<Integer> touched = new ArrayList<>();
		for (int layer : candidates) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal delta = deltas.getOrDefault(layer, ZERO);
			BigDecimal reduction = delta.min(remaining);
			BigDecimal updated = delta.subtract(reduction);
			if (updated.signum() > 0 && minimumRebalancing != null
					&& updated.abs().compareTo(minimumRebalancing) < 0) {
				reduction = delta;
				updated = ZERO;
			}
			deltas.put(layer, updated);
			remaining = remaining.subtract(reduction);
			touched.add(layer);
		}
		BigDecimal reduced = residual.subtract(remaining);
		String note = "Reduced increases by " + reduced.setScale(2, RoundingMode.HALF_UP) + " EUR in layers " + touched;
		return new RedistributionResult(reduced, note);
	}

	private static RedistributionResult reduceNegativeDeltas(Map<Integer, BigDecimal> deltas,
															 BigDecimal minimumRebalancing,
															 BigDecimal residual) {
		List<Integer> candidates = new ArrayList<>();
		for (int layer : LAYERS) {
			BigDecimal delta = deltas.getOrDefault(layer, ZERO);
			if (delta.signum() < 0) {
				candidates.add(layer);
			}
		}
		candidates.sort((a, b) -> {
			BigDecimal da = deltas.getOrDefault(a, ZERO).abs();
			BigDecimal db = deltas.getOrDefault(b, ZERO).abs();
			int cmp = db.compareTo(da);
			if (cmp != 0) {
				return cmp;
			}
			return Integer.compare(a, b);
		});
		BigDecimal remaining = residual;
		List<Integer> touched = new ArrayList<>();
		for (int layer : candidates) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal delta = deltas.getOrDefault(layer, ZERO);
			BigDecimal reduction = delta.abs().min(remaining);
			BigDecimal updated = delta.add(reduction);
			if (updated.signum() < 0 && minimumRebalancing != null
					&& updated.abs().compareTo(minimumRebalancing) < 0) {
				reduction = delta.abs();
				updated = ZERO;
			}
			deltas.put(layer, updated);
			remaining = remaining.subtract(reduction);
			touched.add(layer);
		}
		BigDecimal reduced = residual.subtract(remaining);
		String note = "Reduced decreases by " + reduced.setScale(2, RoundingMode.HALF_UP) + " EUR in layers " + touched;
		return new RedistributionResult(reduced, note);
	}

	private static RedistributionResult increasePositiveDeltas(Map<Integer, BigDecimal> deltas,
															   BigDecimal residual) {
		List<Integer> candidates = new ArrayList<>();
		for (int layer : LAYERS) {
			BigDecimal delta = deltas.getOrDefault(layer, ZERO);
			if (delta.signum() >= 0) {
				candidates.add(layer);
			}
		}
		if (candidates.isEmpty()) {
			return new RedistributionResult(ZERO, "No positive layers available to increase.");
		}
		candidates.sort((a, b) -> {
			BigDecimal da = deltas.getOrDefault(a, ZERO).abs();
			BigDecimal db = deltas.getOrDefault(b, ZERO).abs();
			int cmp = db.compareTo(da);
			if (cmp != 0) {
				return cmp;
			}
			return Integer.compare(a, b);
		});
		BigDecimal remaining = residual;
		List<Integer> touched = new ArrayList<>();
		for (int layer : candidates) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal delta = deltas.getOrDefault(layer, ZERO);
			deltas.put(layer, delta.add(remaining));
			remaining = ZERO;
			touched.add(layer);
		}
		BigDecimal increased = residual.subtract(remaining);
		String note = "Increased increases by " + increased.setScale(2, RoundingMode.HALF_UP) + " EUR in layers " + touched;
		return new RedistributionResult(increased, note);
	}

	private static RedistributionResult increaseNegativeDeltas(Map<Integer, BigDecimal> deltas,
															   BigDecimal residual) {
		List<Integer> candidates = new ArrayList<>();
		for (int layer : LAYERS) {
			BigDecimal delta = deltas.getOrDefault(layer, ZERO);
			if (delta.signum() < 0) {
				candidates.add(layer);
			}
		}
		if (candidates.isEmpty()) {
			return new RedistributionResult(ZERO, "No negative layers available to increase.");
		}
		candidates.sort((a, b) -> {
			BigDecimal da = deltas.getOrDefault(a, ZERO).abs();
			BigDecimal db = deltas.getOrDefault(b, ZERO).abs();
			int cmp = db.compareTo(da);
			if (cmp != 0) {
				return cmp;
			}
			return Integer.compare(a, b);
		});
		BigDecimal remaining = residual;
		List<Integer> touched = new ArrayList<>();
		for (int layer : candidates) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal delta = deltas.getOrDefault(layer, ZERO);
			deltas.put(layer, delta.subtract(remaining));
			remaining = ZERO;
			touched.add(layer);
		}
		BigDecimal increased = residual.subtract(remaining);
		String note = "Increased decreases by " + increased.setScale(2, RoundingMode.HALF_UP) + " EUR in layers " + touched;
		return new RedistributionResult(increased, note);
	}

	private Comparator<SavingPlanItem> planAmountComparator() {
		return (a, b) -> {
			int cmp = safeAmount(b.amount()).compareTo(safeAmount(a.amount()));
			if (cmp != 0) {
				return cmp;
			}
			return safeIsin(a.isin()).compareTo(safeIsin(b.isin()));
		};
	}

	private Comparator<PlanKey> planDeltaComparator(Map<PlanKey, SavingPlanItem> plans,
													Map<PlanKey, BigDecimal> deltas) {
		return (a, b) -> {
			BigDecimal da = deltas.getOrDefault(a, ZERO).abs();
			BigDecimal db = deltas.getOrDefault(b, ZERO).abs();
			int cmp = da.compareTo(db);
			if (cmp != 0) {
				return cmp;
			}
			SavingPlanItem planA = plans.get(a);
			SavingPlanItem planB = plans.get(b);
			int layerCmp = Integer.compare(planA == null ? 0 : planA.layer(), planB == null ? 0 : planB.layer());
			if (layerCmp != 0) {
				return layerCmp;
			}
			return safeIsin(a.isin()).compareTo(safeIsin(b.isin()));
		};
	}

	private String safeIsin(String isin) {
		return isin == null ? "" : isin;
	}

	private BigDecimal safeAmount(BigDecimal value) {
		return value == null ? ZERO : value;
	}

	private BigDecimal normalizeMinimum(Integer raw, int fallback) {
		if (raw == null || raw < 1) {
			return new BigDecimal(fallback);
		}
		return new BigDecimal(raw);
	}

	private static BigDecimal sumAmounts(Map<?, BigDecimal> values) {
		BigDecimal total = ZERO;
		if (values == null) {
			return total;
		}
		for (BigDecimal value : values.values()) {
			if (value != null) {
				total = total.add(value);
			}
		}
		return total;
	}

	private String determineType(BigDecimal oldAmount, BigDecimal newAmount) {
		if (newAmount.signum() == 0 && oldAmount.signum() > 0) {
			return "discard";
		}
		if (oldAmount.signum() == 0 && newAmount.signum() > 0) {
			return "create";
		}
		if (newAmount.compareTo(oldAmount) > 0) {
			return "increase";
		}
		if (newAmount.compareTo(oldAmount) < 0) {
			return "decrease";
		}
		return "increase";
	}

	private String buildRationale(String type) {
		return switch (type) {
			case "discard" -> "Discard to avoid sub-minimum saving plan size.";
			case "increase" -> "Increase to align with target layer allocation.";
			case "decrease" -> "Decrease to align with target layer allocation.";
			case "create" -> "Create to align with target layer allocation.";
			default -> "Adjust to align with target layer allocation.";
		};
	}

	public record AssessorEngineInput(String selectedProfile,
									  Map<Integer, BigDecimal> targetWeights,
									  BigDecimal acceptableVariancePct,
									  Integer minimumSavingPlanSize,
									  Integer minimumRebalancingAmount,
									  Integer minimumInstrumentAmount,
									  List<SavingPlanItem> savingPlans,
									  BigDecimal savingPlanAmountDelta,
									  BigDecimal oneTimeAmount,
									  Map<Integer, BigDecimal> holdingsByLayer,
									  boolean instrumentAllocationEnabled) {
	}

	public record AssessorEngineResult(String selectedProfile,
									   BigDecimal currentMonthlyTotal,
									   Map<Integer, BigDecimal> currentLayerDistribution,
									   Map<Integer, BigDecimal> targetLayerDistribution,
									   List<SavingPlanSuggestion> savingPlanSuggestions,
									   OneTimeAllocation oneTimeAllocation,
									   Diagnostics diagnostics) {
	}

	public record SavingPlanItem(String isin,
								 Long depotId,
								 BigDecimal amount,
								 int layer) {
	}

	public record SavingPlanSuggestion(String type,
									   String isin,
									   Long depotId,
									   BigDecimal oldAmount,
									   BigDecimal newAmount,
									   BigDecimal delta,
									   String rationale) {
	}

	public record OneTimeAllocation(Map<Integer, BigDecimal> layerBuckets,
									Map<String, BigDecimal> instrumentBuckets) {
	}

	public record Diagnostics(boolean withinTolerance,
							  int suppressedDeltasCount,
							  BigDecimal suppressedAmountTotal,
							  List<String> redistributionNotes) {
	}

	record LayerDeltaResult(Map<Integer, BigDecimal> adjustedDeltas,
							int suppressedCount,
							BigDecimal suppressedAmount,
							List<String> redistributionNotes) {
	}

	record RedistributionResult(BigDecimal amountReduced, String note) {
	}

	record MinimumSavingPlanAdjustment(Map<Integer, BigDecimal> adjustedAmounts,
									   List<Integer> zeroedLayers,
									   boolean rebalanced,
									   boolean increasedLayerOneToMinimum) {
	}

	record PlanKey(String isin, Long depotId) {
	}
}
