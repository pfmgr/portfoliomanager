package my.portfoliomanager.app.service;

import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import my.portfoliomanager.app.dto.InstrumentProposalDto;
import my.portfoliomanager.app.dto.InstrumentProposalGatingDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class InstrumentRebalanceService {
	private static final Logger logger = LoggerFactory.getLogger(InstrumentRebalanceService.class);
	private static final Set<String> COMPLETE_STATUSES = Set.of("COMPLETE", "APPROVED", "APPLIED");
	private static final String REASON_NO_CHANGE = "NO_CHANGE_WITHIN_TOLERANCE";
	private static final String REASON_MIN_DROPPED = "MIN_AMOUNT_DROPPED";
	private static final String REASON_MIN_REBALANCE = "MIN_REBALANCE_AMOUNT";
	private static final String REASON_WEIGHTED = "KB_WEIGHTED";
	private static final String REASON_EQUAL_WEIGHT = "EQUAL_WEIGHT";
	private static final String REASON_LAYER_BUDGET_ZERO = "LAYER_BUDGET_ZERO";
	private static final String WARNING_NO_INSTRUMENTS = "LAYER_NO_INSTRUMENTS";
	private static final String WARNING_ALL_BELOW_MIN = "LAYER_ALL_BELOW_MINIMUM";

	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
	private final ObjectMapper objectMapper;
	private final AppProperties properties;

	public InstrumentRebalanceService(NamedParameterJdbcTemplate namedParameterJdbcTemplate,
									  ObjectMapper objectMapper,
									  AppProperties properties) {
		this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	public InstrumentProposalResult buildInstrumentProposals(List<SavingPlanInstrument> instruments,
															 Map<Integer, BigDecimal> layerBudgets,
															 Integer minimumSavingPlanSize,
															 Integer minimumRebalancingAmount,
															 boolean withinTolerance) {
		List<SavingPlanInstrument> normalized = normalizeInstruments(instruments);
		Set<String> isins = normalized.stream()
				.map(SavingPlanInstrument::isin)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

		boolean kbEnabled = properties.kb() != null && properties.kb().enabled();
		Map<String, KbExtraction> extractions = kbEnabled ? loadLatestExtractions(isins) : Map.of();
		List<String> missingIsins = resolveMissingIsins(isins, extractions, kbEnabled);
		boolean kbComplete = kbEnabled && missingIsins.isEmpty();
		InstrumentProposalGatingDto gating = new InstrumentProposalGatingDto(kbEnabled, kbComplete, missingIsins);

		if (!kbComplete || normalized.isEmpty()) {
			return new InstrumentProposalResult(gating, List.of(), List.of(), List.of());
		}

		if (withinTolerance) {
			List<InstrumentProposalDto> proposals = new ArrayList<>();
			for (SavingPlanInstrument instrument : normalized) {
				BigDecimal amount = instrument.monthlyAmount();
				proposals.add(new InstrumentProposalDto(
						instrument.isin(),
						instrument.name(),
						toAmount(amount),
						toAmount(amount),
						toAmount(BigDecimal.ZERO),
						instrument.layer(),
						List.of(REASON_NO_CHANGE)
				));
			}
			proposals.sort(instrumentOrder());
			return new InstrumentProposalResult(gating, proposals, List.of(), List.of());
		}

		Map<Integer, List<SavingPlanInstrument>> byLayer = groupByLayer(normalized);
		List<InstrumentProposalDto> proposals = new ArrayList<>();
		List<InstrumentWarning> warnings = new ArrayList<>();
		List<LayerWeightingSummary> weightingSummaries = new ArrayList<>();

		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal budget = layerBudgets == null
					? BigDecimal.ZERO
					: layerBudgets.getOrDefault(layer, BigDecimal.ZERO);
			List<SavingPlanInstrument> layerInstruments = byLayer.getOrDefault(layer, List.of());

			if (budget == null || budget.signum() <= 0) {
				for (SavingPlanInstrument instrument : layerInstruments) {
					proposals.add(new InstrumentProposalDto(
							instrument.isin(),
							instrument.name(),
							toAmount(instrument.monthlyAmount()),
							toAmount(BigDecimal.ZERO),
							toAmount(BigDecimal.ZERO.subtract(instrument.monthlyAmount())),
							layer,
							List.of(REASON_LAYER_BUDGET_ZERO)
					));
				}
				continue;
			}

			if (layerInstruments.isEmpty()) {
				warnings.add(new InstrumentWarning(
						WARNING_NO_INSTRUMENTS,
						String.format("Layer %d has a budget of %s EUR but no active saving plan instruments.",
								layer, budget.stripTrailingZeros().toPlainString()),
						layer
				));
				continue;
			}

			LayerAllocation allocation = allocateLayerBudget(layer, budget, layerInstruments, minimumSavingPlanSize, minimumRebalancingAmount, extractions);
			if (allocation.weightingSummary() != null) {
				weightingSummaries.add(allocation.weightingSummary());
			}
			warnings.addAll(allocation.warnings());

			for (SavingPlanInstrument instrument : layerInstruments) {
				BigDecimal proposed = allocation.proposedAmounts().getOrDefault(instrument.isin(), BigDecimal.ZERO);
				BigDecimal delta = proposed.subtract(instrument.monthlyAmount());
				List<String> reasons = allocation.reasonCodes().getOrDefault(instrument.isin(), List.of());
				proposals.add(new InstrumentProposalDto(
						instrument.isin(),
						instrument.name(),
						toAmount(instrument.monthlyAmount()),
						toAmount(proposed),
						toAmount(delta),
						layer,
						reasons
				));
			}
		}

		proposals.sort(instrumentOrder());
		return new InstrumentProposalResult(gating, proposals, warnings, List.copyOf(weightingSummaries));
	}

	private List<SavingPlanInstrument> normalizeInstruments(List<SavingPlanInstrument> instruments) {
		if (instruments == null || instruments.isEmpty()) {
			return List.of();
		}
		List<SavingPlanInstrument> normalized = new ArrayList<>();
		for (SavingPlanInstrument instrument : instruments) {
			if (instrument == null) {
				continue;
			}
			String isin = normalizeIsin(instrument.isin());
			if (isin == null) {
				continue;
			}
			String name = instrument.name();
			BigDecimal amount = instrument.monthlyAmount() == null ? BigDecimal.ZERO : instrument.monthlyAmount();
			int layer = instrument.layer();
			LocalDate lastChanged = instrument.lastChanged();
			normalized.add(new SavingPlanInstrument(isin, name, amount, layer, lastChanged));
		}
		return normalized;
	}

	private Map<Integer, List<SavingPlanInstrument>> groupByLayer(List<SavingPlanInstrument> instruments) {
		Map<Integer, List<SavingPlanInstrument>> grouped = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			grouped.put(layer, new ArrayList<>());
		}
		for (SavingPlanInstrument instrument : instruments) {
			grouped.computeIfAbsent(instrument.layer(), key -> new ArrayList<>()).add(instrument);
		}
		return grouped;
	}

	private LayerAllocation allocateLayerBudget(int layer,
												BigDecimal budget,
												List<SavingPlanInstrument> instruments,
												Integer minimumSavingPlanSize,
												Integer minimumRebalancingAmount,
												Map<String, KbExtraction> extractions) {
		List<SavingPlanInstrument> remaining = new ArrayList<>(instruments);
		Set<String> dropped = new HashSet<>();
		Map<String, List<String>> reasonCodes = new HashMap<>();
		List<InstrumentWarning> warnings = new ArrayList<>();
		BigDecimal minimum = minimumSavingPlanSize == null ? null : new BigDecimal(minimumSavingPlanSize);

		Map<String, BigDecimal> rawAmounts = Map.of();
		Map<String, BigDecimal> proposedAmounts = new HashMap<>();
		WeightingResult weighting = WeightingResult.equal(remaining);
		LayerWeightingSummary weightingSummary = null;

		while (!remaining.isEmpty()) {
			weighting = computeWeights(remaining, extractions);
			rawAmounts = allocateRawAmounts(budget, remaining, weighting.weights());

			if (minimum != null && minimum.signum() > 0 && hasBelowMinimum(rawAmounts, remaining, minimum)) {
				SavingPlanInstrument discard = selectDiscardCandidate(remaining, weighting.weights());
				if (discard == null) {
					break;
				}
				markDiscard(discard, dropped, reasonCodes);
				remaining.remove(discard);
				continue;
			}

			Map<String, BigDecimal> rounded = roundLayerAmounts(rawAmounts, budget);
			proposedAmounts = new HashMap<>(rounded);

			if (minimumRebalancingAmount != null && minimumRebalancingAmount > 0) {
				Map<String, List<String>> workingReasons = copyReasonCodes(reasonCodes);
				MinimumRebalancingOutcome minOutcome = applyMinimumRebalancingAmount(remaining, proposedAmounts, minimumRebalancingAmount, workingReasons);
				proposedAmounts = new HashMap<>(minOutcome.adjustedAmounts());
				if (!matchesLayerBudget(proposedAmounts, remaining, budget)) {
					SavingPlanInstrument discard = selectDiscardCandidate(remaining, weighting.weights());
					if (discard == null) {
						break;
					}
					markDiscard(discard, dropped, reasonCodes);
					remaining.remove(discard);
					continue;
				}
				reasonCodes = workingReasons;
			}

			String weightCode = weighting.weighted() ? REASON_WEIGHTED : REASON_EQUAL_WEIGHT;
			for (SavingPlanInstrument instrument : remaining) {
				addReasonCode(reasonCodes, instrument.isin(), weightCode);
			}
			weightingSummary = new LayerWeightingSummary(
					layer,
					remaining.size(),
					weighting.weighted(),
					weighting.costUsed(),
					weighting.benchmarkUsed(),
					weighting.regionsUsed(),
					weighting.holdingsUsed(),
					Map.copyOf(weighting.weights())
			);
			break;
		}

		if (remaining.isEmpty()) {
			proposedAmounts = new HashMap<>();
			warnings.add(new InstrumentWarning(
					WARNING_ALL_BELOW_MIN,
					String.format("Layer %d budget could not be allocated because all instruments fall below the minimum saving plan size.",
							layer),
					layer
			));
		}

		for (SavingPlanInstrument instrument : instruments) {
			if (dropped.contains(instrument.isin())) {
				proposedAmounts.put(instrument.isin(), BigDecimal.ZERO);
			}
		}

		return new LayerAllocation(Map.copyOf(proposedAmounts), toReasonMap(reasonCodes), warnings, weightingSummary);
	}

	private boolean hasBelowMinimum(Map<String, BigDecimal> rawAmounts,
									List<SavingPlanInstrument> instruments,
									BigDecimal minimum) {
		if (rawAmounts == null || instruments == null || instruments.isEmpty() || minimum == null || minimum.signum() <= 0) {
			return false;
		}
		for (SavingPlanInstrument instrument : instruments) {
			BigDecimal proposed = rawAmounts.getOrDefault(instrument.isin(), BigDecimal.ZERO);
			if (proposed.signum() > 0 && proposed.compareTo(minimum) < 0) {
				return true;
			}
		}
		return false;
	}

	private boolean matchesLayerBudget(Map<String, BigDecimal> proposedAmounts,
									   List<SavingPlanInstrument> instruments,
									   BigDecimal budget) {
		BigDecimal target = budget == null ? BigDecimal.ZERO : budget.setScale(0, RoundingMode.HALF_UP);
		BigDecimal total = sumInstrumentAmounts(proposedAmounts, instruments);
		return total.compareTo(target) == 0;
	}

	private SavingPlanInstrument selectDiscardCandidate(List<SavingPlanInstrument> instruments,
														Map<String, BigDecimal> weights) {
		if (instruments == null || instruments.isEmpty()) {
			return null;
		}
		return instruments.stream()
				.min((left, right) -> compareDiscardCandidates(left, right, weights))
				.orElse(null);
	}

	private int compareDiscardCandidates(SavingPlanInstrument left,
										 SavingPlanInstrument right,
										 Map<String, BigDecimal> weights) {
		BigDecimal leftWeight = weights == null ? BigDecimal.ZERO : weights.getOrDefault(left.isin(), BigDecimal.ZERO);
		BigDecimal rightWeight = weights == null ? BigDecimal.ZERO : weights.getOrDefault(right.isin(), BigDecimal.ZERO);
		int cmp = leftWeight.compareTo(rightWeight);
		if (cmp != 0) {
			return cmp;
		}
		BigDecimal leftAmount = left.monthlyAmount() == null ? BigDecimal.ZERO : left.monthlyAmount();
		BigDecimal rightAmount = right.monthlyAmount() == null ? BigDecimal.ZERO : right.monthlyAmount();
		cmp = leftAmount.compareTo(rightAmount);
		if (cmp != 0) {
			return cmp;
		}
		LocalDate leftChanged = left.lastChanged();
		LocalDate rightChanged = right.lastChanged();
		if (leftChanged != null || rightChanged != null) {
			if (leftChanged == null) {
				return -1;
			}
			if (rightChanged == null) {
				return 1;
			}
			cmp = leftChanged.compareTo(rightChanged);
			if (cmp != 0) {
				return cmp;
			}
		}
		String leftIsin = left.isin() == null ? "" : left.isin();
		String rightIsin = right.isin() == null ? "" : right.isin();
		return leftIsin.compareTo(rightIsin);
	}

	private void markDiscard(SavingPlanInstrument instrument,
							 Set<String> dropped,
							 Map<String, List<String>> reasonCodes) {
		if (instrument == null || instrument.isin() == null) {
			return;
		}
		dropped.add(instrument.isin());
		addReasonCode(reasonCodes, instrument.isin(), REASON_MIN_DROPPED);
	}

	private void addReasonCode(Map<String, List<String>> reasonCodes, String isin, String code) {
		if (reasonCodes == null || isin == null || code == null) {
			return;
		}
		List<String> existing = reasonCodes.get(isin);
		List<String> updated = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
		if (!updated.contains(code)) {
			updated.add(code);
		}
		reasonCodes.put(isin, updated);
	}

	private Map<String, List<String>> copyReasonCodes(Map<String, List<String>> source) {
		Map<String, List<String>> copy = new HashMap<>();
		if (source == null || source.isEmpty()) {
			return copy;
		}
		for (Map.Entry<String, List<String>> entry : source.entrySet()) {
			List<String> codes = entry.getValue();
			copy.put(entry.getKey(), codes == null ? new ArrayList<>() : new ArrayList<>(codes));
		}
		return copy;
	}

	private Map<String, List<String>> toReasonMap(Map<String, List<String>> raw) {
		Map<String, List<String>> result = new HashMap<>();
		for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
			List<String> codes = entry.getValue();
			result.put(entry.getKey(), codes == null ? List.of() : List.copyOf(codes));
		}
		return result;
	}

	private Map<String, BigDecimal> allocateRawAmounts(BigDecimal budget,
													   List<SavingPlanInstrument> instruments,
													   Map<String, BigDecimal> weights) {
		Map<String, BigDecimal> raw = new HashMap<>();
		if (budget == null || budget.signum() <= 0) {
			for (SavingPlanInstrument instrument : instruments) {
				raw.put(instrument.isin(), BigDecimal.ZERO);
			}
			return raw;
		}
		for (SavingPlanInstrument instrument : instruments) {
			BigDecimal weight = weights.getOrDefault(instrument.isin(), BigDecimal.ZERO);
			raw.put(instrument.isin(), budget.multiply(weight));
		}
		return raw;
	}

	private WeightingResult computeWeights(List<SavingPlanInstrument> instruments,
										   Map<String, KbExtraction> extractions) {
		if (instruments == null || instruments.isEmpty()) {
			return WeightingResult.equal(instruments);
		}
		if (instruments.size() == 1) {
			SavingPlanInstrument instrument = instruments.get(0);
			return WeightingResult.single(instrument.isin());
		}

		boolean useCost = true;
		boolean useBenchmark = true;
		boolean useRegions = true;
		boolean useHoldings = true;
		Map<String, String> benchmarks = new HashMap<>();
		Map<String, BigDecimal> ters = new HashMap<>();
		Map<String, Set<String>> regionNames = new HashMap<>();
		Map<String, Set<String>> holdingNames = new HashMap<>();

		for (SavingPlanInstrument instrument : instruments) {
			KbExtraction extraction = extractions.get(instrument.isin());
			InstrumentDossierExtractionPayload payload = extraction == null ? null : extraction.payload();
			BigDecimal ter = null;
			String benchmark = null;
			Set<String> regions = null;
			Set<String> holdings = null;
			if (payload != null) {
				if (payload.etf() != null) {
					ter = payload.etf().ongoingChargesPct();
					benchmark = payload.etf().benchmarkIndex();
				}
				regions = normalizeRegionNames(payload.regions());
				holdings = normalizeHoldingNames(payload.topHoldings());
			}
			if (ter == null) {
				useCost = false;
			} else {
				ters.put(instrument.isin(), ter);
			}
			if (benchmark == null || benchmark.isBlank()) {
				useBenchmark = false;
			} else {
				benchmarks.put(instrument.isin(), benchmark.trim());
			}
			if (regions == null || regions.isEmpty()) {
				useRegions = false;
			} else {
				regionNames.put(instrument.isin(), regions);
			}
			if (holdings == null || holdings.isEmpty()) {
				useHoldings = false;
			} else {
				holdingNames.put(instrument.isin(), holdings);
			}
		}

		boolean useRedundancy = useBenchmark || useRegions || useHoldings;
		if (!useCost && !useRedundancy) {
			return WeightingResult.equal(instruments);
		}

		Map<String, Integer> benchmarkCounts = new HashMap<>();
		if (useBenchmark) {
			for (String benchmark : benchmarks.values()) {
				benchmarkCounts.merge(benchmark, 1, Integer::sum);
			}
		}

		Map<String, BigDecimal> scores = new HashMap<>();
		BigDecimal total = BigDecimal.ZERO;
		for (SavingPlanInstrument instrument : instruments) {
			BigDecimal score = BigDecimal.ONE;
			if (useCost) {
				BigDecimal ter = ters.get(instrument.isin());
				if (ter != null) {
					BigDecimal divisor = BigDecimal.ONE.add(ter.max(BigDecimal.ZERO));
					score = score.divide(divisor, 8, RoundingMode.HALF_UP);
				}
			}
			if (useRedundancy) {
				double redundancy = computeRedundancy(instrument.isin(), instruments.size(),
						benchmarks, benchmarkCounts, regionNames, holdingNames, useBenchmark, useRegions, useHoldings);
				BigDecimal uniqueness = BigDecimal.ONE.subtract(BigDecimal.valueOf(redundancy));
				if (uniqueness.compareTo(BigDecimal.ZERO) < 0) {
					uniqueness = BigDecimal.ZERO;
				}
				score = score.multiply(uniqueness);
			}
			scores.put(instrument.isin(), score);
			total = total.add(score);
		}

		if (total.signum() <= 0) {
			return WeightingResult.equal(instruments);
		}

		Map<String, BigDecimal> weights = new HashMap<>();
		for (SavingPlanInstrument instrument : instruments) {
			BigDecimal score = scores.getOrDefault(instrument.isin(), BigDecimal.ZERO);
			weights.put(instrument.isin(), score.divide(total, 8, RoundingMode.HALF_UP));
		}
		return new WeightingResult(weights, useCost || useRedundancy, useCost, useBenchmark, useRegions, useHoldings);
	}

	private Map<String, BigDecimal> roundLayerAmounts(Map<String, BigDecimal> rawAmounts, BigDecimal budget) {
		if (rawAmounts == null || rawAmounts.isEmpty()) {
			return Map.of();
		}
		BigDecimal total = budget == null ? BigDecimal.ZERO : budget.setScale(0, RoundingMode.HALF_UP);
		Map<String, BigDecimal> rounded = new HashMap<>();
		Map<String, BigDecimal> fractions = new HashMap<>();
		BigDecimal sumFloor = BigDecimal.ZERO;

		for (Map.Entry<String, BigDecimal> entry : rawAmounts.entrySet()) {
			BigDecimal raw = entry.getValue() == null ? BigDecimal.ZERO : entry.getValue();
			if (raw.signum() < 0) {
				raw = BigDecimal.ZERO;
			}
			BigDecimal floor = raw.setScale(0, RoundingMode.FLOOR);
			rounded.put(entry.getKey(), floor);
			fractions.put(entry.getKey(), raw.subtract(floor));
			sumFloor = sumFloor.add(floor);
		}

		int remainder = total.subtract(sumFloor).intValue();
		if (remainder > 0) {
			List<String> order = sortByFraction(fractions);
			int index = 0;
			while (remainder > 0 && !order.isEmpty()) {
				String isin = order.get(index % order.size());
				rounded.put(isin, rounded.getOrDefault(isin, BigDecimal.ZERO).add(BigDecimal.ONE));
				remainder -= 1;
				index++;
			}
		}

		return rounded;
	}

	private MinimumRebalancingOutcome applyMinimumRebalancingAmount(List<SavingPlanInstrument> instruments,
																	Map<String, BigDecimal> proposedAmounts,
																	Integer minimumRebalancingAmount,
																	Map<String, List<String>> reasonCodes) {
		if (instruments == null || instruments.isEmpty() || proposedAmounts == null
				|| minimumRebalancingAmount == null || minimumRebalancingAmount < 1) {
			return new MinimumRebalancingOutcome(proposedAmounts == null ? Map.of() : Map.copyOf(proposedAmounts), List.of());
		}
		BigDecimal minimum = new BigDecimal(minimumRebalancingAmount);
		Map<String, BigDecimal> adjusted = new HashMap<>();
		Map<String, BigDecimal> current = new HashMap<>();
		for (SavingPlanInstrument instrument : instruments) {
			if (instrument == null) {
				continue;
			}
			String isin = instrument.isin();
			BigDecimal currentAmount = instrument.monthlyAmount() == null ? BigDecimal.ZERO : instrument.monthlyAmount();
			current.put(isin, currentAmount);
			adjusted.put(isin, proposedAmounts.getOrDefault(isin, BigDecimal.ZERO));
		}
		BigDecimal targetTotal = sumInstrumentAmounts(adjusted, instruments);
		Set<String> skipped = new LinkedHashSet<>();
		for (SavingPlanInstrument instrument : instruments) {
			if (instrument == null) {
				continue;
			}
			String isin = instrument.isin();
			BigDecimal delta = adjusted.getOrDefault(isin, BigDecimal.ZERO)
					.subtract(current.getOrDefault(isin, BigDecimal.ZERO));
			if (delta.signum() != 0 && delta.abs().compareTo(minimum) < 0) {
				adjusted.put(isin, current.getOrDefault(isin, BigDecimal.ZERO));
				skipped.add(isin);
			}
		}
		BigDecimal residual = sumInstrumentAmounts(adjusted, instruments).subtract(targetTotal);
		int guard = 0;
		while (residual.signum() != 0 && guard < 12) {
			guard += 1;
			BigDecimal reduced = residual.signum() > 0
					? reducePositiveResidual(adjusted, current, minimum, residual, skipped)
					: reduceNegativeResidual(adjusted, current, minimum, residual.abs(), skipped);
			if (reduced.signum() == 0) {
				break;
			}
			residual = sumInstrumentAmounts(adjusted, instruments).subtract(targetTotal);
		}
		if (!skipped.isEmpty()) {
			for (String isin : skipped) {
				List<String> codes = new ArrayList<>(reasonCodes.getOrDefault(isin, List.of()));
				codes.remove(REASON_MIN_DROPPED);
				codes.remove(REASON_LAYER_BUDGET_ZERO);
				if (!codes.contains(REASON_MIN_REBALANCE)) {
					codes.add(REASON_MIN_REBALANCE);
				}
				reasonCodes.put(isin, List.copyOf(codes));
			}
		}
		return new MinimumRebalancingOutcome(Map.copyOf(adjusted), List.copyOf(skipped));
	}

	private BigDecimal reducePositiveResidual(Map<String, BigDecimal> adjusted,
											  Map<String, BigDecimal> current,
											  BigDecimal minimum,
											  BigDecimal residual,
											  Set<String> skipped) {
		List<String> candidates = new ArrayList<>();
		for (Map.Entry<String, BigDecimal> entry : adjusted.entrySet()) {
			String isin = entry.getKey();
			BigDecimal delta = entry.getValue().subtract(current.getOrDefault(isin, BigDecimal.ZERO));
			if (delta.signum() > 0) {
				candidates.add(isin);
			}
		}
		candidates.sort((a, b) -> {
			BigDecimal da = adjusted.getOrDefault(a, BigDecimal.ZERO).subtract(current.getOrDefault(a, BigDecimal.ZERO)).abs();
			BigDecimal db = adjusted.getOrDefault(b, BigDecimal.ZERO).subtract(current.getOrDefault(b, BigDecimal.ZERO)).abs();
			int cmp = da.compareTo(db);
			return cmp != 0 ? cmp : a.compareTo(b);
		});
		BigDecimal remaining = residual;
		for (String isin : candidates) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal delta = adjusted.getOrDefault(isin, BigDecimal.ZERO).subtract(current.getOrDefault(isin, BigDecimal.ZERO));
			BigDecimal reduction = delta.min(remaining);
			BigDecimal updatedDelta = delta.subtract(reduction);
			if (updatedDelta.signum() > 0 && updatedDelta.abs().compareTo(minimum) < 0) {
				reduction = delta;
				updatedDelta = BigDecimal.ZERO;
				skipped.add(isin);
			}
			adjusted.put(isin, current.getOrDefault(isin, BigDecimal.ZERO).add(updatedDelta));
			remaining = remaining.subtract(reduction);
		}
		return residual.subtract(remaining);
	}

	private BigDecimal reduceNegativeResidual(Map<String, BigDecimal> adjusted,
											  Map<String, BigDecimal> current,
											  BigDecimal minimum,
											  BigDecimal residual,
											  Set<String> skipped) {
		List<String> candidates = new ArrayList<>();
		for (Map.Entry<String, BigDecimal> entry : adjusted.entrySet()) {
			String isin = entry.getKey();
			BigDecimal delta = entry.getValue().subtract(current.getOrDefault(isin, BigDecimal.ZERO));
			if (delta.signum() < 0) {
				candidates.add(isin);
			}
		}
		candidates.sort((a, b) -> {
			BigDecimal da = adjusted.getOrDefault(a, BigDecimal.ZERO).subtract(current.getOrDefault(a, BigDecimal.ZERO)).abs();
			BigDecimal db = adjusted.getOrDefault(b, BigDecimal.ZERO).subtract(current.getOrDefault(b, BigDecimal.ZERO)).abs();
			int cmp = da.compareTo(db);
			return cmp != 0 ? cmp : a.compareTo(b);
		});
		BigDecimal remaining = residual;
		for (String isin : candidates) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal delta = adjusted.getOrDefault(isin, BigDecimal.ZERO).subtract(current.getOrDefault(isin, BigDecimal.ZERO));
			BigDecimal reduction = delta.abs().min(remaining);
			BigDecimal updatedDelta = delta.add(reduction);
			if (updatedDelta.signum() < 0 && updatedDelta.abs().compareTo(minimum) < 0) {
				reduction = delta.abs();
				updatedDelta = BigDecimal.ZERO;
				skipped.add(isin);
			}
			adjusted.put(isin, current.getOrDefault(isin, BigDecimal.ZERO).add(updatedDelta));
			remaining = remaining.subtract(reduction);
		}
		return residual.subtract(remaining);
	}

	private BigDecimal sumInstrumentAmounts(Map<String, BigDecimal> amounts, List<SavingPlanInstrument> instruments) {
		if (amounts == null || amounts.isEmpty() || instruments == null || instruments.isEmpty()) {
			return BigDecimal.ZERO;
		}
		BigDecimal total = BigDecimal.ZERO;
		for (SavingPlanInstrument instrument : instruments) {
			if (instrument == null) {
				continue;
			}
			total = total.add(amounts.getOrDefault(instrument.isin(), BigDecimal.ZERO));
		}
		return total;
	}

	private List<String> sortByFraction(Map<String, BigDecimal> fractions) {
		List<String> order = new ArrayList<>(fractions.keySet());
		order.sort((a, b) -> {
			int cmp = fractions.getOrDefault(b, BigDecimal.ZERO)
					.compareTo(fractions.getOrDefault(a, BigDecimal.ZERO));
			if (cmp != 0) {
				return cmp;
			}
			return a.compareTo(b);
		});
		return order;
	}

	private Set<String> normalizeRegionNames(List<InstrumentDossierExtractionPayload.RegionExposurePayload> regions) {
		if (regions == null || regions.isEmpty()) {
			return null;
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (InstrumentDossierExtractionPayload.RegionExposurePayload region : regions) {
			String name = region == null ? null : region.name();
			String cleaned = normalizeLabel(name);
			if (cleaned != null) {
				normalized.add(cleaned);
			}
		}
		return normalized.isEmpty() ? null : normalized;
	}

	private Set<String> normalizeHoldingNames(List<InstrumentDossierExtractionPayload.HoldingPayload> holdings) {
		if (holdings == null || holdings.isEmpty()) {
			return null;
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (InstrumentDossierExtractionPayload.HoldingPayload holding : holdings) {
			String name = holding == null ? null : holding.name();
			String cleaned = normalizeLabel(name);
			if (cleaned != null) {
				normalized.add(cleaned);
			}
		}
		return normalized.isEmpty() ? null : normalized;
	}

	private String normalizeLabel(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		return raw.trim().toLowerCase(Locale.ROOT);
	}

	private double computeRedundancy(String isin,
									 int instrumentCount,
									 Map<String, String> benchmarks,
									 Map<String, Integer> benchmarkCounts,
									 Map<String, Set<String>> regionNames,
									 Map<String, Set<String>> holdingNames,
									 boolean useBenchmark,
									 boolean useRegions,
									 boolean useHoldings) {
		if (instrumentCount <= 1) {
			return 0.0d;
		}
		double sum = 0.0d;
		int components = 0;
		if (useBenchmark) {
			components += 1;
			String benchmark = benchmarks.get(isin);
			int count = benchmark == null ? 1 : benchmarkCounts.getOrDefault(benchmark, 1);
			double overlap = (double) (count - 1) / (double) (instrumentCount - 1);
			sum += overlap;
		}
		if (useRegions) {
			components += 1;
			sum += averageOverlap(isin, regionNames, instrumentCount);
		}
		if (useHoldings) {
			components += 1;
			sum += averageOverlap(isin, holdingNames, instrumentCount);
		}
		if (components == 0) {
			return 0.0d;
		}
		return sum / components;
	}

	private double averageOverlap(String isin, Map<String, Set<String>> entries, int instrumentCount) {
		Set<String> base = entries.get(isin);
		if (base == null || base.isEmpty()) {
			return 0.0d;
		}
		double sum = 0.0d;
		for (Map.Entry<String, Set<String>> entry : entries.entrySet()) {
			if (entry.getKey().equals(isin)) {
				continue;
			}
			Set<String> other = entry.getValue();
			if (other == null || other.isEmpty()) {
				continue;
			}
			int maxSize = Math.max(base.size(), other.size());
			if (maxSize == 0) {
				continue;
			}
			int common = 0;
			for (String name : base) {
				if (other.contains(name)) {
					common += 1;
				}
			}
			sum += (double) common / (double) maxSize;
		}
		return sum / (double) (instrumentCount - 1);
	}

	private Map<String, KbExtraction> loadLatestExtractions(Set<String> isins) {
		if (isins == null || isins.isEmpty()) {
			return Map.of();
		}
		String sql = """
				SELECT isin, status, extracted_json
				FROM knowledge_base_extractions
				WHERE isin IN (:isins)
				""";
		MapSqlParameterSource params = new MapSqlParameterSource("isins", isins);
		Map<String, KbExtraction> result = new HashMap<>();
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			String isin = normalizeIsin(rs.getString("isin"));
			String status = rs.getString("status");
			String json = rs.getString("extracted_json");
			InstrumentDossierExtractionPayload payload = parsePayload(isin, json);
			if (isin != null) {
				result.put(isin, new KbExtraction(isin, status, payload));
			}
		});
		return result;
	}

	private InstrumentDossierExtractionPayload parsePayload(String isin, String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readValue(json, InstrumentDossierExtractionPayload.class);
		} catch (Exception ex) {
			logger.debug("Failed to parse KB extraction payload for {}: {}", isin, ex.getMessage());
			return null;
		}
	}

	private List<String> resolveMissingIsins(Set<String> isins, Map<String, KbExtraction> extractions, boolean kbEnabled) {
		if (isins == null || isins.isEmpty()) {
			return List.of();
		}
		List<String> missing = new ArrayList<>();
		for (String isin : isins) {
			if (!kbEnabled) {
				missing.add(isin);
				continue;
			}
			KbExtraction extraction = extractions.get(isin);
			if (extraction == null || !isComplete(extraction.status())) {
				missing.add(isin);
			}
		}
		missing.sort(String::compareTo);
		return missing;
	}

	private boolean isComplete(String status) {
		if (status == null) {
			return false;
		}
		return COMPLETE_STATUSES.contains(status.toUpperCase(Locale.ROOT));
	}

	private String normalizeIsin(String isin) {
		if (isin == null || isin.isBlank()) {
			return null;
		}
		return isin.trim().toUpperCase(Locale.ROOT);
	}

	private Double toAmount(BigDecimal value) {
		if (value == null) {
			return null;
		}
		return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
	}

	private Comparator<InstrumentProposalDto> instrumentOrder() {
		return Comparator.comparing(InstrumentProposalDto::getLayer, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(InstrumentProposalDto::getIsin, Comparator.nullsLast(String::compareTo));
	}

	public record InstrumentProposalResult(InstrumentProposalGatingDto gating,
										   List<InstrumentProposalDto> proposals,
										   List<InstrumentWarning> warnings,
										   List<LayerWeightingSummary> weightingSummaries) {
	}

	public record LayerWeightingSummary(int layer,
										int instrumentCount,
										boolean weighted,
										boolean costUsed,
										boolean benchmarkUsed,
										boolean regionsUsed,
										boolean holdingsUsed,
										Map<String, BigDecimal> weights) {
	}

	private record KbExtraction(String isin, String status, InstrumentDossierExtractionPayload payload) {
	}

	private record LayerAllocation(Map<String, BigDecimal> proposedAmounts,
								   Map<String, List<String>> reasonCodes,
								   List<InstrumentWarning> warnings,
								   LayerWeightingSummary weightingSummary) {
	}

	private record MinimumRebalancingOutcome(Map<String, BigDecimal> adjustedAmounts,
											 List<String> skippedIsins) {
	}

	public record InstrumentWarning(String code, String message, Integer layer) {
	}

	private record WeightingResult(Map<String, BigDecimal> weights,
								   boolean weighted,
								   boolean costUsed,
								   boolean benchmarkUsed,
								   boolean regionsUsed,
								   boolean holdingsUsed) {
		private static WeightingResult equal(List<SavingPlanInstrument> instruments) {
			Map<String, BigDecimal> weights = new HashMap<>();
			if (instruments != null && !instruments.isEmpty()) {
				BigDecimal share = BigDecimal.ONE.divide(BigDecimal.valueOf(instruments.size()), 8, RoundingMode.HALF_UP);
				for (SavingPlanInstrument instrument : instruments) {
					weights.put(instrument.isin(), share);
				}
			}
			return new WeightingResult(weights, false, false, false, false, false);
		}

		private static WeightingResult single(String isin) {
			Map<String, BigDecimal> weights = new HashMap<>();
			weights.put(isin, BigDecimal.ONE);
			return new WeightingResult(weights, false, false, false, false, false);
		}
	}
}
