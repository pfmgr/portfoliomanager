package my.portfoliomanager.app.service;

import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import my.portfoliomanager.app.dto.InstrumentProposalDto;
import my.portfoliomanager.app.dto.InstrumentProposalGatingDto;
import my.portfoliomanager.app.service.AssessorInstrumentAssessmentService;
import my.portfoliomanager.app.model.LayerTargetRiskThresholds;
import my.portfoliomanager.app.service.util.RiskThresholdsUtil;
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
	private static final String REASON_SCORE_WEIGHTED = "SCORE_WEIGHTED";
	private static final String REASON_LAYER_BUDGET_ZERO = "LAYER_BUDGET_ZERO";
	private static final String WARNING_NO_INSTRUMENTS = "LAYER_NO_INSTRUMENTS";
	private static final double DEFAULT_PB_TARGET = 2.0;
	private static final double DATA_QUALITY_MISSING_WEIGHT = 0.01;
	private static final double DATA_QUALITY_WARNING_WEIGHT = 0.05;
	private static final double MAX_DATA_QUALITY_PENALTY = 0.25;
	private static final double MIN_SCORE_WEIGHT_FACTOR = 0.2;
	private static final double ETF_HOLDINGS_YIELD_WEIGHT = 0.65;
	private static final double ETF_CURRENT_YIELD_WEIGHT = 0.20;
	private static final double ETF_PB_WEIGHT = 0.10;
	private static final double ETF_DIVIDEND_WEIGHT = 0.05;
	private static final double STOCK_LONGTERM_YIELD_WEIGHT = 0.50;
	private static final double STOCK_CURRENT_YIELD_WEIGHT = 0.20;
	private static final double STOCK_EV_WEIGHT = 0.20;
	private static final double STOCK_DIVIDEND_WEIGHT = 0.05;
	private static final double STOCK_PB_WEIGHT = 0.05;
	private static final double REIT_LONGTERM_YIELD_WEIGHT = 0.40;
	private static final double REIT_CURRENT_YIELD_WEIGHT = 0.20;
	private static final double REIT_EV_WEIGHT = 0.20;
	private static final double REIT_PB_WEIGHT = 0.10;
	private static final double REIT_PROFITABILITY_WEIGHT = 0.10;

	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
	private final ObjectMapper objectMapper;
	private final AppProperties properties;
	private final SavingPlanDeltaAllocator savingPlanDeltaAllocator;
	private final AssessorInstrumentAssessmentService assessmentService;

	public InstrumentRebalanceService(NamedParameterJdbcTemplate namedParameterJdbcTemplate,
									  ObjectMapper objectMapper,
									  AppProperties properties,
									  SavingPlanDeltaAllocator savingPlanDeltaAllocator,
									  AssessorInstrumentAssessmentService assessmentService) {
		this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
		this.objectMapper = objectMapper;
		this.properties = properties;
		this.savingPlanDeltaAllocator = savingPlanDeltaAllocator;
		this.assessmentService = assessmentService;
	}

	public InstrumentProposalResult buildInstrumentProposals(List<SavingPlanInstrument> instruments,
										 Map<Integer, BigDecimal> layerBudgets,
										 Integer minimumSavingPlanSize,
										 Integer minimumRebalancingAmount,
										 boolean withinTolerance,
										 LayerTargetRiskThresholds riskThresholds,
										 Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer) {
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

		LayerTargetRiskThresholds thresholds = RiskThresholdsUtil.normalize(riskThresholds);
		Map<Integer, LayerTargetRiskThresholds> normalizedByLayer =
				RiskThresholdsUtil.normalizeByLayer(riskThresholdsByLayer, thresholds);
		Map<String, Integer> assessmentScores = assessmentService == null
				? Map.of()
				: assessmentService.assessScores(isins, thresholds, normalizedByLayer);

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

			LayerTargetRiskThresholds layerThresholds =
					RiskThresholdsUtil.resolveForLayer(normalizedByLayer, thresholds, layer);
			double scoreCutoff = layerThresholds.getHighMin();
			LayerAllocation allocation = allocateLayerBudget(layer, budget, layerInstruments, minimumSavingPlanSize,
					minimumRebalancingAmount, extractions, assessmentScores, scoreCutoff);
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
								Map<String, KbExtraction> extractions,
								Map<String, Integer> assessmentScores,
								double scoreCutoff) {
		Map<String, List<String>> reasonCodes = new HashMap<>();
		List<InstrumentWarning> warnings = new ArrayList<>();
		BigDecimal minSavingPlan = minimumSavingPlanSize == null ? null : new BigDecimal(minimumSavingPlanSize);
		BigDecimal minRebalance = minimumRebalancingAmount == null ? null : new BigDecimal(minimumRebalancingAmount);

		WeightingResult weighting = computeWeights(layer, instruments, extractions, assessmentScores, scoreCutoff);
		List<SavingPlanDeltaAllocator.PlanInput> inputs = new ArrayList<>();
		Map<AssessorEngine.PlanKey, String> keyToIsin = new HashMap<>();
		Map<String, BigDecimal> currentByIsin = new HashMap<>();
		for (SavingPlanInstrument instrument : instruments) {
			if (instrument == null) {
				continue;
			}
			String isin = instrument.isin();
			BigDecimal weight = weighting.weights().getOrDefault(isin, BigDecimal.ONE);
			AssessorEngine.PlanKey key = new AssessorEngine.PlanKey(isin, null);
			inputs.add(new SavingPlanDeltaAllocator.PlanInput(key, instrument.monthlyAmount(), weight));
			keyToIsin.put(key, isin);
			currentByIsin.put(isin, instrument.monthlyAmount() == null ? BigDecimal.ZERO : instrument.monthlyAmount());
		}

		SavingPlanDeltaAllocator.Allocation allocation = savingPlanDeltaAllocator.allocateToTarget(
				inputs,
				budget,
				minRebalance,
				minSavingPlan
		);

		String weightCode = weighting.weighted() ? REASON_WEIGHTED : REASON_EQUAL_WEIGHT;
		for (SavingPlanInstrument instrument : instruments) {
			addReasonCode(reasonCodes, instrument.isin(), weightCode);
			if (weighting.scoreWeighted()) {
				addReasonCode(reasonCodes, instrument.isin(), REASON_SCORE_WEIGHTED);
			}
		}
		if (minRebalance != null && minRebalance.signum() > 0) {
			for (Map.Entry<AssessorEngine.PlanKey, BigDecimal> entry : allocation.proposedAmounts().entrySet()) {
				String isin = keyToIsin.get(entry.getKey());
				if (isin == null) {
					continue;
				}
				BigDecimal current = currentByIsin.getOrDefault(isin, BigDecimal.ZERO);
				BigDecimal proposed = entry.getValue() == null ? BigDecimal.ZERO : entry.getValue();
				BigDecimal delta = proposed.subtract(current);
				if (delta.signum() != 0 && delta.abs().compareTo(minRebalance) < 0) {
					addReasonCode(reasonCodes, isin, REASON_MIN_REBALANCE);
				}
			}
		}
		for (AssessorEngine.PlanKey key : allocation.discardedPlans()) {
			String isin = keyToIsin.get(key);
			if (isin != null) {
				addReasonCode(reasonCodes, isin, REASON_MIN_DROPPED);
			}
		}

		Map<String, BigDecimal> proposedAmounts = new HashMap<>();
		for (SavingPlanInstrument instrument : instruments) {
			AssessorEngine.PlanKey key = new AssessorEngine.PlanKey(instrument.isin(), null);
			BigDecimal proposed = allocation.proposedAmounts().getOrDefault(key, BigDecimal.ZERO);
			proposedAmounts.put(instrument.isin(), proposed);
		}

		LayerWeightingSummary weightingSummary = new LayerWeightingSummary(
				layer,
				instruments.size(),
				weighting.weighted(),
				weighting.scoreWeighted(),
				weighting.costUsed(),
				weighting.benchmarkUsed(),
				weighting.regionsUsed(),
				weighting.sectorsUsed(),
				weighting.holdingsUsed(),
				weighting.valuationUsed(),
				Map.copyOf(weighting.weights())
		);

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

	private WeightingResult computeWeights(int layer,
							   List<SavingPlanInstrument> instruments,
							   Map<String, KbExtraction> extractions,
							   Map<String, Integer> assessmentScores,
							   double scoreCutoff) {
		if (instruments == null || instruments.isEmpty()) {
			return WeightingResult.equal(instruments);
		}
		if (instruments.size() == 1) {
			SavingPlanInstrument instrument = instruments.get(0);
			return WeightingResult.single(instrument.isin());
		}
		boolean useScoreWeighting = instruments.size() > 1;
		Map<String, Double> scoreFactors = useScoreWeighting
				? buildScoreWeightFactors(instruments, assessmentScores, scoreCutoff)
				: Map.of();
		boolean hasScoreWeights = useScoreWeighting && !scoreFactors.isEmpty();

		boolean useCost = true;
		boolean useBenchmark = true;
		boolean useRegions = true;
		boolean useHoldings = true;
		boolean useSectors = layer != 5;
		boolean useValuation = true;
		Map<String, String> benchmarks = new HashMap<>();
		Map<String, BigDecimal> ters = new HashMap<>();
		Map<String, Set<String>> regionNames = new HashMap<>();
		Map<String, Set<String>> holdingNames = new HashMap<>();
		Map<String, Set<String>> sectorNames = new HashMap<>();
		Map<String, Map<String, BigDecimal>> regionWeights = new HashMap<>();
		Map<String, Map<String, BigDecimal>> holdingWeights = new HashMap<>();
		Map<String, Map<String, BigDecimal>> sectorWeights = new HashMap<>();
		Map<String, BigDecimal> valuationScores = new HashMap<>();
		Map<String, Double> dataPenalties = new HashMap<>();

		for (SavingPlanInstrument instrument : instruments) {
			KbExtraction extraction = extractions.get(instrument.isin());
			InstrumentDossierExtractionPayload payload = extraction == null ? null : extraction.payload();
			BigDecimal ter = null;
			String benchmark = null;
			Set<String> regions = null;
			Set<String> holdings = null;
			Set<String> sectors = null;
			BigDecimal valuationScore = null;
			double dataPenalty = 0.0;
			if (payload != null) {
				if (payload.etf() != null) {
					ter = payload.etf().ongoingChargesPct();
					benchmark = payload.etf().benchmarkIndex();
				}
				regions = normalizeRegionNames(payload.regions());
				holdings = normalizeHoldingNames(payload.topHoldings());
				sectors = normalizeSectorNames(payload.sectors(), payload.gicsSector());
				Map<String, BigDecimal> regionWeight = normalizeRegionWeights(payload.regions());
				if (regionWeight != null && !regionWeight.isEmpty()) {
					regionWeights.put(instrument.isin(), regionWeight);
				}
				Map<String, BigDecimal> holdingWeight = normalizeHoldingWeights(payload.topHoldings());
				if (holdingWeight != null && !holdingWeight.isEmpty()) {
					holdingWeights.put(instrument.isin(), holdingWeight);
				}
				Map<String, BigDecimal> sectorWeight = normalizeSectorWeights(payload.sectors(), payload.gicsSector());
				if (sectorWeight != null && !sectorWeight.isEmpty()) {
					sectorWeights.put(instrument.isin(), sectorWeight);
				}
				valuationScore = computeValuationScore(payload);
				dataPenalty = computeDataQualityPenalty(payload);
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
			if (sectors == null || sectors.isEmpty()) {
				useSectors = false;
			} else {
				sectorNames.put(instrument.isin(), sectors);
			}
			if (valuationScore == null) {
				useValuation = false;
			} else {
				valuationScores.put(instrument.isin(), valuationScore);
			}
			if (dataPenalty > 0) {
				dataPenalties.put(instrument.isin(), dataPenalty);
			}
		}

		boolean useRedundancy = useBenchmark || useRegions || useHoldings || useSectors;
		if (!useCost && !useRedundancy && !useValuation) {
			return hasScoreWeights
					? scoreWeightedResult(instruments, scoreFactors)
					: WeightingResult.equal(instruments);
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
						benchmarks, benchmarkCounts, regionNames, holdingNames, sectorNames,
						regionWeights, holdingWeights, sectorWeights,
						useBenchmark, useRegions, useHoldings, useSectors, sectorWeightFactor(layer));
				BigDecimal uniqueness = BigDecimal.ONE.subtract(BigDecimal.valueOf(redundancy));
				if (uniqueness.compareTo(BigDecimal.ZERO) < 0) {
					uniqueness = BigDecimal.ZERO;
				}
				score = score.multiply(uniqueness);
			}
			if (useValuation) {
				BigDecimal valuationScore = valuationScores.get(instrument.isin());
				if (valuationScore != null) {
					BigDecimal factor = BigDecimal.valueOf(0.7)
							.add(valuationScore.multiply(BigDecimal.valueOf(0.3)));
					score = score.multiply(factor);
				}
			}
			double penalty = dataPenalties.getOrDefault(instrument.isin(), 0.0);
			if (penalty > 0) {
				double factor = Math.max(0.0, 1.0 - penalty);
				score = score.multiply(BigDecimal.valueOf(factor));
			}
			if (hasScoreWeights) {
				double scoreFactor = scoreFactors.getOrDefault(instrument.isin(), 1.0);
				score = score.multiply(BigDecimal.valueOf(scoreFactor));
			}
			scores.put(instrument.isin(), score);
			total = total.add(score);
		}

		if (total.signum() <= 0) {
			return hasScoreWeights
					? scoreWeightedResult(instruments, scoreFactors)
					: WeightingResult.equal(instruments);
		}

		Map<String, BigDecimal> weights = new HashMap<>();
		for (SavingPlanInstrument instrument : instruments) {
			BigDecimal score = scores.getOrDefault(instrument.isin(), BigDecimal.ZERO);
			weights.put(instrument.isin(), score.divide(total, 8, RoundingMode.HALF_UP));
		}
		return new WeightingResult(weights, useCost || useRedundancy || useValuation, hasScoreWeights, useCost,
				useBenchmark, useRegions, useHoldings, useSectors, useValuation);
	}

	private Map<String, Double> buildScoreWeightFactors(List<SavingPlanInstrument> instruments,
												Map<String, Integer> assessmentScores,
												double scoreCutoff) {
		if (instruments == null || instruments.isEmpty() || assessmentScores == null || assessmentScores.isEmpty()) {
			return Map.of();
		}
		Map<String, Double> factors = new HashMap<>();
		for (SavingPlanInstrument instrument : instruments) {
			if (instrument == null || instrument.isin() == null) {
				continue;
			}
			Integer score = assessmentScores.get(instrument.isin());
			double factor = score == null ? 1.0 : scoreWeightFactor(score, scoreCutoff);
			factors.put(instrument.isin(), factor);
		}
		return factors;
	}

	private WeightingResult scoreWeightedResult(List<SavingPlanInstrument> instruments,
										 Map<String, Double> scoreFactors) {
		Map<String, BigDecimal> weights = scoreWeightedWeights(instruments, scoreFactors);
		boolean scoreWeighted = instruments != null && instruments.size() > 1 && scoreFactors != null && !scoreFactors.isEmpty();
		return new WeightingResult(weights, false, scoreWeighted, false, false, false, false, false, false);
	}

	private Map<String, BigDecimal> scoreWeightedWeights(List<SavingPlanInstrument> instruments,
												 Map<String, Double> scoreFactors) {
		if (instruments == null || instruments.isEmpty()) {
			return Map.of();
		}
		if (instruments.size() == 1) {
			SavingPlanInstrument instrument = instruments.get(0);
			return Map.of(instrument.isin(), BigDecimal.ONE);
		}
		Map<String, BigDecimal> raw = new HashMap<>();
		BigDecimal total = BigDecimal.ZERO;
		for (SavingPlanInstrument instrument : instruments) {
			if (instrument == null || instrument.isin() == null) {
				continue;
			}
			double factor = scoreFactors == null ? 1.0 : scoreFactors.getOrDefault(instrument.isin(), 1.0);
			BigDecimal weight = BigDecimal.valueOf(Math.max(0.0, factor));
			raw.put(instrument.isin(), weight);
			total = total.add(weight);
		}
		if (total.signum() <= 0) {
			return WeightingResult.equal(instruments).weights();
		}
		Map<String, BigDecimal> normalized = new HashMap<>();
		for (Map.Entry<String, BigDecimal> entry : raw.entrySet()) {
			normalized.put(entry.getKey(), entry.getValue().divide(total, 8, RoundingMode.HALF_UP));
		}
		return normalized;
	}

	private double scoreWeightFactor(int score, double scoreCutoff) {
		if (scoreCutoff <= 0) {
			return 1.0;
		}
		double normalized = (scoreCutoff - (double) score + 1.0) / scoreCutoff;
		if (normalized > 1.0) {
			return 1.0;
		}
		if (normalized < MIN_SCORE_WEIGHT_FACTOR) {
			return MIN_SCORE_WEIGHT_FACTOR;
		}
		return normalized;
	}

	private BigDecimal computeValuationScore(InstrumentDossierExtractionPayload payload) {
		if (payload == null || payload.valuation() == null) {
			return null;
		}
		BigDecimal longtermYield = extractLongtermEarningsYield(payload.valuation());
		BigDecimal holdingsYield = extractHoldingsEarningsYield(payload.valuation());
		BigDecimal currentYield = extractCurrentEarningsYield(payload.valuation());
		BigDecimal priceToBook = extractPriceToBook(payload.valuation());
		BigDecimal evToEbitda = extractEvToEbitda(payload.valuation());
		BigDecimal ebitdaEur = extractEbitdaEur(payload.valuation());
		BigDecimal dividendYield = extractDividendYield(payload);
		double longtermYieldScore = scoreEarningsYield(longtermYield);
		double holdingsYieldScore = scoreEarningsYield(holdingsYield);
		double currentYieldScore = scoreEarningsYield(currentYield);
		double dividendYieldScore = scoreDividendYield(dividendYield);
		double evScore = scoreEvToEbitda(evToEbitda);
		double profitabilityScore = scoreEbitdaEur(ebitdaEur);
		double pbScore = scorePriceToBook(priceToBook);
		double weightSum = 0.0;
		double scoreSum = 0.0;
		if (isEtf(payload)) {
			if (holdingsYieldScore > 0) {
				scoreSum += holdingsYieldScore * ETF_HOLDINGS_YIELD_WEIGHT;
				weightSum += ETF_HOLDINGS_YIELD_WEIGHT;
			}
			if (currentYieldScore > 0) {
				scoreSum += currentYieldScore * ETF_CURRENT_YIELD_WEIGHT;
				weightSum += ETF_CURRENT_YIELD_WEIGHT;
			}
			if (pbScore > 0) {
				scoreSum += pbScore * ETF_PB_WEIGHT;
				weightSum += ETF_PB_WEIGHT;
			}
			if (dividendYieldScore > 0) {
				scoreSum += dividendYieldScore * ETF_DIVIDEND_WEIGHT;
				weightSum += ETF_DIVIDEND_WEIGHT;
			}
		} else if (isReit(payload)) {
			if (longtermYieldScore > 0) {
				scoreSum += longtermYieldScore * REIT_LONGTERM_YIELD_WEIGHT;
				weightSum += REIT_LONGTERM_YIELD_WEIGHT;
			}
			if (currentYieldScore > 0) {
				scoreSum += currentYieldScore * REIT_CURRENT_YIELD_WEIGHT;
				weightSum += REIT_CURRENT_YIELD_WEIGHT;
			}
			if (evScore > 0) {
				scoreSum += evScore * REIT_EV_WEIGHT;
				weightSum += REIT_EV_WEIGHT;
			}
			if (pbScore > 0) {
				scoreSum += pbScore * REIT_PB_WEIGHT;
				weightSum += REIT_PB_WEIGHT;
			}
			if (profitabilityScore > 0) {
				scoreSum += profitabilityScore * REIT_PROFITABILITY_WEIGHT;
				weightSum += REIT_PROFITABILITY_WEIGHT;
			}
		} else {
			if (longtermYieldScore > 0) {
				scoreSum += longtermYieldScore * STOCK_LONGTERM_YIELD_WEIGHT;
				weightSum += STOCK_LONGTERM_YIELD_WEIGHT;
			}
			if (currentYieldScore > 0) {
				scoreSum += currentYieldScore * STOCK_CURRENT_YIELD_WEIGHT;
				weightSum += STOCK_CURRENT_YIELD_WEIGHT;
			}
			if (evScore > 0) {
				scoreSum += evScore * STOCK_EV_WEIGHT;
				weightSum += STOCK_EV_WEIGHT;
			}
			if (dividendYieldScore > 0) {
				scoreSum += dividendYieldScore * STOCK_DIVIDEND_WEIGHT;
				weightSum += STOCK_DIVIDEND_WEIGHT;
			}
			if (pbScore > 0) {
				scoreSum += pbScore * STOCK_PB_WEIGHT;
				weightSum += STOCK_PB_WEIGHT;
			}
		}
		if (weightSum <= 0) {
			return null;
		}
		double baseScore = scoreSum / weightSum;
		double quality = valuationQualityMultiplier(payload.valuation().peMethod(),
				payload.valuation().peHorizon(),
				payload.valuation().negEarningsHandling());
		return BigDecimal.valueOf(baseScore * quality);
	}

	private BigDecimal extractLongtermEarningsYield(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (valuation == null) {
			return null;
		}
		BigDecimal yield = valuation.earningsYieldLongterm();
		if (yield != null) {
			return yield;
		}
		BigDecimal pe = valuation.peLongterm();
		if (pe != null && pe.compareTo(BigDecimal.ZERO) > 0) {
			return BigDecimal.ONE.divide(pe, 8, RoundingMode.HALF_UP);
		}
		BigDecimal computedYield = computeLongtermEarningsYield(valuation);
		if (computedYield != null) {
			return computedYield;
		}
		return null;
	}

	private BigDecimal extractHoldingsEarningsYield(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (valuation == null) {
			return null;
		}
		BigDecimal yield = valuation.earningsYieldTtmHoldings();
		if (yield != null) {
			return yield;
		}
		BigDecimal peHoldings = valuation.peTtmHoldings();
		if (peHoldings != null && peHoldings.compareTo(BigDecimal.ZERO) > 0) {
			return BigDecimal.ONE.divide(peHoldings, 8, RoundingMode.HALF_UP);
		}
		return null;
	}

	private BigDecimal extractCurrentEarningsYield(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (valuation == null) {
			return null;
		}
		BigDecimal peCurrent = valuation.peCurrent();
		if (peCurrent != null && peCurrent.compareTo(BigDecimal.ZERO) > 0) {
			return BigDecimal.ONE.divide(peCurrent, 8, RoundingMode.HALF_UP);
		}
		return null;
	}

	private BigDecimal extractPriceToBook(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (valuation == null) {
			return null;
		}
		return valuation.pbCurrent();
	}

	private BigDecimal computeLongtermEarningsYield(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		BigDecimal epsNorm = computeEpsNormFromHistory(valuation);
		BigDecimal price = extractPrice(valuation);
		if (epsNorm == null || price == null) {
			return null;
		}
		if (epsNorm.compareTo(BigDecimal.ZERO) <= 0 || price.compareTo(BigDecimal.ZERO) <= 0) {
			return null;
		}
		return epsNorm.divide(price, 8, RoundingMode.HALF_UP);
	}

	private BigDecimal computeEpsNormFromHistory(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		List<InstrumentDossierExtractionPayload.EpsHistoryPayload> history = valuation == null ? null : valuation.epsHistory();
		if (history == null || history.isEmpty()) {
			return null;
		}
		List<InstrumentDossierExtractionPayload.EpsHistoryPayload> selected = selectPreferredEpsHistory(history);
		List<BigDecimal> values = new ArrayList<>();
		selected.stream()
				.filter(entry -> entry != null && entry.year() != null && entry.eps() != null)
				.sorted(Comparator.comparing(InstrumentDossierExtractionPayload.EpsHistoryPayload::year).reversed())
				.limit(7)
				.forEach(entry -> values.add(applyEpsFloor(entry.eps(), valuation)));
		if (values.size() < 3) {
			return null;
		}
		values.sort(Comparator.naturalOrder());
		int mid = values.size() / 2;
		if (values.size() % 2 == 1) {
			return values.get(mid);
		}
		return values.get(mid - 1).add(values.get(mid)).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
	}

	private List<InstrumentDossierExtractionPayload.EpsHistoryPayload> selectPreferredEpsHistory(
			List<InstrumentDossierExtractionPayload.EpsHistoryPayload> history) {
		boolean hasAdjusted = history.stream().anyMatch(entry -> isAdjustedEpsType(entry == null ? null : entry.epsType()));
		if (!hasAdjusted) {
			return history;
		}
		return history.stream()
				.filter(entry -> isAdjustedEpsType(entry == null ? null : entry.epsType()))
				.toList();
	}

	private boolean isAdjustedEpsType(String epsType) {
		if (epsType == null || epsType.isBlank()) {
			return false;
		}
		String normalized = epsType.trim().toLowerCase(Locale.ROOT);
		return normalized.contains("adjusted")
				|| normalized.contains("normalized")
				|| normalized.contains("non-gaap")
				|| normalized.contains("non gaap");
	}

	private BigDecimal applyEpsFloor(BigDecimal value, InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (value == null) {
			return null;
		}
		if (value.compareTo(BigDecimal.ZERO) <= 0) {
			return value;
		}
		if (!shouldApplyEpsFloor(valuation == null ? null : valuation.epsFloorPolicy())) {
			return value;
		}
		BigDecimal floor = valuation == null || valuation.epsFloorValue() == null
				? new BigDecimal("0.10")
				: valuation.epsFloorValue();
		if (floor == null || floor.compareTo(BigDecimal.ZERO) <= 0) {
			return value;
		}
		return value.compareTo(floor) < 0 ? floor : value;
	}

	private boolean shouldApplyEpsFloor(String policy) {
		if (policy == null || policy.isBlank()) {
			return true;
		}
		String normalized = policy.trim().toLowerCase(Locale.ROOT);
		return !(normalized.equals("none") || normalized.equals("off") || normalized.equals("no_floor"));
	}

	private BigDecimal extractPrice(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (valuation == null) {
			return null;
		}
		BigDecimal price = valuation.price();
		if (price != null) {
			return price;
		}
		BigDecimal marketCap = valuation.marketCap();
		BigDecimal shares = valuation.sharesOutstanding();
		if (marketCap != null && shares != null && shares.compareTo(BigDecimal.ZERO) > 0) {
			return marketCap.divide(shares, 8, RoundingMode.HALF_UP);
		}
		return null;
	}

	private BigDecimal extractEvToEbitda(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (valuation == null) {
			return null;
		}
		BigDecimal evToEbitda = valuation.evToEbitda();
		if (evToEbitda != null) {
			return evToEbitda;
		}
		BigDecimal enterpriseValue = valuation.enterpriseValue();
		BigDecimal ebitda = valuation.ebitda();
		if (enterpriseValue != null && ebitda != null && ebitda.compareTo(BigDecimal.ZERO) > 0) {
			return enterpriseValue.divide(ebitda, 8, RoundingMode.HALF_UP);
		}
		return null;
	}

	private BigDecimal extractEbitdaEur(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (valuation == null) {
			return null;
		}
		return extractProfitabilityEur(valuation);
	}

	private BigDecimal extractProfitabilityEur(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (valuation == null) {
			return null;
		}
		BigDecimal ebitdaEur = valuation.ebitdaEur();
		if (ebitdaEur != null) {
			return ebitdaEur;
		}
		BigDecimal ebitda = convertMetricToEur(valuation.ebitda(), valuation.ebitdaCurrency(), valuation.fxRateToEur());
		if (ebitda != null) {
			return ebitda;
		}
		BigDecimal affo = convertMetricToEur(valuation.affo(), valuation.affoCurrency(), valuation.fxRateToEur());
		if (affo != null) {
			return affo;
		}
		BigDecimal ffo = convertMetricToEur(valuation.ffo(), valuation.ffoCurrency(), valuation.fxRateToEur());
		if (ffo != null) {
			return ffo;
		}
		BigDecimal noi = convertMetricToEur(valuation.noi(), valuation.noiCurrency(), valuation.fxRateToEur());
		if (noi != null) {
			return noi;
		}
		return convertMetricToEur(valuation.netRent(), valuation.netRentCurrency(), valuation.fxRateToEur());
	}

	private BigDecimal extractNetIncomeEur(InstrumentDossierExtractionPayload payload) {
		if (payload == null || payload.financials() == null) {
			return null;
		}
		InstrumentDossierExtractionPayload.FinancialsPayload financials = payload.financials();
		if (financials.netIncomeEur() != null) {
			return financials.netIncomeEur();
		}
		BigDecimal fxRate = resolveFinancialsFxRate(financials, payload);
		return convertMetricToEur(financials.netIncome(), financials.netIncomeCurrency(), fxRate);
	}

	private BigDecimal extractRevenueEur(InstrumentDossierExtractionPayload payload) {
		if (payload == null || payload.financials() == null) {
			return null;
		}
		InstrumentDossierExtractionPayload.FinancialsPayload financials = payload.financials();
		if (financials.revenueEur() != null) {
			return financials.revenueEur();
		}
		BigDecimal fxRate = resolveFinancialsFxRate(financials, payload);
		return convertMetricToEur(financials.revenue(), financials.revenueCurrency(), fxRate);
	}

	private BigDecimal extractDividendYield(InstrumentDossierExtractionPayload payload) {
		if (payload == null || payload.financials() == null || payload.valuation() == null) {
			return null;
		}
		InstrumentDossierExtractionPayload.FinancialsPayload financials = payload.financials();
		BigDecimal dividend = financials.dividendPerShare();
		if (dividend == null || dividend.compareTo(BigDecimal.ZERO) <= 0) {
			return null;
		}
		BigDecimal price = extractPrice(payload.valuation());
		if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
			return null;
		}
		String dividendCcy = financials.dividendCurrency();
		String priceCcy = payload.valuation().priceCurrency();
		if (dividendCcy != null && priceCcy != null && !dividendCcy.equalsIgnoreCase(priceCcy)) {
			BigDecimal fxDividend = resolveFinancialsFxRate(financials, payload);
			BigDecimal dividendEur = convertMetricToEur(dividend, dividendCcy, fxDividend);
			BigDecimal priceEur = convertMetricToEur(price, priceCcy, payload.valuation().fxRateToEur());
			if (dividendEur == null || priceEur == null || priceEur.compareTo(BigDecimal.ZERO) <= 0) {
				return null;
			}
			return dividendEur.divide(priceEur, 8, RoundingMode.HALF_UP);
		}
		return dividend.divide(price, 8, RoundingMode.HALF_UP);
	}

	private BigDecimal resolveFinancialsFxRate(InstrumentDossierExtractionPayload.FinancialsPayload financials,
											   InstrumentDossierExtractionPayload payload) {
		if (financials != null && financials.fxRateToEur() != null) {
			return financials.fxRateToEur();
		}
		if (payload != null && payload.valuation() != null) {
			return payload.valuation().fxRateToEur();
		}
		return null;
	}

	private BigDecimal convertMetricToEur(BigDecimal value, String currency, BigDecimal fxRate) {
		if (value == null) {
			return null;
		}
		if (currency == null || currency.isBlank() || currency.equalsIgnoreCase("EUR")) {
			return value;
		}
		if (fxRate == null || fxRate.compareTo(BigDecimal.ZERO) <= 0) {
			return null;
		}
		return value.multiply(fxRate);
	}

	private double scoreEarningsYield(BigDecimal earningsYield) {
		if (earningsYield == null) {
			return 0.0;
		}
		double value = earningsYield.doubleValue();
		if (value <= 0) {
			return 0.0;
		}
		double cap = 0.20;
		return Math.min(value / cap, 1.0);
	}

	private double scoreEvToEbitda(BigDecimal evToEbitda) {
		if (evToEbitda == null) {
			return 0.0;
		}
		double value = evToEbitda.doubleValue();
		if (value <= 0) {
			return 0.0;
		}
		double target = 12.0;
		return Math.min(target / value, 1.0);
	}

	private double scoreDividendYield(BigDecimal dividendYield) {
		if (dividendYield == null) {
			return 0.0;
		}
		double value = dividendYield.doubleValue();
		if (value <= 0) {
			return 0.0;
		}
		double cap = 0.08;
		return Math.min(value / cap, 1.0);
	}

	private double scorePriceToBook(BigDecimal priceToBook) {
		if (priceToBook == null) {
			return 0.0;
		}
		double value = priceToBook.doubleValue();
		if (value <= 0) {
			return 0.0;
		}
		return Math.min(DEFAULT_PB_TARGET / value, 1.0);
	}

	private double scoreEbitdaEur(BigDecimal ebitdaEur) {
		if (ebitdaEur == null) {
			return 0.0;
		}
		double value = ebitdaEur.doubleValue();
		if (value <= 0) {
			return 0.0;
		}
		double cap = 10_000_000_000d;
		double scaled = Math.log10(1.0 + Math.min(value, cap)) / Math.log10(1.0 + cap);
		return Math.max(0.0, Math.min(scaled, 1.0));
	}

	private double scoreNetIncomeEur(BigDecimal netIncomeEur) {
		if (netIncomeEur == null) {
			return 0.0;
		}
		double value = netIncomeEur.doubleValue();
		if (value <= 0) {
			return 0.0;
		}
		double cap = 20_000_000_000d;
		double scaled = Math.log10(1.0 + Math.min(value, cap)) / Math.log10(1.0 + cap);
		return Math.max(0.0, Math.min(scaled, 1.0));
	}

	private double scoreRevenueEur(BigDecimal revenueEur) {
		if (revenueEur == null) {
			return 0.0;
		}
		double value = revenueEur.doubleValue();
		if (value <= 0) {
			return 0.0;
		}
		double cap = 200_000_000_000d;
		double scaled = Math.log10(1.0 + Math.min(value, cap)) / Math.log10(1.0 + cap);
		return Math.max(0.0, Math.min(scaled, 1.0));
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

	private Map<String, BigDecimal> normalizeRegionWeights(List<InstrumentDossierExtractionPayload.RegionExposurePayload> regions) {
		if (regions == null || regions.isEmpty()) {
			return null;
		}
		Map<String, BigDecimal> normalized = new LinkedHashMap<>();
		for (InstrumentDossierExtractionPayload.RegionExposurePayload region : regions) {
			String name = normalizeLabel(region == null ? null : region.name());
			if (name == null) {
				continue;
			}
			BigDecimal weight = normalizeWeightPct(region.weightPct());
			if (weight != null) {
				normalized.put(name, weight);
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

	private Map<String, BigDecimal> normalizeHoldingWeights(List<InstrumentDossierExtractionPayload.HoldingPayload> holdings) {
		if (holdings == null || holdings.isEmpty()) {
			return null;
		}
		Map<String, BigDecimal> normalized = new LinkedHashMap<>();
		for (InstrumentDossierExtractionPayload.HoldingPayload holding : holdings) {
			String name = normalizeLabel(holding == null ? null : holding.name());
			if (name == null) {
				continue;
			}
			BigDecimal weight = normalizeWeightPct(holding.weightPct());
			if (weight != null) {
				normalized.put(name, weight);
			}
		}
		return normalized.isEmpty() ? null : normalized;
	}

	private Set<String> normalizeSectorNames(List<InstrumentDossierExtractionPayload.SectorExposurePayload> sectors,
								 String gicsSector) {
		Set<String> normalized = new LinkedHashSet<>();
		if (sectors != null) {
			for (InstrumentDossierExtractionPayload.SectorExposurePayload sector : sectors) {
				String cleaned = normalizeLabel(sector == null ? null : sector.name());
				if (cleaned != null) {
					normalized.add(cleaned);
				}
			}
		}
		if (normalized.isEmpty()) {
			String fallback = normalizeLabel(gicsSector);
			if (fallback != null) {
				normalized.add(fallback);
			}
		}
		return normalized.isEmpty() ? null : normalized;
	}

	private Map<String, BigDecimal> normalizeSectorWeights(
			List<InstrumentDossierExtractionPayload.SectorExposurePayload> sectors,
			String gicsSector) {
		Map<String, BigDecimal> normalized = new LinkedHashMap<>();
		if (sectors != null) {
			for (InstrumentDossierExtractionPayload.SectorExposurePayload sector : sectors) {
				String name = normalizeLabel(sector == null ? null : sector.name());
				if (name == null) {
					continue;
				}
				BigDecimal weight = normalizeWeightPct(sector.weightPct());
				if (weight != null) {
					normalized.put(name, weight);
				}
			}
		}
		if (normalized.isEmpty()) {
			String fallback = normalizeLabel(gicsSector);
			if (fallback != null) {
				normalized.put(fallback, BigDecimal.ONE);
			}
		}
		return normalized.isEmpty() ? null : normalized;
	}

	private BigDecimal normalizeWeightPct(BigDecimal weight) {
		if (weight == null || weight.signum() <= 0) {
			return null;
		}
		return weight.compareTo(BigDecimal.ONE) > 0
				? weight.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
				: weight;
	}

	private double sectorWeightFactor(int layer) {
		if (layer == 1) {
			return 0.5;
		}
		if (layer == 5) {
			return 0.0;
		}
		return 1.0;
	}

	private String normalizeLabel(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		return raw.trim().toLowerCase(Locale.ROOT);
	}

	private boolean isEtf(InstrumentDossierExtractionPayload payload) {
		if (payload == null) {
			return false;
		}
		String type = normalizeLabel(payload.instrumentType());
		String asset = normalizeLabel(payload.assetClass());
		return (type != null && (type.contains("etf") || type.contains("ucits")))
				|| (asset != null && asset.contains("fund"));
	}

	private boolean isReit(InstrumentDossierExtractionPayload payload) {
		if (payload == null) {
			return false;
		}
		String type = normalizeLabel(payload.instrumentType());
		String sub = normalizeLabel(payload.subClass());
		String notes = normalizeLabel(payload.layerNotes());
		return (type != null && type.contains("reit"))
				|| (sub != null && sub.contains("reit"))
				|| (notes != null && notes.contains("reit"));
	}

	private double valuationQualityMultiplier(String peMethod, String peHorizon, String negHandling) {
		double multiplier = 1.0;
		String method = normalizeLabel(peMethod);
		if (method == null) {
			multiplier *= 0.95;
		} else {
			switch (method) {
				case "ttm" -> multiplier *= 1.0;
				case "forward" -> multiplier *= 0.90;
				case "provider_weighted_avg" -> multiplier *= 0.95;
				case "provider_aggregate" -> multiplier *= 0.90;
				default -> multiplier *= 0.92;
			}
		}
		String horizon = normalizeLabel(peHorizon);
		if (horizon == null) {
			multiplier *= 0.95;
		} else if (horizon.contains("normalized")) {
			multiplier *= 1.0;
		} else if (horizon.contains("ttm")) {
			multiplier *= 0.95;
		} else {
			multiplier *= 0.92;
		}
		String handling = normalizeLabel(negHandling);
		if (handling == null) {
			multiplier *= 0.97;
		} else {
			switch (handling) {
				case "exclude" -> multiplier *= 1.0;
				case "set_null" -> multiplier *= 0.95;
				case "aggregate_allows_negative" -> multiplier *= 0.90;
				default -> multiplier *= 0.92;
			}
		}
		return Math.min(1.0, Math.max(0.7, multiplier));
	}

	private double computeDataQualityPenalty(InstrumentDossierExtractionPayload payload) {
		if (payload == null) {
			return 0.0;
		}
		int missing = payload.missingFields() == null ? 0 : payload.missingFields().size();
		int warnings = payload.warnings() == null ? 0 : payload.warnings().size();
		double penalty = (missing * DATA_QUALITY_MISSING_WEIGHT) + (warnings * DATA_QUALITY_WARNING_WEIGHT);
		return Math.min(MAX_DATA_QUALITY_PENALTY, Math.max(0.0, penalty));
	}

	private double computeRedundancy(String isin,
								 int instrumentCount,
								 Map<String, String> benchmarks,
								 Map<String, Integer> benchmarkCounts,
								 Map<String, Set<String>> regionNames,
								 Map<String, Set<String>> holdingNames,
								 Map<String, Set<String>> sectorNames,
								 Map<String, Map<String, BigDecimal>> regionWeights,
								 Map<String, Map<String, BigDecimal>> holdingWeights,
								 Map<String, Map<String, BigDecimal>> sectorWeights,
								 boolean useBenchmark,
								 boolean useRegions,
								 boolean useHoldings,
								 boolean useSectors,
								 double sectorWeightFactor) {
		if (instrumentCount <= 1) {
			return 0.0d;
		}
		double sum = 0.0d;
		double weightSum = 0.0d;
		if (useBenchmark) {
			String benchmark = benchmarks.get(isin);
			int count = benchmark == null ? 1 : benchmarkCounts.getOrDefault(benchmark, 1);
			double overlap = (double) (count - 1) / (double) (instrumentCount - 1);
			sum += overlap;
			weightSum += 1.0d;
		}
		if (useRegions) {
			double overlap = averageWeightedOverlap(isin, regionWeights);
			if (overlap < 0) {
				overlap = averageOverlap(isin, regionNames, instrumentCount);
			}
			sum += overlap;
			weightSum += 1.0d;
		}
		if (useHoldings) {
			double overlap = averageWeightedOverlap(isin, holdingWeights);
			if (overlap < 0) {
				overlap = averageOverlap(isin, holdingNames, instrumentCount);
			}
			sum += overlap;
			weightSum += 1.0d;
		}
		if (useSectors) {
			double weight = Math.max(0.0d, Math.min(1.0d, sectorWeightFactor));
			if (weight > 0.0d) {
				double overlap = averageWeightedOverlap(isin, sectorWeights);
				if (overlap < 0) {
					overlap = averageOverlap(isin, sectorNames, instrumentCount);
				}
				sum += overlap * weight;
				weightSum += weight;
			}
		}
		if (weightSum == 0.0d) {
			return 0.0d;
		}
		return sum / weightSum;
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

	private double averageWeightedOverlap(String isin, Map<String, Map<String, BigDecimal>> entries) {
		if (entries == null || entries.isEmpty()) {
			return -1.0d;
		}
		Map<String, BigDecimal> base = entries.get(isin);
		if (base == null || base.isEmpty()) {
			return -1.0d;
		}
		double baseTotal = base.values().stream().mapToDouble(value -> value == null ? 0.0 : value.doubleValue()).sum();
		if (baseTotal <= 0.0) {
			return -1.0d;
		}
		double sum = 0.0d;
		int count = 0;
		for (Map.Entry<String, Map<String, BigDecimal>> entry : entries.entrySet()) {
			if (isin.equals(entry.getKey())) {
				continue;
			}
			Map<String, BigDecimal> other = entry.getValue();
			if (other == null || other.isEmpty()) {
				continue;
			}
			double otherTotal = other.values().stream().mapToDouble(value -> value == null ? 0.0 : value.doubleValue()).sum();
			if (otherTotal <= 0.0) {
				continue;
			}
			double overlap = 0.0d;
			for (Map.Entry<String, BigDecimal> baseEntry : base.entrySet()) {
				BigDecimal otherValue = other.get(baseEntry.getKey());
				if (otherValue == null) {
					continue;
				}
				double left = baseEntry.getValue() == null ? 0.0 : baseEntry.getValue().doubleValue();
				double right = otherValue.doubleValue();
				overlap += Math.min(left, right);
			}
			double denom = Math.max(baseTotal, otherTotal);
			sum += denom <= 0.0 ? 0.0 : overlap / denom;
			count += 1;
		}
		if (count == 0) {
			return -1.0d;
		}
		return sum / (double) count;
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
									boolean scoreWeighted,
									boolean costUsed,
									boolean benchmarkUsed,
									boolean regionsUsed,
									boolean sectorsUsed,
									boolean holdingsUsed,
									boolean valuationUsed,
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
								   boolean scoreWeighted,
								   boolean costUsed,
								   boolean benchmarkUsed,
								   boolean regionsUsed,
								   boolean sectorsUsed,
								   boolean holdingsUsed,
								   boolean valuationUsed) {
		private static WeightingResult equal(List<SavingPlanInstrument> instruments) {
			Map<String, BigDecimal> weights = new HashMap<>();
			if (instruments != null && !instruments.isEmpty()) {
				BigDecimal share = BigDecimal.ONE.divide(BigDecimal.valueOf(instruments.size()), 8, RoundingMode.HALF_UP);
				for (SavingPlanInstrument instrument : instruments) {
					weights.put(instrument.isin(), share);
				}
			}
			return new WeightingResult(weights, false, false, false, false, false, false, false, false);
		}

		private static WeightingResult single(String isin) {
			Map<String, BigDecimal> weights = new HashMap<>();
			weights.put(isin, BigDecimal.ONE);
			return new WeightingResult(weights, false, false, false, false, false, false, false, false);
		}
	}
}
