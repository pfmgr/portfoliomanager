package my.portfoliomanager.app.service;

import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.dto.AssessorDiagnosticsDto;
import my.portfoliomanager.app.dto.AssessorInstrumentAssessmentDto;
import my.portfoliomanager.app.dto.AssessorInstrumentAssessmentItemDto;
import my.portfoliomanager.app.dto.AssessorInstrumentAssessmentScoreComponentDto;
import my.portfoliomanager.app.dto.AssessorInstrumentBucketDto;
import my.portfoliomanager.app.dto.AssessorNewInstrumentSuggestionDto;
import my.portfoliomanager.app.dto.AssessorOneTimeAllocationDto;
import my.portfoliomanager.app.dto.AssessorRunRequestDto;
import my.portfoliomanager.app.dto.AssessorRunResponseDto;
import my.portfoliomanager.app.dto.AssessorSavingPlanSuggestionDto;
import my.portfoliomanager.app.dto.LayerTargetConfigResponseDto;
import my.portfoliomanager.app.dto.LayerTargetRiskThresholdsDto;
import my.portfoliomanager.app.repository.SavingPlanRepository;
import my.portfoliomanager.app.repository.projection.SavingPlanListProjection;
import my.portfoliomanager.app.model.LayerTargetRiskThresholds;
import my.portfoliomanager.app.service.util.RiskThresholdsUtil;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AssessorService {
	private static final Set<String> COMPLETE_STATUSES = Set.of("COMPLETE", "APPROVED", "APPLIED");
	private static final BigDecimal DEFAULT_VARIANCE_PCT = new BigDecimal("3.0");
	private static final int DEFAULT_MIN_SAVING_PLAN = 15;
	private static final int DEFAULT_MIN_REBALANCE = 10;
	private static final int DEFAULT_MIN_INSTRUMENT = 25;
	private static final double MIN_SCORE_WEIGHT_FACTOR = 0.2;
	private static final String ASSESSMENT_TYPE_INSTRUMENT_ONE_TIME = "instrument_one_time";
	private static final String PROFILE_BALANCED = "BALANCED";
	private static final String PROMPT_RULES_HEADER = "Rules:\n";
	private static final String PROMPT_CONTEXT_HEADER = "Context:\n";
	private static final String PROMPT_LAYER_NAMES = "layer_names=";
	private static final String LABEL_LAYER_PREFIX = "Layer ";
	private static final String SQL_PARAM_ISINS = "isins";
	private static final String COLUMN_LAYER = "layer";
	private static final String PLAN_TYPE_INCREASE = "increase";

	private final SavingPlanRepository savingPlanRepository;
	private final LayerTargetConfigService layerTargetConfigService;
	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
	private final AppProperties properties;
	private final AssessorEngine assessorEngine;
	private final AssessorInstrumentSuggestionService instrumentSuggestionService;
	private final AssessorInstrumentAssessmentService instrumentAssessmentService;
	private final LlmNarrativeService llmNarrativeService;
	private final SavingPlanDeltaAllocator savingPlanDeltaAllocator;
	private final LlmPromptPolicy llmPromptPolicy;
	private final boolean llmEnabled;

	public AssessorService(SavingPlanRepository savingPlanRepository,
					   LayerTargetConfigService layerTargetConfigService,
					   NamedParameterJdbcTemplate namedParameterJdbcTemplate,
					   AppProperties properties,
					   AssessorEngine assessorEngine,
					   AssessorInstrumentSuggestionService instrumentSuggestionService,
					   AssessorInstrumentAssessmentService instrumentAssessmentService,
					   LlmNarrativeService llmNarrativeService,
					   SavingPlanDeltaAllocator savingPlanDeltaAllocator,
					   LlmPromptPolicy llmPromptPolicy) {
		this.savingPlanRepository = savingPlanRepository;
		this.layerTargetConfigService = layerTargetConfigService;
		this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
		this.properties = properties;
		this.assessorEngine = assessorEngine;
		this.instrumentSuggestionService = instrumentSuggestionService;
		this.instrumentAssessmentService = instrumentAssessmentService;
		this.llmNarrativeService = llmNarrativeService;
		this.savingPlanDeltaAllocator = savingPlanDeltaAllocator;
		this.llmPromptPolicy = llmPromptPolicy;
		this.llmEnabled = llmNarrativeService != null && llmNarrativeService.isEnabled();
	}

	public AssessorRunResponseDto run(AssessorRunRequestDto request) {
		RunSetup runSetup = buildRunSetup(request);
		if (isInstrumentAssessment(request)) {
			return runInstrumentAssessment(request, runSetup.selectedProfile(), runSetup.config(), runSetup.targets());
		}

		RunContext runContext = buildRunContext(request, runSetup);
		RunAmounts runAmounts = buildRunAmounts(request, runSetup);
		boolean instrumentAllocationEnabled = shouldAllocateInstruments(
				runAmounts.oneTimeAmount(),
				runContext.savingPlanSnapshot().plans());
		AssessorEngine.AssessorEngineResult result = runAssessorEngine(
				runSetup,
				runContext,
				runAmounts,
				instrumentAllocationEnabled);

		List<AssessorSavingPlanSuggestionDto> suggestionDtos =
				toSuggestionDtos(result.savingPlanSuggestions(), runContext.savingPlanSnapshot());
		AssessorInstrumentSuggestionService.SuggestionResult instrumentSuggestions = buildInstrumentSuggestions(
				new InstrumentSuggestionInput(
						result,
						runContext.savingPlanSnapshot(),
						runAmounts.savingPlanDelta(),
						runAmounts.oneTimeAmount(),
						runSetup.minimumSavingPlanSize(),
						runSetup.minimumRebalancingAmount(),
						runSetup.minimumInstrumentAmount(),
						runSetup.config(),
						runContext.existingInstrumentLayers(),
						runContext.kbDiagnostics(),
						runContext.riskThresholds(),
						runContext.riskThresholdsByLayer(),
						runContext.gapDetectionPolicy()));
		Set<String> oneTimeSuggestionIsins = collectSuggestionIsins(instrumentSuggestions.oneTimeSuggestions());
		Set<String> effectiveInstrumentIsins = loadEffectiveInstrumentIsins(oneTimeSuggestionIsins);
		List<AssessorNewInstrumentSuggestionDto> oneTimeNewInstruments =
				toNewInstrumentSuggestionDtos(instrumentSuggestions.oneTimeSuggestions(), effectiveInstrumentIsins);
		Map<String, BigDecimal> adjustedOneTimeBuckets = adjustOneTimeInstrumentBuckets(
				result.oneTimeAllocation(),
				runContext.savingPlanSnapshot(),
				oneTimeNewInstruments);
		SavingPlanAllocation savingPlanAllocation = buildSavingPlanAllocation(
				new SavingPlanAllocationInput(
						result,
						runContext.savingPlanSnapshot(),
						runAmounts.savingPlanDelta(),
						runSetup.minimumSavingPlanSize(),
						runSetup.minimumRebalancingAmount(),
						runSetup.config(),
						runContext.existingInstrumentLayers(),
						runContext.kbDiagnostics(),
						runContext.riskThresholds(),
						runContext.riskThresholdsByLayer(),
						runContext.gapDetectionPolicy()));
		SavingPlanPlanResult savingPlanPlan = resolveSavingPlanPlan(
				result,
				suggestionDtos,
				instrumentSuggestions.savingPlanSuggestions(),
				savingPlanAllocation);
		List<String> riskWarnings = buildRiskWarnings(
				runContext.savingPlanSnapshot(),
				runContext.riskThresholds(),
				runContext.riskThresholdsByLayer(),
				runContext.kbDiagnostics());
		NarrativeBundle narratives = buildNarratives(new NarrativeInput(
				result,
				runSetup.config(),
				runContext.savingPlanSnapshot(),
				runSetup.variance(),
				runSetup.minimumSavingPlanSize(),
				runSetup.minimumRebalancingAmount(),
				runSetup.projectionHorizonMonths(),
				runSetup.minimumInstrumentAmount(),
				runAmounts.oneTimeAmount(),
				savingPlanPlan.targetLayerAmounts(),
				savingPlanPlan.savingPlanSuggestions(),
				savingPlanPlan.newInstruments(),
				savingPlanPlan.allocationNotes(),
				oneTimeNewInstruments,
				adjustedOneTimeBuckets,
				runContext.gapDetectionPolicy()));

		return new AssessorRunResponseDto(
				result.selectedProfile(),
				resolveAsOfDate(runContext.holdings().asOfDate()),
				toAmount(result.currentMonthlyTotal()),
				toAmountMap(result.currentLayerDistribution()),
				toAmountMap(savingPlanPlan.targetLayerAmounts()),
				savingPlanPlan.savingPlanSuggestions(),
				savingPlanPlan.newInstruments(),
				narratives.savingPlanNarrative(),
				toOneTimeDto(result.oneTimeAllocation(), runContext.savingPlanSnapshot(), oneTimeNewInstruments, adjustedOneTimeBuckets),
				narratives.oneTimeNarrative(),
				null,
				toDiagnosticsDto(result.diagnostics(), runContext.kbDiagnostics(), riskWarnings)
		);
	}

	private RunSetup buildRunSetup(AssessorRunRequestDto request) {
		LayerTargetConfigResponseDto config = layerTargetConfigService.getConfigResponse();
		String selectedProfile = resolveProfileKey(null, config);
		LayerTargetConfigResponseDto.LayerTargetProfileDto profile = resolveRunProfile(config, selectedProfile);
		boolean applyOverrides = shouldApplyCustomOverrides(config, selectedProfile);
		Map<Integer, BigDecimal> targets = toBigDecimalMap(resolveLayerTargets(config, profile, applyOverrides));
		return new RunSetup(
				config,
				selectedProfile,
				profile,
				targets,
				resolveVariance(config, profile, applyOverrides),
				resolveMinimumSavingPlan(config, profile, applyOverrides),
				resolveMinimumRebalancing(config, profile, applyOverrides),
				resolveMinimumInstrument(request),
				profile == null ? null : profile.getProjectionHorizonMonths()
		);
	}

	private LayerTargetConfigResponseDto.LayerTargetProfileDto resolveRunProfile(
			LayerTargetConfigResponseDto config,
			String selectedProfile) {
		if (config == null || config.getProfiles() == null || config.getProfiles().isEmpty()) {
			return null;
		}
		LayerTargetConfigResponseDto.LayerTargetProfileDto profile = config.getProfiles().get(selectedProfile);
		if (profile != null) {
			return profile;
		}
		profile = config.getProfiles().get(config.getActiveProfileKey());
		if (profile != null) {
			return profile;
		}
		return config.getProfiles().values().iterator().next();
	}

	private boolean shouldApplyCustomOverrides(LayerTargetConfigResponseDto config, String selectedProfile) {
		if (config == null || selectedProfile == null || config.getActiveProfileKey() == null) {
			return false;
		}
		return config.isCustomOverridesEnabled() && selectedProfile.equalsIgnoreCase(config.getActiveProfileKey());
	}

	private Map<Integer, Double> resolveLayerTargets(LayerTargetConfigResponseDto config,
										 LayerTargetConfigResponseDto.LayerTargetProfileDto profile,
										 boolean applyOverrides) {
		if (applyOverrides && config != null && config.getCustomLayerTargets() != null
				&& !config.getCustomLayerTargets().isEmpty()) {
			return config.getCustomLayerTargets();
		}
		if (profile == null || profile.getLayerTargets() == null) {
			return Map.of();
		}
		return profile.getLayerTargets();
	}

	private BigDecimal resolveVariance(LayerTargetConfigResponseDto config,
							 LayerTargetConfigResponseDto.LayerTargetProfileDto profile,
							 boolean applyOverrides) {
		if (applyOverrides && config != null && config.getCustomAcceptableVariancePct() != null) {
			return toBigDecimal(config.getCustomAcceptableVariancePct());
		}
		BigDecimal variance = toBigDecimal(profile == null ? null : profile.getAcceptableVariancePct());
		return variance == null ? DEFAULT_VARIANCE_PCT : variance;
	}

	private int resolveMinimumSavingPlan(LayerTargetConfigResponseDto config,
							 LayerTargetConfigResponseDto.LayerTargetProfileDto profile,
							 boolean applyOverrides) {
		Integer value = null;
		if (applyOverrides && config != null && config.getCustomMinimumSavingPlanSize() != null) {
			value = config.getCustomMinimumSavingPlanSize();
		} else if (profile != null) {
			value = profile.getMinimumSavingPlanSize();
		}
		return value == null || value < 1 ? DEFAULT_MIN_SAVING_PLAN : value;
	}

	private int resolveMinimumRebalancing(LayerTargetConfigResponseDto config,
							 LayerTargetConfigResponseDto.LayerTargetProfileDto profile,
							 boolean applyOverrides) {
		Integer value = null;
		if (applyOverrides && config != null && config.getCustomMinimumRebalancingAmount() != null) {
			value = config.getCustomMinimumRebalancingAmount();
		} else if (profile != null) {
			value = profile.getMinimumRebalancingAmount();
		}
		return value == null || value < 1 ? DEFAULT_MIN_REBALANCE : value;
	}

	private int resolveMinimumInstrument(AssessorRunRequestDto request) {
		Integer value = request == null ? null : request.minimumInstrumentAmountEur();
		return value == null || value < 1 ? DEFAULT_MIN_INSTRUMENT : value;
	}

	private RunContext buildRunContext(AssessorRunRequestDto request, RunSetup runSetup) {
		List<String> depotScope = request == null ? null : request.depotScope();
		SavingPlanSnapshot savingPlanSnapshot = loadSavingPlans(depotScope);
		HoldingsSnapshot holdings = loadHoldingsByLayer(depotScope);
		Map<String, Integer> existingInstrumentLayers =
				mergeSavingPlanInstrumentLayers(loadSnapshotInstrumentLayers(depotScope), savingPlanSnapshot);
		KbDiagnostics kbDiagnostics = buildKbDiagnostics(existingInstrumentLayers.keySet());
		AssessorGapDetectionPolicy gapDetectionPolicy =
				AssessorGapDetectionPolicy.from(request == null ? null : request.gapDetectionPolicy());
		LayerTargetRiskThresholds riskThresholds =
				resolveRiskThresholds(runSetup.selectedProfile(), runSetup.config());
		Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer =
				resolveRiskThresholdsByLayer(runSetup.selectedProfile(), runSetup.config(), riskThresholds);
		return new RunContext(
				savingPlanSnapshot,
				holdings,
				existingInstrumentLayers,
				kbDiagnostics,
				gapDetectionPolicy,
				riskThresholds,
				riskThresholdsByLayer
		);
	}

	private RunAmounts buildRunAmounts(AssessorRunRequestDto request, RunSetup runSetup) {
		BigDecimal savingPlanDelta = request == null ? null : toBigDecimal(request.savingPlanAmountDeltaEur());
		validateSavingPlanDelta(savingPlanDelta, runSetup.minimumSavingPlanSize());
		BigDecimal oneTimeAmount = request == null ? null : toBigDecimal(request.oneTimeAmountEur());
		validateOneTimeAmount(oneTimeAmount, runSetup.minimumInstrumentAmount());
		return new RunAmounts(savingPlanDelta, oneTimeAmount);
	}

	private void validateSavingPlanDelta(BigDecimal savingPlanDelta, int minimumSavingPlanSize) {
		if (savingPlanDelta == null) {
			return;
		}
		if (savingPlanDelta.signum() < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Saving plan amount delta must be zero or positive.");
		}
		if (savingPlanDelta.signum() > 0
				&& savingPlanDelta.compareTo(BigDecimal.valueOf(minimumSavingPlanSize)) < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Saving plan amount delta must be at least the minimum saving plan size.");
		}
	}

	private void validateOneTimeAmount(BigDecimal oneTimeAmount, int minimumInstrumentAmount) {
		if (oneTimeAmount == null || oneTimeAmount.signum() <= 0) {
			return;
		}
		BigDecimal minInstrumentAmount = BigDecimal.valueOf(minimumInstrumentAmount);
		if (oneTimeAmount.compareTo(minInstrumentAmount) < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"One-time amount must be at least the minimum amount per instrument.");
		}
	}

	private AssessorEngine.AssessorEngineResult runAssessorEngine(RunSetup runSetup,
											  RunContext runContext,
											  RunAmounts runAmounts,
											  boolean instrumentAllocationEnabled) {
		LayerTargetConfigResponseDto.LayerTargetProfileDto profile = runSetup.profile();
		return assessorEngine.assess(new AssessorEngine.AssessorEngineInput(
				runSetup.selectedProfile(),
				runSetup.targets(),
				runSetup.variance(),
				runSetup.minimumSavingPlanSize(),
				runSetup.minimumRebalancingAmount(),
				runSetup.minimumInstrumentAmount(),
				runSetup.projectionHorizonMonths(),
				profile == null ? null : toBigDecimal(profile.getProjectionBlendMin()),
				profile == null ? null : toBigDecimal(profile.getProjectionBlendMax()),
				runContext.savingPlanSnapshot().plans(),
				runAmounts.savingPlanDelta(),
				runAmounts.oneTimeAmount(),
				runContext.holdings().holdingsByLayer(),
				instrumentAllocationEnabled
		));
	}

	private SavingPlanPlanResult resolveSavingPlanPlan(AssessorEngine.AssessorEngineResult result,
											 List<AssessorSavingPlanSuggestionDto> suggestionDtos,
											 List<AssessorInstrumentSuggestionService.NewInstrumentSuggestion> savingPlanInstrumentSuggestions,
											 SavingPlanAllocation savingPlanAllocation) {
		if (savingPlanAllocation != null) {
			return new SavingPlanPlanResult(
					savingPlanAllocation.targetLayerAmounts(),
					savingPlanAllocation.savingPlanSuggestions(),
					savingPlanAllocation.newInstruments(),
					savingPlanAllocation.allocationNotes());
		}
		List<AssessorNewInstrumentSuggestionDto> savingPlanNewInstruments =
				toNewInstrumentSuggestionDtos(savingPlanInstrumentSuggestions, Set.of());
		List<AssessorSavingPlanSuggestionDto> adjustedSuggestions =
				applyNewInstrumentReservations(suggestionDtos, savingPlanNewInstruments);
		Map<Integer, BigDecimal> targetLayerAmounts = resolveSavingPlanTargets(result, adjustedSuggestions, savingPlanNewInstruments);
		return new SavingPlanPlanResult(targetLayerAmounts, adjustedSuggestions, savingPlanNewInstruments, List.of());
	}

	private Map<Integer, BigDecimal> resolveSavingPlanTargets(AssessorEngine.AssessorEngineResult result,
											 List<AssessorSavingPlanSuggestionDto> adjustedSuggestions,
											 List<AssessorNewInstrumentSuggestionDto> savingPlanNewInstruments) {
		Map<Integer, BigDecimal> derivedTargets = deriveTargetLayerDistribution(adjustedSuggestions);
		if (derivedTargets == null) {
			return result.targetLayerDistribution();
		}
		return applyNewInstrumentTargets(derivedTargets, savingPlanNewInstruments);
	}

	private LocalDate resolveAsOfDate(LocalDate asOfDate) {
		return asOfDate == null ? LocalDate.now() : asOfDate;
	}

	private boolean isInstrumentAssessment(AssessorRunRequestDto request) {
		if (request == null || request.assessmentType() == null) {
			return false;
		}
		String type = request.assessmentType().trim().toLowerCase(Locale.ROOT);
		return ASSESSMENT_TYPE_INSTRUMENT_ONE_TIME.equals(type)
				|| "instrument_one_time_invest".equals(type)
				|| "instrument_one_type_invest".equals(type);
	}

	private AssessorRunResponseDto runInstrumentAssessment(AssessorRunRequestDto request,
											 String selectedProfile,
											 LayerTargetConfigResponseDto config,
											 Map<Integer, BigDecimal> targets) {
		if (request == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Instrument assessment request missing.");
		}
		Integer amount = request.instrumentAmountEur();
		if (amount == null || amount < 1) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Instrument amount must be a positive integer.");
		}
		List<String> instruments = request.instruments();
		if (instruments == null || instruments.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Instrument list must not be empty.");
		}
		LayerTargetRiskThresholds riskThresholds = resolveRiskThresholds(selectedProfile, config);
		Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer =
				resolveRiskThresholdsByLayer(selectedProfile, config, riskThresholds);
		AssessorInstrumentAssessmentService.AssessmentResult assessment =
				instrumentAssessmentService.assess(instruments, amount, targets, riskThresholds, riskThresholdsByLayer);
		List<AssessorInstrumentAssessmentItemDto> itemDtos = new ArrayList<>();
		for (AssessorInstrumentAssessmentService.AssessmentItem item : assessment.items()) {
			List<AssessorInstrumentAssessmentScoreComponentDto> scoreComponents =
					toScoreComponentDtos(item.scoreComponents());
			String riskCategory = riskCategoryForScore(item.score(), item.layer(), riskThresholds, riskThresholdsByLayer);
			itemDtos.add(new AssessorInstrumentAssessmentItemDto(
					item.isin(),
					item.name(),
					item.layer(),
					item.score(),
					riskCategory,
					toAmount(item.allocation()),
					scoreComponents
			));
		}
		String narrative = null;
		if (llmEnabled && assessment.missingIsins().isEmpty() && !itemDtos.isEmpty()) {
			narrative = buildInstrumentAssessmentNarrative(assessment, config, itemDtos, riskThresholds);
		}
		AssessorInstrumentAssessmentDto instrumentAssessment = new AssessorInstrumentAssessmentDto(
				(double) amount,
				assessment.scoreCutoff(),
				new LayerTargetRiskThresholdsDto(riskThresholds.getLowMax(), riskThresholds.getHighMin()),
				toRiskThresholdsByLayerDto(riskThresholdsByLayer),
				itemDtos,
				assessment.missingIsins(),
				narrative
		);
		return new AssessorRunResponseDto(
				selectedProfile,
				LocalDate.now(),
				null,
				null,
				null,
				List.of(),
				List.of(),
				null,
				null,
				null,
				instrumentAssessment,
				null
		);
	}

	private String buildInstrumentAssessmentNarrative(
			AssessorInstrumentAssessmentService.AssessmentResult assessment,
			LayerTargetConfigResponseDto config,
			List<AssessorInstrumentAssessmentItemDto> items,
			LayerTargetRiskThresholds riskThresholds) {
		if (assessment == null || items == null || items.isEmpty()) {
			return null;
		}
		StringBuilder builder = new StringBuilder();
		String scoreCriteria = formatCriteriaList(assessment.scoreCriteria(), "the available criteria");
		builder.append("Assessment criteria: ")
				.append(scoreCriteria)
				.append(". Cutoff ")
				.append(assessment.scoreCutoff())
				.append(" (lower is better).\n");
		if (riskThresholds != null) {
			builder.append("Risk bands: low <= ")
					.append(riskThresholds.getLowMax())
					.append(", medium between, high >= ")
					.append(riskThresholds.getHighMin())
					.append(". High risk is not acceptable.\n");
		}
		Map<Integer, String> layerNames = config == null ? Map.of() : config.getLayerNames();
		builder.append("Results:\n");
		for (AssessorInstrumentAssessmentItemDto item : items) {
			if (item == null) {
				continue;
			}
			String isin = item.isin() == null ? "" : item.isin();
			String name = formatInstrumentName(item.instrumentName(), isin);
			Integer layer = item.layer();
			String layerLabel = formatLayerLabel(layer, layerNames);
			Integer score = item.score() == null ? 0 : item.score();
			String recommendation = formatRecommendationPhrase(item.riskCategory());
			String allocation = formatAmountValue(item.allocation());
			String breakdown = formatScoreComponentBreakdown(item.scoreComponents());
			builder.append("- ")
					.append(name)
					.append(" (")
					.append(isin)
					.append(") ")
					.append(layerLabel)
					.append(": score ")
					.append(score)
					.append(" (")
					.append(recommendation)
					.append("), allocation ")
					.append(allocation)
					.append(" EUR; breakdown: ")
					.append(breakdown)
					.append("\n");
		}
		String allocationCriteria = formatCriteriaList(assessment.allocationCriteria(), "the available allocation rules");
		String layerBudgets = formatLayerBudgetSummary(assessment.layerBudgets(), layerNames);
		builder.append("Allocation: ")
				.append(allocationCriteria)
				.append("; budgets: ")
				.append(layerBudgets)
				.append(".");
		return builder.toString().trim();
	}

	private String formatCriteriaList(List<String> criteria, String fallback) {
		if (criteria == null || criteria.isEmpty()) {
			return fallback;
		}
		List<String> entries = new ArrayList<>();
		for (String criterion : criteria) {
			if (criterion != null && !criterion.isBlank()) {
				entries.add(criterion.trim());
			}
		}
		if (entries.isEmpty()) {
			return fallback;
		}
		if (entries.size() == 1) {
			return entries.get(0);
		}
		if (entries.size() == 2) {
			return entries.get(0) + " and " + entries.get(1);
		}
		String last = entries.remove(entries.size() - 1);
		return String.join(", ", entries) + ", and " + last;
	}

	private String formatLayerLabel(Integer layer, Map<Integer, String> layerNames) {
		if (layer == null) {
			return "Layer ?";
		}
		String defaultName = LABEL_LAYER_PREFIX + layer;
		String label = layerNames == null ? defaultName : layerNames.getOrDefault(layer, defaultName);
		if (label.equals(defaultName)) {
			return defaultName;
		}
		return defaultName + " (" + label + ")";
	}

	private String formatLayerBudgetSummary(Map<Integer, BigDecimal> layerBudgets, Map<Integer, String> layerNames) {
		Map<Integer, Double> amounts = toAmountMap(layerBudgets);
		List<String> nonZero = new ArrayList<>();
		int zeroCount = 0;
		for (Map.Entry<Integer, Double> entry : amounts.entrySet()) {
			Double value = entry.getValue();
			if (value == null || Math.abs(value) < 0.0001) {
				zeroCount++;
				continue;
			}
			String label = formatLayerLabel(entry.getKey(), layerNames);
			nonZero.add(label + " " + formatAmountValue(value) + " EUR");
		}
		if (nonZero.isEmpty()) {
			return "all layers 0 EUR";
		}
		String summary = String.join(", ", nonZero);
		if (zeroCount > 0) {
			return summary + "; remaining layers 0 EUR";
		}
		return summary;
	}

	private String formatScoreComponentBreakdown(List<AssessorInstrumentAssessmentScoreComponentDto> components) {
		if (components == null || components.isEmpty()) {
			return "none";
		}
		List<String> entries = new ArrayList<>();
		for (AssessorInstrumentAssessmentScoreComponentDto component : components) {
			if (component == null || component.criterion() == null || component.criterion().isBlank()) {
				continue;
			}
			String points = formatScorePoints(component.points());
			entries.add(component.criterion().trim() + " " + points);
		}
		return entries.isEmpty() ? "none" : String.join(", ", entries);
	}

	private String formatRecommendationPhrase(String riskCategory) {
		String normalized = normalizeRiskCategory(riskCategory);
		return switch (normalized) {
			case "high" -> "high risk (not acceptable)";
			case "medium" -> "medium risk (acceptable, warning)";
			case "low" -> "low risk (acceptable)";
			default -> "risk not specified";
		};
	}

	private String formatInstrumentName(String name, String isin) {
		if (name == null || name.isBlank()) {
			return (isin == null || isin.isBlank()) ? "Instrument" : isin;
		}
		return name.trim();
	}

	private String formatAmountValue(Double value) {
		if (value == null) {
			return "0";
		}
		BigDecimal amount = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
		return amount.toPlainString();
	}

	private String normalizeRiskCategory(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}

	private String riskCategoryForScore(int score,
							 Integer layer,
							 LayerTargetRiskThresholds fallback,
							 Map<Integer, LayerTargetRiskThresholds> thresholdsByLayer) {
		LayerTargetRiskThresholds effective = RiskThresholdsUtil.resolveForLayer(thresholdsByLayer, fallback, layer);
		double lowMax = effective.getLowMax();
		double highMin = effective.getHighMin();
		if (score >= highMin) {
			return "high";
		}
		if (score <= lowMax) {
			return "low";
		}
		return "medium";
	}

	private LayerTargetRiskThresholds resolveRiskThresholds(String profileKey, LayerTargetConfigResponseDto config) {
		LayerTargetRiskThresholdsDto dto = null;
		if (config != null && config.getProfiles() != null) {
			LayerTargetConfigResponseDto.LayerTargetProfileDto profile = config.getProfiles().get(profileKey);
			if (profile != null) {
				dto = profile.getRiskThresholds();
			}
			if (dto == null) {
				LayerTargetConfigResponseDto.LayerTargetProfileDto balanced = config.getProfiles().get(PROFILE_BALANCED);
				if (balanced != null) {
					dto = balanced.getRiskThresholds();
				}
			}
		}
		LayerTargetRiskThresholds thresholds = dto == null
				? new LayerTargetRiskThresholds(RiskThresholdsUtil.DEFAULT_LOW_MAX, RiskThresholdsUtil.DEFAULT_HIGH_MIN)
				: new LayerTargetRiskThresholds(dto.lowMax(), dto.highMin());
		return RiskThresholdsUtil.normalize(thresholds);
	}

	private Map<Integer, LayerTargetRiskThresholds> resolveRiskThresholdsByLayer(
			String profileKey,
			LayerTargetConfigResponseDto config,
			LayerTargetRiskThresholds fallback
	) {
		Map<Integer, LayerTargetRiskThresholdsDto> raw = resolveRiskThresholdsByLayerDto(profileKey, config);
		Map<Integer, LayerTargetRiskThresholds> mapped = mapRiskThresholdsByLayer(raw);
		return RiskThresholdsUtil.normalizeByLayer(mapped, fallback);
	}

	private Map<Integer, LayerTargetRiskThresholdsDto> resolveRiskThresholdsByLayerDto(
			String profileKey,
			LayerTargetConfigResponseDto config) {
		if (config == null || config.getProfiles() == null) {
			return null;
		}
		LayerTargetConfigResponseDto.LayerTargetProfileDto profile = config.getProfiles().get(profileKey);
		Map<Integer, LayerTargetRiskThresholdsDto> raw = profile == null ? null : profile.getRiskThresholdsByLayer();
		if (raw != null && !raw.isEmpty()) {
			return raw;
		}
		LayerTargetConfigResponseDto.LayerTargetProfileDto balanced = config.getProfiles().get(PROFILE_BALANCED);
		return balanced == null ? null : balanced.getRiskThresholdsByLayer();
	}

	private Map<Integer, LayerTargetRiskThresholds> mapRiskThresholdsByLayer(
			Map<Integer, LayerTargetRiskThresholdsDto> raw) {
		Map<Integer, LayerTargetRiskThresholds> mapped = new LinkedHashMap<>();
		if (raw == null || raw.isEmpty()) {
			return mapped;
		}
		for (Map.Entry<Integer, LayerTargetRiskThresholdsDto> entry : raw.entrySet()) {
			Integer layer = entry.getKey();
			LayerTargetRiskThresholdsDto dto = entry.getValue();
			if (layer == null || dto == null) {
				continue;
			}
			mapped.put(layer, new LayerTargetRiskThresholds(dto.lowMax(), dto.highMin()));
		}
		return mapped;
	}

	private Map<Integer, LayerTargetRiskThresholdsDto> toRiskThresholdsByLayerDto(
			Map<Integer, LayerTargetRiskThresholds> thresholdsByLayer) {
		Map<Integer, LayerTargetRiskThresholdsDto> mapped = new LinkedHashMap<>();
		if (thresholdsByLayer == null) {
			return mapped;
		}
		for (int layer = 1; layer <= 5; layer++) {
			LayerTargetRiskThresholds thresholds = thresholdsByLayer.get(layer);
			if (thresholds != null) {
				mapped.put(layer, new LayerTargetRiskThresholdsDto(thresholds.getLowMax(), thresholds.getHighMin()));
			}
		}
		return Map.copyOf(mapped);
	}

	private List<AssessorInstrumentAssessmentScoreComponentDto> toScoreComponentDtos(
			List<AssessorInstrumentAssessmentService.ScoreComponent> components) {
		if (components == null || components.isEmpty()) {
			return List.of();
		}
		List<AssessorInstrumentAssessmentScoreComponentDto> mapped = new ArrayList<>();
		for (AssessorInstrumentAssessmentService.ScoreComponent component : components) {
			if (component == null || component.criterion() == null || component.criterion().isBlank()) {
				continue;
			}
			mapped.add(new AssessorInstrumentAssessmentScoreComponentDto(component.criterion(), component.points()));
		}
		return mapped;
	}

	private String formatScorePoints(Double points) {
		if (points == null) {
			return "0";
		}
		BigDecimal value = BigDecimal.valueOf(points).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
		return value.toPlainString();
	}

	private SavingPlanSnapshot loadSavingPlans(List<String> depotScope) {
		List<SavingPlanListProjection> rows = savingPlanRepository.findAllWithDetails();
		List<AssessorEngine.SavingPlanItem> plans = new ArrayList<>();
		Map<PlanKey, PlanMeta> planMeta = new LinkedHashMap<>();
		Set<String> isins = new HashSet<>();
		Set<String> depotFilter = normalizeDepotScope(depotScope);
		collectActiveMonthlyPlans(rows, depotFilter, plans, planMeta, isins);
		Map<String, InstrumentMeta> instrumentMeta = loadInstrumentMetadata(isins);
		Map<PlanKey, PlanMeta> resolvedPlanMeta = resolvePlanMeta(planMeta, instrumentMeta);
		Map<String, InstrumentMeta> resolvedInstrumentMeta = resolveInstrumentMeta(instrumentMeta, resolvedPlanMeta);
		return new SavingPlanSnapshot(plans, resolvedPlanMeta, resolvedInstrumentMeta);
	}

	private void collectActiveMonthlyPlans(List<SavingPlanListProjection> rows,
											  Set<String> depotFilter,
											  List<AssessorEngine.SavingPlanItem> plans,
											  Map<PlanKey, PlanMeta> planMeta,
											  Set<String> isins) {
		for (SavingPlanListProjection row : rows) {
			if (!isIncludedSavingPlanRow(row, depotFilter)) {
				continue;
			}
			String isin = normalizeIsin(row.getIsin());
			if (isin.isBlank()) {
				continue;
			}
			int layer = normalizeLayer(row.getLayer());
			plans.add(new AssessorEngine.SavingPlanItem(isin, row.getDepotId(), row.getAmountEur(), layer));
			isins.add(isin);
			planMeta.put(new PlanKey(isin, row.getDepotId()), new PlanMeta(row.getName(), layer, row.getDepotName()));
		}
	}

	private boolean isIncludedSavingPlanRow(SavingPlanListProjection row, Set<String> depotFilter) {
		if (row == null || !Boolean.TRUE.equals(row.getActive())) {
			return false;
		}
		String frequency = row.getFrequency();
		if (frequency == null || !frequency.trim().equalsIgnoreCase("monthly")) {
			return false;
		}
		if (depotFilter == null || depotFilter.isEmpty()) {
			return true;
		}
		String depotCode = row.getDepotCode();
		return depotCode != null && depotFilter.contains(depotCode.toLowerCase(Locale.ROOT));
	}

	private Map<PlanKey, PlanMeta> resolvePlanMeta(Map<PlanKey, PlanMeta> planMeta,
											 Map<String, InstrumentMeta> instrumentMeta) {
		Map<PlanKey, PlanMeta> resolved = new LinkedHashMap<>();
		for (Map.Entry<PlanKey, PlanMeta> entry : planMeta.entrySet()) {
			InstrumentMeta meta = instrumentMeta.get(entry.getKey().isin());
			String name = meta != null && meta.name() != null && !meta.name().isBlank()
					? meta.name()
					: entry.getValue().instrumentName();
			int layer = meta != null ? meta.layer() : entry.getValue().layer();
			resolved.put(entry.getKey(), new PlanMeta(name, layer, entry.getValue().depotName()));
		}
		return resolved;
	}

	private Map<String, InstrumentMeta> resolveInstrumentMeta(Map<String, InstrumentMeta> instrumentMeta,
												  Map<PlanKey, PlanMeta> resolvedPlanMeta) {
		Map<String, InstrumentMeta> resolved = new LinkedHashMap<>(instrumentMeta);
		for (Map.Entry<PlanKey, PlanMeta> entry : resolvedPlanMeta.entrySet()) {
			resolved.putIfAbsent(entry.getKey().isin(),
					new InstrumentMeta(entry.getValue().instrumentName(), entry.getValue().layer()));
		}
		return resolved;
	}

	private HoldingsSnapshot loadHoldingsByLayer(List<String> depotScope) {
		Set<String> depots = normalizeDepotScope(depotScope);
		boolean depotsEmpty = depots.isEmpty();
		if (depotsEmpty) {
			depots = Set.of("__all__");
		}
		String sql = """
				select sp.as_of_date as as_of_date,
				       ie.layer as layer,
				       sum(sp.value_eur) as value_eur
				from snapshot_positions_effective sp
				join instruments_effective ie on ie.isin = sp.isin
				where (:depotsEmpty = true or sp.depot_code in (:depots))
				group by sp.as_of_date, ie.layer
				""";
		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("depotsEmpty", depotsEmpty);
		params.addValue("depots", depots);

		Map<Integer, BigDecimal> holdings = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			holdings.put(layer, BigDecimal.ZERO);
		}
		final LocalDate[] maxDate = {null};
		final boolean[] hasRows = {false};
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			LocalDate date = rs.getDate("as_of_date") == null ? null : rs.getDate("as_of_date").toLocalDate();
			Integer layer = rs.getObject(COLUMN_LAYER) == null ? null : rs.getInt(COLUMN_LAYER);
			BigDecimal value = rs.getObject("value_eur") == null ? BigDecimal.ZERO : rs.getBigDecimal("value_eur");
			if (layer == null) {
				return;
			}
			hasRows[0] = true;
			holdings.put(layer, holdings.getOrDefault(layer, BigDecimal.ZERO).add(value));
			if (date != null && (maxDate[0] == null || date.isAfter(maxDate[0]))) {
				maxDate[0] = date;
			}
		});
		if (!hasRows[0]) {
			return new HoldingsSnapshot(null, Map.of());
		}
		return new HoldingsSnapshot(maxDate[0], holdings);
	}

	private boolean shouldAllocateInstruments(BigDecimal oneTimeAmount, List<AssessorEngine.SavingPlanItem> plans) {
		if (oneTimeAmount == null || oneTimeAmount.signum() <= 0) {
			return false;
		}
		return hasCompleteKbCoverage(collectPlanIsins(plans), false);
	}

	private Set<String> collectPlanIsins(List<AssessorEngine.SavingPlanItem> plans) {
		Set<String> isins = new HashSet<>();
		if (plans == null) {
			return isins;
		}
		for (AssessorEngine.SavingPlanItem plan : plans) {
			if (plan != null && plan.isin() != null && !plan.isin().isBlank()) {
				isins.add(plan.isin().trim().toUpperCase(Locale.ROOT));
			}
		}
		return isins;
	}

	private Set<String> collectSuggestionIsins(List<AssessorInstrumentSuggestionService.NewInstrumentSuggestion> suggestions) {
		Set<String> isins = new HashSet<>();
		if (suggestions == null) {
			return isins;
		}
		for (AssessorInstrumentSuggestionService.NewInstrumentSuggestion suggestion : suggestions) {
			if (suggestion == null || suggestion.isin() == null || suggestion.isin().isBlank()) {
				continue;
			}
			isins.add(suggestion.isin().trim().toUpperCase(Locale.ROOT));
		}
		return isins;
	}

	private boolean hasCompleteKbCoverage(Set<String> isins, boolean allowEmpty) {
		if (properties.kb() == null || !properties.kb().enabled()) {
			return false;
		}
		if (isins == null || isins.isEmpty()) {
			return allowEmpty;
		}
		String sql = """
				select isin, status
				from knowledge_base_extractions
				where isin in (:isins)
				""";
		MapSqlParameterSource params = new MapSqlParameterSource(SQL_PARAM_ISINS, isins);
		Set<String> complete = new HashSet<>();
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			String isin = rs.getString("isin");
			String status = rs.getString("status");
			if (isin == null || status == null) {
				return;
			}
			if (COMPLETE_STATUSES.contains(status.toUpperCase(Locale.ROOT))) {
				complete.add(isin.trim().toUpperCase(Locale.ROOT));
			}
		});
		return complete.containsAll(isins);
	}

	private Map<String, InstrumentMeta> loadInstrumentMetadata(Set<String> isins) {
		if (isins == null || isins.isEmpty()) {
			return Map.of();
		}
		String sql = """
				select isin, name, layer
				from instruments_effective
				where isin in (:isins)
				""";
		MapSqlParameterSource params = new MapSqlParameterSource(SQL_PARAM_ISINS, isins);
		Map<String, InstrumentMeta> metadata = new LinkedHashMap<>();
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			String isin = normalizeIsin(rs.getString("isin"));
			if (isin.isBlank()) {
				return;
			}
			String name = rs.getString("name");
			Integer layer = rs.getObject(COLUMN_LAYER) == null ? null : rs.getInt(COLUMN_LAYER);
			metadata.put(isin, new InstrumentMeta(name, normalizeLayer(layer)));
		});
		return metadata;
	}

	private Set<String> loadEffectiveInstrumentIsins(Set<String> isins) {
		Set<String> normalized = normalizeIsinSet(isins);
		if (normalized.isEmpty()) {
			return Set.of();
		}
		String sql = """
				select isin
				from instruments_effective
				where isin in (:isins)
				""";
		MapSqlParameterSource params = new MapSqlParameterSource(SQL_PARAM_ISINS, normalized);
		Set<String> effective = new HashSet<>();
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			String isin = normalizeIsin(rs.getString("isin"));
			if (!isin.isBlank()) {
				effective.add(isin);
			}
		});
		return Set.copyOf(effective);
	}

	private Map<String, Integer> loadSnapshotInstrumentLayers(List<String> depotScope) {
		Set<String> depots = normalizeDepotScope(depotScope);
		boolean depotsEmpty = depots.isEmpty();
		if (depotsEmpty) {
			depots = Set.of("__all__");
		}
		String sql = """
				select distinct sp.isin, ie.layer
				from snapshot_positions_effective sp
				join instruments_effective ie on ie.isin = sp.isin
				where (:depotsEmpty = true or sp.depot_code in (:depots))
				""";
		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("depotsEmpty", depotsEmpty);
		params.addValue("depots", depots);
		Map<String, Integer> layers = new LinkedHashMap<>();
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			String isin = normalizeIsin(rs.getString("isin"));
			Integer layer = rs.getObject(COLUMN_LAYER) == null ? null : rs.getInt(COLUMN_LAYER);
			if (!isin.isBlank()) {
				layers.put(isin, normalizeLayer(layer));
			}
		});
		return layers;
	}

	private Map<String, Integer> mergeSavingPlanInstrumentLayers(Map<String, Integer> base,
																  SavingPlanSnapshot savingPlanSnapshot) {
		Map<String, Integer> merged = new LinkedHashMap<>();
		if (base != null) {
			merged.putAll(base);
		}
		if (savingPlanSnapshot == null || savingPlanSnapshot.instrumentMeta() == null) {
			return merged;
		}
		for (Map.Entry<String, InstrumentMeta> entry : savingPlanSnapshot.instrumentMeta().entrySet()) {
			String isin = normalizeIsin(entry.getKey());
			if (isin.isBlank()) {
				continue;
			}
			InstrumentMeta meta = entry.getValue();
			Integer layer = meta == null ? null : meta.layer();
			merged.putIfAbsent(isin, normalizeLayer(layer));
		}
		return merged;
	}

	private List<AssessorInstrumentBucketDto> buildInstrumentBucketDtos(Map<String, BigDecimal> buckets,
																Map<String, InstrumentMeta> instrumentMeta) {
		if (buckets == null || buckets.isEmpty()) {
			return null;
		}
		List<AssessorInstrumentBucketDto> detailed = new ArrayList<>();
		for (Map.Entry<String, BigDecimal> entry : buckets.entrySet()) {
			AssessorInstrumentBucketDto dto = toInstrumentBucketDto(entry, instrumentMeta);
			if (dto != null) {
				detailed.add(dto);
			}
		}
		sortInstrumentBuckets(detailed);
		return List.copyOf(detailed);
	}

	private AssessorInstrumentBucketDto toInstrumentBucketDto(Map.Entry<String, BigDecimal> entry,
															 Map<String, InstrumentMeta> instrumentMeta) {
		BigDecimal amount = entry.getValue();
		if (amount == null || amount.signum() <= 0) {
			return null;
		}
		String isin = normalizeIsin(entry.getKey());
		InstrumentMeta meta = instrumentMeta == null ? null : instrumentMeta.get(isin);
		return new AssessorInstrumentBucketDto(
				isin,
				toAmount(amount),
				meta == null ? null : meta.name(),
				meta == null ? null : meta.layer()
		);
	}

	private void sortInstrumentBuckets(List<AssessorInstrumentBucketDto> buckets) {
		buckets.sort((a, b) -> {
			int layerA = a.layer() == null ? 99 : a.layer();
			int layerB = b.layer() == null ? 99 : b.layer();
			int cmp = Integer.compare(layerA, layerB);
			if (cmp != 0) {
				return cmp;
			}
			return a.isin().compareTo(b.isin());
		});
	}

	private NarrativeBundle buildNarratives(NarrativeInput input) {
		if (!llmEnabled || input == null || input.result() == null) {
			return new NarrativeBundle(null, null);
		}
		String savingPlanNarrative = buildSavingPlanNarrative(input);
		String oneTimeNarrative = buildOneTimeNarrative(input);
		return new NarrativeBundle(savingPlanNarrative, oneTimeNarrative);
	}

	private String buildSavingPlanNarrative(NarrativeInput input) {
		if (!hasSavingPlanContext(input)) {
			return null;
		}
		String prompt = buildSavingPlanNarrativePrompt(new SavingPlanNarrativeInput(
				input.result(),
				input.config(),
				input.variance(),
				input.minimumSavingPlanSize(),
				input.minimumRebalancingAmount(),
				input.projectionHorizonMonths(),
				input.targetLayerAmounts(),
				input.savingPlanSuggestions(),
				input.savingPlanNewInstruments(),
				input.savingPlanAllocationNotes(),
				input.gapDetectionPolicy()
		));
		prompt = validatePrompt(prompt, LlmPromptPurpose.SAVING_PLAN_NARRATIVE);
		return prompt == null ? null : llmNarrativeService.suggestSavingPlanNarrative(prompt);
	}

	private boolean hasSavingPlanContext(NarrativeInput input) {
		return (input.savingPlanSuggestions() != null && !input.savingPlanSuggestions().isEmpty())
				|| (input.savingPlanNewInstruments() != null && !input.savingPlanNewInstruments().isEmpty());
	}

	private String buildOneTimeNarrative(NarrativeInput input) {
		AssessorEngine.AssessorEngineResult result = input.result();
		boolean hasOneTimeBuckets = result.oneTimeAllocation() != null
				&& result.oneTimeAllocation().layerBuckets() != null
				&& !result.oneTimeAllocation().layerBuckets().isEmpty();
		boolean hasOneTimeNewInstruments = input.oneTimeNewInstruments() != null
				&& !input.oneTimeNewInstruments().isEmpty();
		if ((!hasOneTimeBuckets && !hasOneTimeNewInstruments)
				|| input.oneTimeAmount() == null || input.oneTimeAmount().signum() <= 0) {
			return null;
		}
		String prompt = buildOneTimeNarrativePrompt(result, input.config(), input.savingPlanSnapshot(),
				input.oneTimeAmount(), input.minimumInstrumentAmount(), input.oneTimeNewInstruments(),
				input.adjustedOneTimeBuckets());
		prompt = validatePrompt(prompt, LlmPromptPurpose.ONE_TIME_NARRATIVE);
		return prompt == null ? null : llmNarrativeService.suggestSavingPlanNarrative(prompt);
	}

	private String validatePrompt(String prompt, LlmPromptPurpose purpose) {
		if (llmPromptPolicy == null) {
			return prompt;
		}
		return llmPromptPolicy.validatePrompt(prompt, purpose);
	}

	private String buildSavingPlanNarrativePrompt(SavingPlanNarrativeInput input) {
		AssessorEngine.AssessorEngineResult result = input.result();
		LayerTargetConfigResponseDto config = input.config();
		StringBuilder builder = new StringBuilder();
		builder.append("Write a concise narrative (2-5 sentences) describing the savings plan proposal.\n");
		builder.append(PROMPT_RULES_HEADER);
		builder.append("- Use the provided layer names.\n");
		builder.append("- Mention whether the current distribution is within tolerance.\n");
		builder.append("- Explain that layer budgets blend projected gap weights (using effective holdings) with the current savings plan distribution; longer horizons weight the current distribution more.\n");
		builder.append("- New instrument suggestions only include low or medium risk instruments (acceptable risk).\n");
		builder.append("- Summarize the proposed actions using instrument name, ISIN, layer, and delta amounts.\n");
		builder.append("- If new_instruments is not none, add a sentence for each explaining the portfolio gap and why the specific instrument was selected, using the rationale.\n");
		builder.append("- If new_instruments is not none, explain the weighting of new instrument amounts using allocation_rules.\n");
		builder.append("- If allocation_notes is not none, mention the redistribution or gate impact using those notes.\n");
		builder.append("- If new_instruments is none, add one sentence using no_new_instruments_reason verbatim.\n");
		builder.append("- Do not invent instruments or reasons.\n");
		builder.append(PROMPT_CONTEXT_HEADER);
		builder.append("profile_key=").append(result.selectedProfile()).append("\n");
		builder.append("profile_display_name=").append(resolveProfileDisplayName(result.selectedProfile(), config)).append("\n");
		builder.append("gap_detection_policy=").append(input.gapDetectionPolicy() == null
				? AssessorGapDetectionPolicy.SAVING_PLAN_GAPS.id()
				: input.gapDetectionPolicy().id()).append("\n");
		builder.append("tolerance_pct=").append(input.variance() == null ? DEFAULT_VARIANCE_PCT : input.variance()).append("\n");
		builder.append("projection_horizon_months=")
				.append(normalizeProjectionHorizonMonths(input.projectionHorizonMonths()))
				.append("\n");
		LayerTargetConfigResponseDto.LayerTargetProfileDto profile = null;
		if (config != null && config.getProfiles() != null) {
			profile = config.getProfiles().get(result.selectedProfile());
			if (profile == null) {
				profile = config.getProfiles().get(config.getActiveProfileKey());
			}
		}
		Double projectionBlendMin = profile == null ? null : profile.getProjectionBlendMin();
		Double projectionBlendMax = profile == null ? null : profile.getProjectionBlendMax();
		builder.append("projection_blend_min=").append(projectionBlendMin).append("\n");
		builder.append("projection_blend_max=").append(projectionBlendMax).append("\n");
		builder.append("minimum_saving_plan_size_eur=").append(input.minimumSavingPlanSize()).append("\n");
		builder.append("minimum_rebalancing_amount_eur=").append(input.minimumRebalancingAmount()).append("\n");
		builder.append("allocation_rules=New instrument amounts are split evenly after reserving the minimum per instrument. ")
				.append("Any remaining euros are distributed using the weighting; if weighting does not break ties, ")
				.append("the tie is resolved alphabetically by ISIN.\n");
		builder.append("within_tolerance=").append(result.diagnostics() != null && result.diagnostics().withinTolerance()).append("\n");
		builder.append("monthly_total_eur=").append(toAmount(result.currentMonthlyTotal())).append("\n");
		builder.append(PROMPT_LAYER_NAMES).append(config == null ? Map.of() : config.getLayerNames()).append("\n");
		builder.append("current_layer_amounts_eur=").append(toAmountMap(result.currentLayerDistribution())).append("\n");
		Map<Integer, BigDecimal> targets = input.targetLayerAmounts() == null
				? result.targetLayerDistribution()
				: input.targetLayerAmounts();
		builder.append("target_layer_amounts_eur=").append(toAmountMap(targets)).append("\n");
		builder.append("allocation_notes=").append(formatAllocationNotes(input.savingPlanAllocationNotes())).append("\n");
		builder.append("allocation_notes_guidance=Use layer numbers in allocation_notes and map them to layer_names when writing the narrative.\n");
		builder.append("suggestions=\n").append(formatSuggestions(input.savingPlanSuggestions(), config)).append("\n");
		builder.append("new_instruments=\n").append(formatNewInstrumentSuggestions(input.savingPlanNewInstruments(), config)).append("\n");
		builder.append("no_new_instruments_reason=No new instruments were proposed because no qualifying gaps or budgets were identified.\n");
		return builder.toString();
	}

	private Map<Integer, BigDecimal> deriveTargetLayerDistribution(List<AssessorSavingPlanSuggestionDto> suggestions) {
		if (suggestions == null || suggestions.isEmpty()) {
			return null;
		}
		Map<Integer, BigDecimal> totals = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			totals.put(layer, BigDecimal.ZERO);
		}
		boolean hasLayerData = false;
		for (AssessorSavingPlanSuggestionDto suggestion : suggestions) {
			if (suggestion == null || suggestion.layer() == null) {
				continue;
			}
			hasLayerData = true;
			BigDecimal amount = suggestion.newAmount() == null
					? BigDecimal.ZERO
					: BigDecimal.valueOf(suggestion.newAmount());
			totals.put(suggestion.layer(), totals.get(suggestion.layer()).add(amount));
		}
		return hasLayerData ? totals : null;
	}

	private String buildOneTimeNarrativePrompt(AssessorEngine.AssessorEngineResult result,
											   LayerTargetConfigResponseDto config,
											   SavingPlanSnapshot savingPlanSnapshot,
											   BigDecimal oneTimeAmount,
											   Integer minimumInstrumentAmount,
											   List<AssessorNewInstrumentSuggestionDto> oneTimeNewInstruments,
											   Map<String, BigDecimal> adjustedOneTimeBuckets) {
		StringBuilder builder = new StringBuilder();
		builder.append("Write a concise narrative (2-4 sentences) describing the one-time allocation proposal.\n");
		builder.append(PROMPT_RULES_HEADER);
		builder.append("- Use the provided layer names.\n");
		builder.append("- Mention the total one-time amount.\n");
		builder.append("- Explain that layer amounts close gaps to the target weights using effective holdings.\n");
		builder.append("- New instrument suggestions only include low or medium risk instruments (acceptable risk).\n");
		builder.append("- Summarize the layer allocation and any instrument buckets if provided.\n");
		builder.append("- If new_instruments is not none, add a sentence for each explaining the portfolio gap and why the specific instrument was selected, using the rationale.\n");
		builder.append("- If new_instruments is none, add one sentence using no_new_instruments_reason verbatim.\n");
		builder.append("- Do not mention saving plan changes.\n");
		builder.append(PROMPT_CONTEXT_HEADER);
		builder.append("amount_eur=").append(oneTimeAmount).append("\n");
		builder.append("minimum_instrument_amount_eur=").append(minimumInstrumentAmount).append("\n");
		builder.append(PROMPT_LAYER_NAMES).append(config == null ? Map.of() : config.getLayerNames()).append("\n");
		builder.append("layer_buckets_eur=").append(toSparseAmountMap(result.oneTimeAllocation().layerBuckets())).append("\n");
		Map<String, BigDecimal> instrumentBuckets = adjustedOneTimeBuckets == null
				? result.oneTimeAllocation().instrumentBuckets()
				: adjustedOneTimeBuckets;
		builder.append("instrument_buckets=\n").append(formatInstrumentBuckets(instrumentBuckets, savingPlanSnapshot, config)).append("\n");
		builder.append("new_instruments=\n").append(formatNewInstrumentSuggestions(oneTimeNewInstruments, config)).append("\n");
		builder.append("no_new_instruments_reason=No new instruments were proposed because no qualifying portfolio gaps or budgets were identified.\n");
		return builder.toString();
	}

	private String resolveProfileDisplayName(String profileKey, LayerTargetConfigResponseDto config) {
		if (config == null || config.getProfiles() == null || profileKey == null) {
			return profileKey;
		}
		LayerTargetConfigResponseDto.LayerTargetProfileDto profile = config.getProfiles().get(profileKey);
		if (profile == null || profile.getDisplayName() == null) {
			return profileKey;
		}
		return profile.getDisplayName();
	}

	private String formatSuggestions(List<AssessorSavingPlanSuggestionDto> suggestions,
									 LayerTargetConfigResponseDto config) {
		if (suggestions == null || suggestions.isEmpty()) {
			return "none";
		}
		Map<Integer, String> layerNames = config == null ? Map.of() : config.getLayerNames();
		List<String> lines = new ArrayList<>();
		for (AssessorSavingPlanSuggestionDto suggestion : suggestions) {
			if (suggestion == null) {
				continue;
			}
			String isin = normalizeIsin(suggestion.isin());
			String instrumentName = suggestion.instrumentName();
			Integer layer = suggestion.layer();
			String layerName = layer == null ? null : layerNames.getOrDefault(layer, LABEL_LAYER_PREFIX + layer);
			lines.add(String.format("- type=%s, isin=%s, name=%s, layer=%s, layer_name=%s, old_eur=%s, new_eur=%s, delta_eur=%s, rationale=%s",
					suggestion.type(),
					isin,
					instrumentName,
					layer,
					layerName,
					suggestion.oldAmount(),
					suggestion.newAmount(),
					suggestion.delta(),
					suggestion.rationale()));
		}
		return String.join("\n", lines);
	}

	private String formatAllocationNotes(List<String> notes) {
		if (notes == null || notes.isEmpty()) {
			return "none";
		}
		List<String> lines = new ArrayList<>();
		for (String note : notes) {
			if (note == null || note.isBlank()) {
				continue;
			}
			lines.add("- " + note.trim());
		}
		return lines.isEmpty() ? "none" : String.join("\n", lines);
	}

	private String formatNewInstrumentSuggestions(List<AssessorNewInstrumentSuggestionDto> suggestions,
												  LayerTargetConfigResponseDto config) {
		if (suggestions == null || suggestions.isEmpty()) {
			return "none";
		}
		Map<Integer, String> layerNames = config == null ? Map.of() : config.getLayerNames();
		List<String> lines = new ArrayList<>();
		for (AssessorNewInstrumentSuggestionDto suggestion : suggestions) {
			if (suggestion == null) {
				continue;
			}
			String isin = normalizeIsin(suggestion.isin());
			String instrumentName = suggestion.instrumentName();
			Integer layer = suggestion.layer();
			String layerName = layer == null ? null : layerNames.getOrDefault(layer, LABEL_LAYER_PREFIX + layer);
			lines.add(String.format("- isin=%s, name=%s, layer=%s, layer_name=%s, amount_eur=%s, rationale=%s",
					isin,
					instrumentName,
					layer,
					layerName,
					toAmount(BigDecimal.valueOf(suggestion.amount() == null ? 0 : suggestion.amount())),
					suggestion.rationale()));
		}
		return lines.isEmpty() ? "none" : String.join("\n", lines);
	}

	private String formatInstrumentBuckets(Map<String, BigDecimal> buckets,
										   SavingPlanSnapshot savingPlanSnapshot,
										   LayerTargetConfigResponseDto config) {
		if (buckets == null || buckets.isEmpty()) {
			return "none";
		}
		Map<Integer, String> layerNames = config == null ? Map.of() : config.getLayerNames();
		List<String> lines = new ArrayList<>();
		for (Map.Entry<String, BigDecimal> entry : buckets.entrySet()) {
			if (entry.getValue() == null || entry.getValue().signum() <= 0) {
				continue;
			}
			String isin = normalizeIsin(entry.getKey());
			InstrumentMeta meta = savingPlanSnapshot.instrumentMeta().get(isin);
			String instrumentName = meta == null ? null : meta.name();
			Integer layer = meta == null ? null : meta.layer();
			String layerName = layer == null ? null : layerNames.getOrDefault(layer, LABEL_LAYER_PREFIX + layer);
			lines.add(String.format("- isin=%s, name=%s, layer=%s, layer_name=%s, amount_eur=%s",
					isin,
					instrumentName,
					layer,
					layerName,
					toAmount(entry.getValue())));
		}
		return lines.isEmpty() ? "none" : String.join("\n", lines);
	}

	private List<AssessorSavingPlanSuggestionDto> applyNewInstrumentReservations(
			List<AssessorSavingPlanSuggestionDto> suggestions,
			List<AssessorNewInstrumentSuggestionDto> newInstruments) {
		if (suggestions == null || suggestions.isEmpty()
				|| newInstruments == null || newInstruments.isEmpty()) {
			return suggestions;
		}
		Map<Integer, BigDecimal> reservedByLayer = collectReservedByLayer(newInstruments);
		if (reservedByLayer.isEmpty()) {
			return suggestions;
		}
		List<AssessorSavingPlanSuggestionDto> adjusted = new ArrayList<>(suggestions);
		for (Map.Entry<Integer, BigDecimal> entry : reservedByLayer.entrySet()) {
			applyReservationForLayer(suggestions, adjusted, entry.getKey(), entry.getValue());
		}
		return List.copyOf(adjusted);
	}

	private Map<Integer, BigDecimal> collectReservedByLayer(List<AssessorNewInstrumentSuggestionDto> suggestions) {
		Map<Integer, BigDecimal> reservedByLayer = new LinkedHashMap<>();
		for (AssessorNewInstrumentSuggestionDto suggestion : suggestions) {
			if (suggestion == null || suggestion.layer() == null || suggestion.amount() == null) {
				continue;
			}
			BigDecimal amount = toBigDecimal(suggestion.amount());
			if (amount != null && amount.signum() > 0) {
				reservedByLayer.merge(suggestion.layer(), amount, BigDecimal::add);
			}
		}
		return reservedByLayer;
	}

	private void applyReservationForLayer(List<AssessorSavingPlanSuggestionDto> suggestions,
										 List<AssessorSavingPlanSuggestionDto> adjusted,
										 Integer layer,
										 BigDecimal reserved) {
		if (layer == null || reserved == null || reserved.signum() <= 0) {
			return;
		}
		LayerDeltaCandidates layerCandidates = collectLayerDeltaCandidates(suggestions, layer);
		if (layerCandidates.totalPositive().signum() <= 0) {
			return;
		}
		BigDecimal remaining = layerCandidates.totalPositive().subtract(reserved);
		if (remaining.signum() < 0) {
			remaining = BigDecimal.ZERO;
		}
		Map<Integer, BigDecimal> updatedDeltas = allocateDeltasByWeight(layerCandidates.candidates(), remaining);
		for (DeltaCandidate candidate : layerCandidates.candidates()) {
			AssessorSavingPlanSuggestionDto original = suggestions.get(candidate.index());
			BigDecimal newDelta = updatedDeltas.getOrDefault(candidate.index(), BigDecimal.ZERO);
			adjusted.set(candidate.index(), updateSuggestionDelta(original, newDelta));
		}
	}

	private LayerDeltaCandidates collectLayerDeltaCandidates(List<AssessorSavingPlanSuggestionDto> suggestions, Integer layer) {
		List<DeltaCandidate> candidates = new ArrayList<>();
		BigDecimal totalPositive = BigDecimal.ZERO;
		for (int i = 0; i < suggestions.size(); i++) {
			AssessorSavingPlanSuggestionDto suggestion = suggestions.get(i);
			if (suggestion == null || suggestion.layer() == null || !suggestion.layer().equals(layer)) {
				continue;
			}
			BigDecimal delta = toBigDecimal(suggestion.delta());
			if (delta == null || delta.signum() <= 0) {
				continue;
			}
			candidates.add(new DeltaCandidate(i, delta));
			totalPositive = totalPositive.add(delta);
		}
		return new LayerDeltaCandidates(candidates, totalPositive);
	}

	private AssessorSavingPlanSuggestionDto updateSuggestionDelta(AssessorSavingPlanSuggestionDto original, BigDecimal newDelta) {
		BigDecimal oldAmount = toBigDecimal(original.oldAmount());
		if (oldAmount == null) {
			oldAmount = BigDecimal.ZERO;
		}
		BigDecimal newAmount = oldAmount.add(newDelta);
		String type = original.type();
		String rationale = original.rationale();
		if (newDelta.signum() == 0) {
			type = "keep";
			rationale = "Keep amount.";
			newAmount = oldAmount;
		} else if (type == null || type.isBlank() || type.equalsIgnoreCase("keep")) {
			type = PLAN_TYPE_INCREASE;
			if (rationale == null || rationale.isBlank()) {
				rationale = "Increase amount.";
			}
		}
		return new AssessorSavingPlanSuggestionDto(
				type,
				original.isin(),
				original.instrumentName(),
				original.layer(),
				original.depotId(),
				original.depotName(),
				toAmount(oldAmount),
				toAmount(newAmount),
				toAmount(newDelta),
				rationale
		);
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

	private Map<String, BigDecimal> adjustOneTimeInstrumentBuckets(AssessorEngine.OneTimeAllocation allocation,
												   SavingPlanSnapshot savingPlanSnapshot,
												   List<AssessorNewInstrumentSuggestionDto> newInstruments) {
		if (allocation == null || allocation.instrumentBuckets() == null || allocation.instrumentBuckets().isEmpty()
				|| newInstruments == null || newInstruments.isEmpty()) {
			return allocation == null ? null : allocation.instrumentBuckets();
		}
		Map<Integer, BigDecimal> reservedByLayer = collectReservedByLayer(newInstruments);
		if (reservedByLayer.isEmpty()) {
			return allocation.instrumentBuckets();
		}
		Map<String, BigDecimal> adjusted = new LinkedHashMap<>(allocation.instrumentBuckets());
		Map<Integer, List<InstrumentAmountCandidate>> candidatesByLayer = buildInstrumentCandidatesByLayer(
				allocation.instrumentBuckets(), savingPlanSnapshot);
		applyReservedLayerAdjustments(adjusted, reservedByLayer, candidatesByLayer);
		BigDecimal totalReserved = reservedByLayer.values().stream()
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal totalAllocation = sumAmounts(allocation.layerBuckets());
		BigDecimal allowedExisting = totalAllocation.subtract(totalReserved);
		if (allowedExisting.signum() < 0) {
			allowedExisting = BigDecimal.ZERO;
		}
		InstrumentTotalCandidates totalCandidates = collectInstrumentTotalCandidates(adjusted);
		BigDecimal currentExistingTotal = totalCandidates.total();
		List<InstrumentAmountCandidate> allCandidates = totalCandidates.candidates();
		if (!allCandidates.isEmpty() && currentExistingTotal.compareTo(allowedExisting) > 0) {
			Map<String, BigDecimal> updated = allocateAmountsByWeight(allCandidates, allowedExisting);
			for (InstrumentAmountCandidate candidate : allCandidates) {
				adjusted.put(candidate.isin(), updated.getOrDefault(candidate.isin(), BigDecimal.ZERO));
			}
		}
		return Map.copyOf(adjusted);
	}

	private Map<Integer, List<InstrumentAmountCandidate>> buildInstrumentCandidatesByLayer(
			Map<String, BigDecimal> instrumentBuckets,
			SavingPlanSnapshot savingPlanSnapshot) {
		Map<Integer, List<InstrumentAmountCandidate>> candidatesByLayer = new LinkedHashMap<>();
		for (Map.Entry<String, BigDecimal> entry : instrumentBuckets.entrySet()) {
			BigDecimal amount = entry.getValue();
			if (amount == null || amount.signum() <= 0) {
				continue;
			}
			Integer layer = resolveInstrumentLayer(entry.getKey(), savingPlanSnapshot);
			if (layer != null) {
				candidatesByLayer.computeIfAbsent(layer, key -> new ArrayList<>())
						.add(new InstrumentAmountCandidate(entry.getKey(), amount));
			}
		}
		return candidatesByLayer;
	}

	private void applyReservedLayerAdjustments(Map<String, BigDecimal> adjusted,
											  Map<Integer, BigDecimal> reservedByLayer,
											  Map<Integer, List<InstrumentAmountCandidate>> candidatesByLayer) {
		for (Map.Entry<Integer, BigDecimal> entry : reservedByLayer.entrySet()) {
			Integer layer = entry.getKey();
			BigDecimal reserved = entry.getValue();
			if (layer == null || reserved == null || reserved.signum() <= 0) {
				continue;
			}
			List<InstrumentAmountCandidate> candidates = candidatesByLayer.get(layer);
			if (candidates == null || candidates.isEmpty()) {
				continue;
			}
			BigDecimal totalPositive = sumCandidateWeights(candidates);
			if (totalPositive.signum() <= 0) {
				continue;
			}
			BigDecimal remaining = totalPositive.subtract(reserved);
			if (remaining.signum() < 0) {
				remaining = BigDecimal.ZERO;
			}
			Map<String, BigDecimal> updated = allocateAmountsByWeight(candidates, remaining);
			for (InstrumentAmountCandidate candidate : candidates) {
				adjusted.put(candidate.isin(), updated.getOrDefault(candidate.isin(), BigDecimal.ZERO));
			}
		}
	}

	private BigDecimal sumCandidateWeights(List<InstrumentAmountCandidate> candidates) {
		BigDecimal total = BigDecimal.ZERO;
		for (InstrumentAmountCandidate candidate : candidates) {
			total = total.add(candidate.weight());
		}
		return total;
	}

	private BigDecimal sumAmounts(Map<Integer, BigDecimal> values) {
		if (values == null || values.isEmpty()) {
			return BigDecimal.ZERO;
		}
		BigDecimal total = BigDecimal.ZERO;
		for (BigDecimal value : values.values()) {
			if (value != null) {
				total = total.add(value);
			}
		}
		return total;
	}

	private InstrumentTotalCandidates collectInstrumentTotalCandidates(Map<String, BigDecimal> adjusted) {
		BigDecimal currentExistingTotal = BigDecimal.ZERO;
		List<InstrumentAmountCandidate> allCandidates = new ArrayList<>();
		for (Map.Entry<String, BigDecimal> entry : adjusted.entrySet()) {
			BigDecimal amount = entry.getValue();
			if (amount != null && amount.signum() > 0) {
				currentExistingTotal = currentExistingTotal.add(amount);
				allCandidates.add(new InstrumentAmountCandidate(entry.getKey(), amount));
			}
		}
		return new InstrumentTotalCandidates(currentExistingTotal, allCandidates);
	}

	private Map<Integer, BigDecimal> applyNewInstrumentTargets(Map<Integer, BigDecimal> base,
															   List<AssessorNewInstrumentSuggestionDto> newInstruments) {
		Map<Integer, BigDecimal> targets = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal value = base == null ? BigDecimal.ZERO : base.getOrDefault(layer, BigDecimal.ZERO);
			targets.put(layer, value);
		}
		if (newInstruments == null || newInstruments.isEmpty()) {
			return Map.copyOf(targets);
		}
		for (AssessorNewInstrumentSuggestionDto suggestion : newInstruments) {
			if (suggestion == null || suggestion.layer() == null || suggestion.amount() == null) {
				continue;
			}
			BigDecimal amount = toBigDecimal(suggestion.amount());
			if (amount == null || amount.signum() <= 0) {
				continue;
			}
			targets.put(suggestion.layer(), targets.getOrDefault(suggestion.layer(), BigDecimal.ZERO).add(amount));
		}
		return Map.copyOf(targets);
	}

	private Integer resolveInstrumentLayer(String isin, SavingPlanSnapshot savingPlanSnapshot) {
		if (savingPlanSnapshot == null) {
			return null;
		}
		String normalized = normalizeIsin(isin);
		if (normalized.isBlank()) {
			return null;
		}
		InstrumentMeta meta = savingPlanSnapshot.instrumentMeta().get(normalized);
		return meta == null ? null : meta.layer();
	}

	private Map<String, BigDecimal> allocateAmountsByWeight(List<InstrumentAmountCandidate> candidates,
															BigDecimal target) {
		if (candidates == null || candidates.isEmpty() || target == null || target.signum() <= 0) {
			return Map.of();
		}
		BigDecimal totalWeight = BigDecimal.ZERO;
		for (InstrumentAmountCandidate candidate : candidates) {
			totalWeight = totalWeight.add(candidate.weight());
		}
		Map<String, BigDecimal> raw = new LinkedHashMap<>();
		if (totalWeight.signum() == 0) {
			BigDecimal per = target.divide(BigDecimal.valueOf(candidates.size()), 8, RoundingMode.HALF_UP);
			for (InstrumentAmountCandidate candidate : candidates) {
				raw.put(candidate.isin(), per);
			}
		} else {
			for (InstrumentAmountCandidate candidate : candidates) {
				BigDecimal weight = candidate.weight().divide(totalWeight, 8, RoundingMode.HALF_UP);
				raw.put(candidate.isin(), target.multiply(weight));
			}
		}
		return roundByFractionByIsin(raw, target);
	}

	private Map<String, BigDecimal> roundByFractionByIsin(Map<String, BigDecimal> raw, BigDecimal total) {
		if (raw == null || raw.isEmpty()) {
			return Map.of();
		}
		BigDecimal totalRounded = total.setScale(0, RoundingMode.HALF_UP);
		Map<String, BigDecimal> rounded = new LinkedHashMap<>();
		Map<String, BigDecimal> fractions = new HashMap<>();
		BigDecimal sum = BigDecimal.ZERO;
		for (Map.Entry<String, BigDecimal> entry : raw.entrySet()) {
			BigDecimal value = entry.getValue() == null ? BigDecimal.ZERO : entry.getValue();
			BigDecimal floor = value.setScale(0, RoundingMode.FLOOR);
			BigDecimal fraction = value.subtract(floor);
			rounded.put(entry.getKey(), floor);
			fractions.put(entry.getKey(), fraction);
			sum = sum.add(floor);
		}
		int steps = totalRounded.subtract(sum).intValue();
		if (steps > 0 && !fractions.isEmpty()) {
			List<Map.Entry<String, BigDecimal>> order = new ArrayList<>(fractions.entrySet());
			order.sort(Map.Entry.<String, BigDecimal>comparingByValue().reversed());
			int index = 0;
			while (steps > 0) {
				Map.Entry<String, BigDecimal> entry = order.get(index % order.size());
				String key = entry.getKey();
				rounded.put(key, rounded.get(key).add(BigDecimal.ONE));
				steps -= 1;
				index += 1;
			}
		}
		return rounded;
	}

	private record InstrumentAmountCandidate(String isin, BigDecimal weight) {
	}

	private record LayerDeltaCandidates(List<DeltaCandidate> candidates, BigDecimal totalPositive) {
	}

	private record InstrumentTotalCandidates(BigDecimal total, List<InstrumentAmountCandidate> candidates) {
	}

	private record WeightedSuggestionContext(
			Map<Integer, BigDecimal> targets,
			Map<Integer, BigDecimal> newInstrumentTotals,
			Map<AssessorEngine.PlanKey, BigDecimal> planWeights,
			BigDecimal minimumRebalancing,
			BigDecimal minimumSavingPlanSize,
			Map<AssessorEngine.PlanKey, AssessorEngine.SavingPlanItem> planMap) {
	}

	private record SavingPlanAllocationContext(
			AssessorEngine.AssessorEngineResult result,
			SavingPlanSnapshot snapshot,
			Map<Integer, BigDecimal> baseTargets,
			Map<Integer, BigDecimal> currentAmounts,
			Map<Integer, Integer> planCounts,
			Map<Integer, Integer> maxSavingPlans,
			int minSaving,
			int minRebalance,
			Set<String> existingCoverageIsins) {
	}

	private record AllocationLoopResult(
			Map<Integer, BigDecimal> targetAmounts,
			AssessorInstrumentSuggestionService.SuggestionResult suggestionResult,
			List<String> allocationNotes) {
	}

	private record LayerBudgetOutcome(
			Map<Integer, BigDecimal> targetAmounts,
			boolean changed) {
	}

	private String normalizeIsin(String isin) {
		if (isin == null) {
			return "";
		}
		return isin.trim().toUpperCase(Locale.ROOT);
	}

	private int normalizeLayer(Integer layer) {
		if (layer == null || layer < 1 || layer > 5) {
			return 5;
		}
		return layer;
	}

	private Set<String> normalizeDepotScope(List<String> depotScope) {
		Set<String> normalized = new HashSet<>();
		if (depotScope == null) {
			return normalized;
		}
		for (String depot : depotScope) {
			if (depot == null || depot.isBlank()) {
				continue;
			}
			normalized.add(depot.trim().toLowerCase(Locale.ROOT));
		}
		return normalized;
	}

	private AssessorDiagnosticsDto toDiagnosticsDto(AssessorEngine.Diagnostics diagnostics,
									 KbDiagnostics kbDiagnostics,
									 List<String> riskWarnings) {
		boolean kbEnabled = kbDiagnostics != null && kbDiagnostics.enabled();
		boolean kbComplete = kbDiagnostics != null && kbDiagnostics.complete();
		List<String> missingIsins = kbDiagnostics == null ? List.of() : kbDiagnostics.missingIsins();
		if (diagnostics == null) {
			return new AssessorDiagnosticsDto(false, 0, null, List.of(),
					riskWarnings == null ? List.of() : riskWarnings, kbEnabled, kbComplete, missingIsins);
		}
		return new AssessorDiagnosticsDto(
				diagnostics.withinTolerance(),
				diagnostics.suppressedDeltasCount(),
				toAmount(diagnostics.suppressedAmountTotal()),
				diagnostics.redistributionNotes(),
				riskWarnings == null ? List.of() : riskWarnings,
				kbEnabled,
				kbComplete,
				missingIsins
		);
	}

	private List<String> buildRiskWarnings(SavingPlanSnapshot savingPlanSnapshot,
								   LayerTargetRiskThresholds riskThresholds,
								   Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer,
								   KbDiagnostics kbDiagnostics) {
		if (!isRiskWarningEligible(savingPlanSnapshot, kbDiagnostics)) {
			return List.of();
		}
		LayerTargetRiskThresholds thresholds = RiskThresholdsUtil.normalize(riskThresholds);
		Map<Integer, LayerTargetRiskThresholds> thresholdsByLayer =
				RiskThresholdsUtil.normalizeByLayer(riskThresholdsByLayer, thresholds);
		Set<String> isins = collectSavingPlanIsins(savingPlanSnapshot);
		if (isins.isEmpty()) {
			return List.of();
		}
		Map<String, Integer> scores = instrumentAssessmentService.assessScores(isins, thresholds, thresholdsByLayer);
		if (scores.isEmpty()) {
			return List.of();
		}
		Map<String, InstrumentMeta> meta = savingPlanSnapshot.instrumentMeta() == null
				? Map.of()
				: savingPlanSnapshot.instrumentMeta();
		return buildRiskWarningMessages(isins, scores, thresholds, thresholdsByLayer, meta);
	}

	private boolean isRiskWarningEligible(SavingPlanSnapshot savingPlanSnapshot, KbDiagnostics kbDiagnostics) {
		return savingPlanSnapshot != null
				&& savingPlanSnapshot.plans() != null
				&& !savingPlanSnapshot.plans().isEmpty()
				&& kbDiagnostics != null
				&& kbDiagnostics.complete();
	}

	private Set<String> collectSavingPlanIsins(SavingPlanSnapshot savingPlanSnapshot) {
		Set<String> isins = new LinkedHashSet<>();
		for (AssessorEngine.SavingPlanItem plan : savingPlanSnapshot.plans()) {
			if (plan != null && plan.isin() != null) {
				isins.add(normalizeIsin(plan.isin()));
			}
		}
		return isins;
	}

	private List<String> buildRiskWarningMessages(Set<String> isins,
											Map<String, Integer> scores,
											LayerTargetRiskThresholds thresholds,
											Map<Integer, LayerTargetRiskThresholds> thresholdsByLayer,
											Map<String, InstrumentMeta> meta) {
		List<String> warnings = new ArrayList<>();
		for (String isin : isins) {
			Integer score = scores.get(isin);
			InstrumentRiskMeta riskMeta = resolveInstrumentRiskMeta(isin, meta);
			Integer layer = riskMeta.layer();
			LayerTargetRiskThresholds layerThresholds =
					RiskThresholdsUtil.resolveForLayer(thresholdsByLayer, thresholds, layer);
			double cutoff = layerThresholds.getHighMin();
			if (score == null || score < cutoff) {
				continue;
			}
			String name = riskMeta.name();
			if (layer == null) {
				warnings.add(String.format("Existing saving plan instrument %s (%s) exceeds acceptable risk for the profile (score %d >= %d).",
						name, isin, score, (int) Math.ceil(cutoff)));
			} else {
				warnings.add(String.format("Existing saving plan instrument %s (%s) in Layer %d exceeds acceptable risk for the profile (score %d >= %d).",
						name, isin, layer, score, (int) Math.ceil(cutoff)));
			}
		}
		return warnings;
	}

	private InstrumentRiskMeta resolveInstrumentRiskMeta(String isin, Map<String, InstrumentMeta> meta) {
		InstrumentMeta instrumentMeta = meta.get(isin);
		if (instrumentMeta == null) {
			return new InstrumentRiskMeta(isin, null);
		}
		String name = instrumentMeta.name() == null || instrumentMeta.name().isBlank()
				? isin
				: instrumentMeta.name();
		return new InstrumentRiskMeta(name, instrumentMeta.layer());
	}

	private KbDiagnostics buildKbDiagnostics(Set<String> isins) {
		boolean enabled = properties.kb() != null && properties.kb().enabled();
		Set<String> normalized = normalizeIsinSet(isins);
		if (!enabled) {
			return new KbDiagnostics(false, false, toSortedList(normalized));
		}
		if (normalized.isEmpty()) {
			return new KbDiagnostics(true, true, List.of());
		}
		String sql = """
				select isin, status
				from knowledge_base_extractions
				where isin in (:isins)
				""";
		MapSqlParameterSource params = new MapSqlParameterSource(SQL_PARAM_ISINS, normalized);
		Set<String> complete = new HashSet<>();
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			String isin = rs.getString("isin");
			String status = rs.getString("status");
			if (isin == null || status == null) {
				return;
			}
			if (COMPLETE_STATUSES.contains(status.toUpperCase(Locale.ROOT))) {
				complete.add(isin.trim().toUpperCase(Locale.ROOT));
			}
		});
		Set<String> missing = new HashSet<>(normalized);
		missing.removeAll(complete);
		return new KbDiagnostics(true, missing.isEmpty(), toSortedList(missing));
	}

	private Set<String> normalizeIsinSet(Set<String> isins) {
		Set<String> normalized = new HashSet<>();
		if (isins == null) {
			return normalized;
		}
		for (String isin : isins) {
			String value = normalizeIsin(isin);
			if (!value.isBlank()) {
				normalized.add(value);
			}
		}
		return normalized;
	}

	private List<String> toSortedList(Set<String> values) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		List<String> items = new ArrayList<>(values);
		items.sort(String::compareTo);
		return List.copyOf(items);
	}

	private AssessorOneTimeAllocationDto toOneTimeDto(AssessorEngine.OneTimeAllocation allocation,
													 SavingPlanSnapshot savingPlanSnapshot,
													 List<AssessorNewInstrumentSuggestionDto> newInstruments,
													 Map<String, BigDecimal> adjustedInstrumentBuckets) {
		if (allocation == null) {
			return null;
		}
		Map<String, BigDecimal> instrumentBuckets = adjustedInstrumentBuckets == null
				? allocation.instrumentBuckets()
				: adjustedInstrumentBuckets;
		List<AssessorInstrumentBucketDto> detailedBuckets = buildInstrumentBucketDtos(instrumentBuckets,
				savingPlanSnapshot.instrumentMeta());
		return new AssessorOneTimeAllocationDto(
				toSparseAmountMap(allocation.layerBuckets()),
				toInstrumentAmountMap(instrumentBuckets),
				detailedBuckets,
				newInstruments == null ? List.of() : List.copyOf(newInstruments)
		);
	}

	private AssessorInstrumentSuggestionService.SuggestionResult buildInstrumentSuggestions(InstrumentSuggestionInput input) {
		if (input == null || input.result() == null || input.savingPlanSnapshot() == null) {
			return AssessorInstrumentSuggestionService.SuggestionResult.empty();
		}
		if (properties.kb() == null || !properties.kb().enabled()) {
			return AssessorInstrumentSuggestionService.SuggestionResult.empty();
		}
		Map<Integer, BigDecimal> savingPlanBudgets = resolveSavingPlanBudgets(input);
		Map<Integer, BigDecimal> oneTimeBudgets = resolveOneTimeBudgets(input);
		if (savingPlanBudgets.isEmpty() && oneTimeBudgets.isEmpty()) {
			return AssessorInstrumentSuggestionService.SuggestionResult.empty();
		}
		Set<String> existingCoverageIsins = input.existingInstrumentLayers() == null
				? Set.of()
				: input.existingInstrumentLayers().keySet();
		if (input.kbDiagnostics() == null || !input.kbDiagnostics().complete()) {
			return AssessorInstrumentSuggestionService.SuggestionResult.empty();
		}
		Map<Integer, Integer> maxSavingPlans = normalizeMaxSavingPlans(
				input.config() == null ? null : input.config().getMaxSavingPlansPerLayer());
		int minSaving = input.minimumSavingPlanSize() == null ? DEFAULT_MIN_SAVING_PLAN : input.minimumSavingPlanSize();
		int minRebalance = input.minimumRebalancingAmount() == null ? DEFAULT_MIN_REBALANCE : input.minimumRebalancingAmount();
		int minInstrument = input.minimumInstrumentAmount() == null ? DEFAULT_MIN_INSTRUMENT : input.minimumInstrumentAmount();
		return instrumentSuggestionService.suggest(new AssessorInstrumentSuggestionService.SuggestionRequest(
				input.savingPlanSnapshot().plans(),
				existingCoverageIsins,
				savingPlanBudgets,
				oneTimeBudgets,
				minSaving,
				minRebalance,
				minInstrument,
				maxSavingPlans,
				Set.of(),
				input.gapDetectionPolicy(),
				input.riskThresholds(),
				input.riskThresholdsByLayer()
		));
	}

	private Map<Integer, BigDecimal> resolveSavingPlanBudgets(InstrumentSuggestionInput input) {
		if (input.savingPlanDelta() == null || input.savingPlanDelta().signum() <= 0) {
			return Map.of();
		}
		AssessorEngine.AssessorEngineResult result = input.result();
		return computeSavingPlanDeltaBudgets(
				result.targetLayerDistribution(),
				result.currentLayerDistribution(),
				result.savingPlanSuggestions(),
				input.savingPlanSnapshot());
	}

	private Map<Integer, BigDecimal> resolveOneTimeBudgets(InstrumentSuggestionInput input) {
		AssessorEngine.AssessorEngineResult result = input.result();
		if (input.oneTimeAmount() == null || input.oneTimeAmount().signum() <= 0
				|| result.oneTimeAllocation() == null || result.oneTimeAllocation().layerBuckets() == null) {
			return Map.of();
		}
		return result.oneTimeAllocation().layerBuckets();
	}

	private Map<Integer, Integer> normalizeMaxSavingPlans(Map<Integer, Integer> raw) {
		Map<Integer, Integer> values = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			Integer value = raw == null ? null : raw.get(layer);
			if (value == null || value < 1) {
				value = 17;
			}
			values.put(layer, value);
		}
		return values;
	}

	private Map<Integer, BigDecimal> computeSavingPlanDeltaBudgets(Map<Integer, BigDecimal> targetAmounts,
															   Map<Integer, BigDecimal> currentAmounts,
															   List<AssessorEngine.SavingPlanSuggestion> suggestions,
															   SavingPlanSnapshot savingPlanSnapshot) {
		if (targetAmounts == null || targetAmounts.isEmpty()) {
			return Map.of();
		}
		Map<Integer, BigDecimal> planTotals = initializeLayerTotals(currentAmounts);
		applySuggestionDeltas(planTotals, suggestions, savingPlanSnapshot);
		Map<Integer, BigDecimal> budgets = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal target = targetAmounts.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal current = planTotals.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal delta = target.subtract(current);
			if (delta.signum() > 0) {
				budgets.put(layer, delta);
			}
		}
		return budgets;
	}

	private Map<Integer, BigDecimal> initializeLayerTotals(Map<Integer, BigDecimal> currentAmounts) {
		Map<Integer, BigDecimal> totals = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal current = currentAmounts == null ? BigDecimal.ZERO : currentAmounts.getOrDefault(layer, BigDecimal.ZERO);
			totals.put(layer, current);
		}
		return totals;
	}

	private void applySuggestionDeltas(Map<Integer, BigDecimal> planTotals,
											   List<AssessorEngine.SavingPlanSuggestion> suggestions,
											   SavingPlanSnapshot savingPlanSnapshot) {
		if (suggestions == null || suggestions.isEmpty()) {
			return;
		}
		for (AssessorEngine.SavingPlanSuggestion suggestion : suggestions) {
			if (suggestion == null || suggestion.delta() == null || suggestion.delta().signum() == 0) {
				continue;
			}
			Integer layer = resolveSuggestionLayer(suggestion, savingPlanSnapshot);
			if (layer == null) {
				continue;
			}
			planTotals.put(layer, planTotals.getOrDefault(layer, BigDecimal.ZERO).add(suggestion.delta()));
		}
	}

	private SavingPlanAllocation buildSavingPlanAllocation(SavingPlanAllocationInput input) {
		if (!canBuildSavingPlanAllocation(input)) {
			return null;
		}
		SavingPlanAllocationContext context = initializeSavingPlanAllocationContext(input);
		if (context.baseTargets().isEmpty()) {
			return null;
		}
		AllocationLoopResult loopResult = runSavingPlanAllocationLoop(input, context);
		return finalizeSavingPlanAllocation(input, context, loopResult);
	}

	private boolean canBuildSavingPlanAllocation(SavingPlanAllocationInput input) {
		return input != null
				&& input.result() != null
				&& input.savingPlanSnapshot() != null
				&& input.savingPlanDelta() != null
				&& input.savingPlanDelta().signum() > 0
				&& properties.kb() != null
				&& properties.kb().enabled()
				&& input.kbDiagnostics() != null
				&& input.kbDiagnostics().complete();
	}

	private SavingPlanAllocationContext initializeSavingPlanAllocationContext(SavingPlanAllocationInput input) {
		AssessorEngine.AssessorEngineResult result = input.result();
		SavingPlanSnapshot snapshot = input.savingPlanSnapshot();
		Map<Integer, BigDecimal> baseTargets = normalizeLayerAmounts(result.targetLayerDistribution());
		Map<Integer, BigDecimal> currentAmounts = normalizeLayerAmounts(result.currentLayerDistribution());
		Map<Integer, Integer> planCounts = countSavingPlans(snapshot.plans());
		Map<Integer, Integer> maxSavingPlans = normalizeMaxSavingPlans(
				input.config() == null ? null : input.config().getMaxSavingPlansPerLayer());
		int minSaving = input.minimumSavingPlanSize() == null ? DEFAULT_MIN_SAVING_PLAN : input.minimumSavingPlanSize();
		int minRebalance = input.minimumRebalancingAmount() == null ? DEFAULT_MIN_REBALANCE : input.minimumRebalancingAmount();
		Set<String> existingCoverageIsins = input.existingInstrumentLayers() == null
				? Set.of()
				: input.existingInstrumentLayers().keySet();
		return new SavingPlanAllocationContext(result, snapshot, baseTargets, currentAmounts, planCounts,
				maxSavingPlans, minSaving, minRebalance, existingCoverageIsins);
	}

	private AllocationLoopResult runSavingPlanAllocationLoop(SavingPlanAllocationInput input,
											 SavingPlanAllocationContext context) {
		Map<Integer, BigDecimal> effectiveTargets = new LinkedHashMap<>(context.baseTargets());
		AssessorInstrumentSuggestionService.SuggestionResult suggestionResult = AssessorInstrumentSuggestionService.SuggestionResult.empty();
		List<String> allocationNotes = new ArrayList<>();
		for (int guard = 0; guard < 6; guard++) {
			LayerDeltaGateAdjustment gateAdjustment = applySavingPlanLayerGates(
					effectiveTargets,
					context.currentAmounts(),
					context.minSaving(),
					context.minRebalance());
			if (gateAdjustment.changed()) {
				effectiveTargets = gateAdjustment.targetAmounts();
				allocationNotes.addAll(gateAdjustment.notes());
			}
			Map<Integer, BigDecimal> positiveBudgets = filterPositiveBudgets(gateAdjustment.deltas());
			suggestionResult = suggestForPositiveBudgets(input, context, positiveBudgets);
			LayerBudgetOutcome budgetOutcome = processLayerBudgets(context, positiveBudgets,
					suggestionResult.savingPlanSuggestions(), effectiveTargets, allocationNotes);
			effectiveTargets = budgetOutcome.targetAmounts();
			if (!budgetOutcome.changed()) {
				break;
			}
		}
		return new AllocationLoopResult(effectiveTargets, suggestionResult, List.copyOf(allocationNotes));
	}

	private AssessorInstrumentSuggestionService.SuggestionResult suggestForPositiveBudgets(
			SavingPlanAllocationInput input,
			SavingPlanAllocationContext context,
			Map<Integer, BigDecimal> positiveBudgets) {
		if (positiveBudgets.isEmpty()) {
			return AssessorInstrumentSuggestionService.SuggestionResult.empty();
		}
		return instrumentSuggestionService.suggest(new AssessorInstrumentSuggestionService.SuggestionRequest(
				context.snapshot().plans(),
				context.existingCoverageIsins(),
				positiveBudgets,
				Map.of(),
				context.minSaving(),
				context.minRebalance(),
				DEFAULT_MIN_INSTRUMENT,
				context.maxSavingPlans(),
				Set.of(),
				input.gapDetectionPolicy(),
				input.riskThresholds(),
				input.riskThresholdsByLayer()
		));
	}

	private LayerBudgetOutcome processLayerBudgets(SavingPlanAllocationContext context,
										 Map<Integer, BigDecimal> positiveBudgets,
										 List<AssessorInstrumentSuggestionService.NewInstrumentSuggestion> suggestions,
										 Map<Integer, BigDecimal> currentTargets,
										 List<String> allocationNotes) {
		Map<Integer, BigDecimal> newInstrumentTotals = sumNewInstrumentTotals(suggestions);
		Map<Integer, BigDecimal> updatedTargets = currentTargets;
		boolean changed = false;
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal budget = positiveBudgets.getOrDefault(layer, BigDecimal.ZERO);
			if (budget.signum() <= 0) {
				continue;
			}
			LayerBudgetOutcome outcome = processSingleLayerBudget(layer, budget,
					newInstrumentTotals.getOrDefault(layer, BigDecimal.ZERO),
					context.planCounts().getOrDefault(layer, 0) > 0,
					updatedTargets,
					context.baseTargets(),
					allocationNotes);
			updatedTargets = outcome.targetAmounts();
			changed = changed || outcome.changed();
		}
		return new LayerBudgetOutcome(updatedTargets, changed);
	}

	private LayerBudgetOutcome processSingleLayerBudget(int layer,
										BigDecimal budget,
										BigDecimal used,
										boolean hasPlans,
										Map<Integer, BigDecimal> currentTargets,
										Map<Integer, BigDecimal> baseTargets,
										List<String> allocationNotes) {
		BigDecimal remainder = budget.subtract(used);
		if (hasPlans && remainder.signum() > 0) {
			appendExistingPlanAllocationNote(layer, remainder, used, allocationNotes);
		}
		if (hasPlans) {
			return new LayerBudgetOutcome(currentTargets, false);
		}
		BigDecimal shift = used.signum() == 0 ? budget : remainder;
		if (shift.signum() <= 0) {
			return new LayerBudgetOutcome(currentTargets, false);
		}
		appendRedistributionNote(layer, shift, used, allocationNotes);
		Map<Integer, BigDecimal> redistributed = redistributeBudgetToHigherLayers(layer, shift, currentTargets, baseTargets);
		return new LayerBudgetOutcome(redistributed, true);
	}

	private void appendExistingPlanAllocationNote(int layer,
										 BigDecimal remainder,
										 BigDecimal used,
										 List<String> allocationNotes) {
		if (used.signum() > 0) {
			allocationNotes.add(String.format(
					"Layer %d remaining budget %s EUR was allocated to existing saving plans (KB- and score-weighted) after new plan gates/gaps.",
					layer, toAmount(remainder)));
			return;
		}
		allocationNotes.add(String.format(
				"Layer %d budget %s EUR was allocated to existing saving plans (KB- and score-weighted) because no eligible new saving plans were proposed.",
				layer, toAmount(remainder)));
	}

	private void appendRedistributionNote(int layer,
									BigDecimal shift,
									BigDecimal used,
									List<String> allocationNotes) {
		if (used.signum() > 0) {
			allocationNotes.add(String.format(
					"Layer %d could not allocate %s EUR to new saving plans (gates/gaps); redistributed to higher layers.",
					layer, toAmount(shift)));
			return;
		}
		allocationNotes.add(String.format(
				"Layer %d had no eligible saving plan proposals; redistributed %s EUR to higher layers.",
				layer, toAmount(shift)));
	}

	private SavingPlanAllocation finalizeSavingPlanAllocation(SavingPlanAllocationInput input,
											 SavingPlanAllocationContext context,
											 AllocationLoopResult loopResult) {
		Map<Integer, BigDecimal> effectiveTargets = adjustTargetsToExpected(
				loopResult.targetAmounts(), context.result().currentMonthlyTotal());
		List<AssessorNewInstrumentSuggestionDto> newInstruments =
				toNewInstrumentSuggestionDtos(loopResult.suggestionResult().savingPlanSuggestions(), Set.of());
		Map<Integer, BigDecimal> newInstrumentTotals = sumNewInstrumentTotalsFromDtos(newInstruments);
		Map<Integer, Map<String, BigDecimal>> weightsByLayer = instrumentSuggestionService.computeSavingPlanWeights(
				new AssessorInstrumentSuggestionService.SavingPlanWeightRequest(
						context.snapshot().plans(),
						context.existingCoverageIsins(),
						input.gapDetectionPolicy()
				));
		Set<String> planIsins = collectPlanIsins(context.snapshot().plans());
		Map<String, Integer> assessmentScores = instrumentAssessmentService.assessScores(
				planIsins,
				input.riskThresholds(),
				input.riskThresholdsByLayer());
		Map<AssessorEngine.PlanKey, BigDecimal> planWeights = buildPlanWeights(
				context.snapshot().plans(),
				weightsByLayer,
				assessmentScores,
				input.riskThresholds(),
				input.riskThresholdsByLayer());
		List<AssessorEngine.SavingPlanSuggestion> weightedSuggestions = buildWeightedSavingPlanSuggestions(
				context.snapshot().plans(),
				effectiveTargets,
				newInstrumentTotals,
				planWeights,
				BigDecimal.valueOf(context.minRebalance()),
				BigDecimal.valueOf(context.minSaving()));
		List<AssessorSavingPlanSuggestionDto> suggestionDtos = toSuggestionDtos(weightedSuggestions, context.snapshot());
		return new SavingPlanAllocation(effectiveTargets, suggestionDtos, newInstruments, loopResult.allocationNotes());
	}

	private Map<Integer, BigDecimal> normalizeLayerAmounts(Map<Integer, BigDecimal> input) {
		Map<Integer, BigDecimal> output = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal value = input == null ? BigDecimal.ZERO : input.getOrDefault(layer, BigDecimal.ZERO);
			output.put(layer, value == null ? BigDecimal.ZERO : value);
		}
		return output;
	}

	private Map<Integer, BigDecimal> computeLayerDeltas(Map<Integer, BigDecimal> targets, Map<Integer, BigDecimal> current) {
		Map<Integer, BigDecimal> deltas = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal target = targets.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal existing = current.getOrDefault(layer, BigDecimal.ZERO);
			deltas.put(layer, target.subtract(existing));
		}
		return deltas;
	}

	LayerDeltaGateAdjustment applySavingPlanLayerGates(Map<Integer, BigDecimal> targets,
															   Map<Integer, BigDecimal> current,
															   int minimumSavingPlanSize,
															   int minimumRebalancingAmount) {
		Map<Integer, BigDecimal> normalizedTargets = normalizeLayerAmounts(targets);
		Map<Integer, BigDecimal> normalizedCurrent = normalizeLayerAmounts(current);
		Map<Integer, BigDecimal> deltas = computeLayerDeltas(normalizedTargets, normalizedCurrent);
		int gateValue = Math.max(minimumSavingPlanSize, minimumRebalancingAmount);
		if (gateValue <= 0) {
			return new LayerDeltaGateAdjustment(normalizedTargets, deltas, false, List.of());
		}
		Map<Integer, BigDecimal> adjustedDeltas = new LinkedHashMap<>(deltas);
		List<String> notes = new ArrayList<>();
		boolean changed = false;
		for (int layer = 5; layer >= 1; layer--) {
			BigDecimal delta = adjustedDeltas.getOrDefault(layer, BigDecimal.ZERO);
			if (delta.signum() == 0) {
				continue;
			}
			if (delta.abs().compareTo(BigDecimal.valueOf(gateValue)) >= 0) {
				continue;
			}
			BigDecimal newDelta = delta.signum() < 0 ? BigDecimal.valueOf(-gateValue) : BigDecimal.ZERO;
			BigDecimal diff = delta.subtract(newDelta);
			adjustedDeltas.put(layer, newDelta);
			if (layer > 1 && diff.signum() != 0) {
				adjustedDeltas.put(layer - 1, adjustedDeltas.getOrDefault(layer - 1, BigDecimal.ZERO).add(diff));
				notes.add(String.format(
						"Layer %d delta %s EUR was below the minimum gate %s EUR; shifted %s EUR to Layer %d.",
						layer, toAmount(delta), gateValue, toAmount(diff), layer - 1));
			} else {
				notes.add(String.format(
						"Layer %d delta %s EUR was below the minimum gate %s EUR; suppressed.",
						layer, toAmount(delta), gateValue));
			}
			changed = true;
		}
		if (!changed) {
			return new LayerDeltaGateAdjustment(normalizedTargets, deltas, false, List.of());
		}
		Map<Integer, BigDecimal> adjustedTargets = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal currentAmount = normalizedCurrent.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal delta = adjustedDeltas.getOrDefault(layer, BigDecimal.ZERO);
			adjustedTargets.put(layer, currentAmount.add(delta));
		}
		return new LayerDeltaGateAdjustment(adjustedTargets, adjustedDeltas, true, List.copyOf(notes));
	}

	private Map<Integer, BigDecimal> filterPositiveBudgets(Map<Integer, BigDecimal> deltas) {
		Map<Integer, BigDecimal> budgets = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal delta = deltas.getOrDefault(layer, BigDecimal.ZERO);
			if (delta.signum() > 0) {
				budgets.put(layer, delta);
			}
		}
		return budgets;
	}

	private Map<Integer, BigDecimal> sumNewInstrumentTotals(List<AssessorInstrumentSuggestionService.NewInstrumentSuggestion> suggestions) {
		Map<Integer, BigDecimal> totals = new LinkedHashMap<>();
		if (suggestions == null) {
			return totals;
		}
		for (AssessorInstrumentSuggestionService.NewInstrumentSuggestion suggestion : suggestions) {
			if (suggestion == null || suggestion.layer() == null || suggestion.amount() == null) {
				continue;
			}
			BigDecimal amount = suggestion.amount();
			if (amount.signum() <= 0) {
				continue;
			}
			totals.merge(suggestion.layer(), amount, BigDecimal::add);
		}
		return totals;
	}

	private Map<Integer, BigDecimal> sumNewInstrumentTotalsFromDtos(List<AssessorNewInstrumentSuggestionDto> suggestions) {
		Map<Integer, BigDecimal> totals = new LinkedHashMap<>();
		if (suggestions == null) {
			return totals;
		}
		for (AssessorNewInstrumentSuggestionDto suggestion : suggestions) {
			if (suggestion == null || suggestion.layer() == null || suggestion.amount() == null) {
				continue;
			}
			BigDecimal amount = toBigDecimal(suggestion.amount());
			if (amount == null || amount.signum() <= 0) {
				continue;
			}
			totals.merge(suggestion.layer(), amount, BigDecimal::add);
		}
		return totals;
	}

	private Map<Integer, Integer> countSavingPlans(List<AssessorEngine.SavingPlanItem> plans) {
		Map<Integer, Integer> counts = new LinkedHashMap<>();
		if (plans == null) {
			return counts;
		}
		for (AssessorEngine.SavingPlanItem plan : plans) {
			if (plan == null) {
				continue;
			}
			counts.merge(plan.layer(), 1, Integer::sum);
		}
		return counts;
	}

	private Map<Integer, BigDecimal> redistributeBudgetToHigherLayers(int fromLayer,
																	  BigDecimal amount,
																	  Map<Integer, BigDecimal> currentTargets,
																	  Map<Integer, BigDecimal> baseTargets) {
		if (amount == null || amount.signum() <= 0 || fromLayer >= 5) {
			return currentTargets;
		}
		Map<Integer, BigDecimal> updated = new LinkedHashMap<>(currentTargets);
		updated.put(fromLayer, updated.getOrDefault(fromLayer, BigDecimal.ZERO).subtract(amount));
		List<DeltaCandidate> candidates = new ArrayList<>();
		for (int layer = fromLayer + 1; layer <= 5; layer++) {
			BigDecimal weight = baseTargets.getOrDefault(layer, BigDecimal.ZERO);
			candidates.add(new DeltaCandidate(layer, weight));
		}
		Map<Integer, BigDecimal> allocations = allocateDeltasByWeight(candidates, amount);
		for (Map.Entry<Integer, BigDecimal> entry : allocations.entrySet()) {
			updated.put(entry.getKey(), updated.getOrDefault(entry.getKey(), BigDecimal.ZERO).add(entry.getValue()));
		}
		return updated;
	}

	private Map<Integer, BigDecimal> adjustTargetsToExpected(Map<Integer, BigDecimal> targets, BigDecimal expectedTotal) {
		if (targets == null || expectedTotal == null) {
			return targets;
		}
		BigDecimal total = BigDecimal.ZERO;
		for (BigDecimal value : targets.values()) {
			if (value != null) {
				total = total.add(value);
			}
		}
		BigDecimal residual = expectedTotal.subtract(total);
		if (residual.signum() == 0) {
			return targets;
		}
		Map<Integer, BigDecimal> adjusted = new LinkedHashMap<>(targets);
		int targetLayer = 1;
		for (int layer = 5; layer >= 1; layer--) {
			BigDecimal value = adjusted.getOrDefault(layer, BigDecimal.ZERO);
			if (value.signum() != 0) {
				targetLayer = layer;
				break;
			}
		}
		adjusted.put(targetLayer, adjusted.getOrDefault(targetLayer, BigDecimal.ZERO).add(residual));
		return adjusted;
	}

	private Map<AssessorEngine.PlanKey, BigDecimal> buildPlanWeights(List<AssessorEngine.SavingPlanItem> plans,
										 Map<Integer, Map<String, BigDecimal>> weightsByLayer,
										 Map<String, Integer> assessmentScores,
										 LayerTargetRiskThresholds riskThresholds,
										 Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer) {
		Map<AssessorEngine.PlanKey, BigDecimal> weights = new LinkedHashMap<>();
		if (plans == null) {
			return weights;
		}
		LayerTargetRiskThresholds thresholds = RiskThresholdsUtil.normalize(riskThresholds);
		Map<Integer, LayerTargetRiskThresholds> thresholdsByLayer =
				RiskThresholdsUtil.normalizeByLayer(riskThresholdsByLayer, thresholds);
		for (AssessorEngine.SavingPlanItem plan : plans) {
			if (plan == null) {
				continue;
			}
			String isin = normalizeIsin(plan.isin());
			if (isin.isBlank()) {
				continue;
			}
			BigDecimal weight = resolvePlanWeight(isin, plan.layer(), weightsByLayer, assessmentScores,
					thresholds, thresholdsByLayer);
			weights.put(new AssessorEngine.PlanKey(isin, plan.depotId()), weight);
		}
		return weights;
	}

	private BigDecimal resolvePlanWeight(String isin,
									int layer,
									Map<Integer, Map<String, BigDecimal>> weightsByLayer,
									Map<String, Integer> assessmentScores,
									LayerTargetRiskThresholds thresholds,
									Map<Integer, LayerTargetRiskThresholds> thresholdsByLayer) {
		Map<String, BigDecimal> layerWeights = weightsByLayer == null ? null : weightsByLayer.get(layer);
		BigDecimal baseWeight = layerWeights == null ? null : layerWeights.get(isin);
		if (baseWeight == null || baseWeight.signum() <= 0) {
			baseWeight = BigDecimal.ONE;
		}
		Integer score = assessmentScores == null ? null : assessmentScores.get(isin);
		if (score == null) {
			return baseWeight;
		}
		LayerTargetRiskThresholds layerThresholds =
				RiskThresholdsUtil.resolveForLayer(thresholdsByLayer, thresholds, layer);
		double factor = scoreWeightFactor(score, layerThresholds.getHighMin());
		return baseWeight.multiply(BigDecimal.valueOf(factor));
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

	private List<AssessorEngine.SavingPlanSuggestion> buildWeightedSavingPlanSuggestions(
			List<AssessorEngine.SavingPlanItem> plans,
			Map<Integer, BigDecimal> targets,
			Map<Integer, BigDecimal> newInstrumentTotals,
			Map<AssessorEngine.PlanKey, BigDecimal> planWeights,
			BigDecimal minimumRebalancing,
			BigDecimal minimumSavingPlanSize) {
		if (plans == null || plans.isEmpty()) {
			return List.of();
		}
		Map<AssessorEngine.PlanKey, AssessorEngine.SavingPlanItem> planMap = new LinkedHashMap<>();
		Map<Integer, List<AssessorEngine.SavingPlanItem>> byLayer = new LinkedHashMap<>();
		indexPlansByLayer(plans, planMap, byLayer);
		List<AssessorEngine.SavingPlanSuggestion> suggestions = new ArrayList<>();
		for (int layer = 1; layer <= 5; layer++) {
			List<AssessorEngine.SavingPlanItem> layerPlans = byLayer.getOrDefault(layer, List.of());
			if (layerPlans.isEmpty()) {
				continue;
			}
			appendWeightedSuggestionsForLayer(
					suggestions,
					layerPlans,
					layer,
					new WeightedSuggestionContext(
							targets,
							newInstrumentTotals,
							planWeights,
							minimumRebalancing,
							minimumSavingPlanSize,
							planMap));
		}
		suggestions.sort(Comparator.comparing(AssessorEngine.SavingPlanSuggestion::type)
				.thenComparing(AssessorEngine.SavingPlanSuggestion::isin));
		return List.copyOf(suggestions);
	}

	private void indexPlansByLayer(List<AssessorEngine.SavingPlanItem> plans,
									 Map<AssessorEngine.PlanKey, AssessorEngine.SavingPlanItem> planMap,
									 Map<Integer, List<AssessorEngine.SavingPlanItem>> byLayer) {
		for (AssessorEngine.SavingPlanItem plan : plans) {
			if (plan == null) {
				continue;
			}
			AssessorEngine.PlanKey key = new AssessorEngine.PlanKey(normalizeIsin(plan.isin()), plan.depotId());
			planMap.put(key, plan);
			byLayer.computeIfAbsent(plan.layer(), layer -> new ArrayList<>()).add(plan);
		}
	}

	private void appendWeightedSuggestionsForLayer(List<AssessorEngine.SavingPlanSuggestion> suggestions,
											 List<AssessorEngine.SavingPlanItem> layerPlans,
											 int layer,
											 WeightedSuggestionContext context) {
		BigDecimal existingTarget = computeExistingTarget(layer, context.targets(), context.newInstrumentTotals());
		List<SavingPlanDeltaAllocator.PlanInput> inputs = toPlanInputs(layerPlans, context.planWeights());
		SavingPlanDeltaAllocator.Allocation allocation = savingPlanDeltaAllocator.allocateToTarget(
				inputs,
				existingTarget,
				context.minimumRebalancing(),
				context.minimumSavingPlanSize());
		appendLayerAllocationSuggestions(suggestions, allocation, context.planMap());
	}

	private BigDecimal computeExistingTarget(int layer,
									 Map<Integer, BigDecimal> targets,
									 Map<Integer, BigDecimal> newInstrumentTotals) {
		BigDecimal target = targets.getOrDefault(layer, BigDecimal.ZERO);
		BigDecimal reserved = newInstrumentTotals.getOrDefault(layer, BigDecimal.ZERO);
		BigDecimal existingTarget = target.subtract(reserved);
		return existingTarget.signum() < 0 ? BigDecimal.ZERO : existingTarget;
	}

	private List<SavingPlanDeltaAllocator.PlanInput> toPlanInputs(List<AssessorEngine.SavingPlanItem> layerPlans,
														 Map<AssessorEngine.PlanKey, BigDecimal> planWeights) {
		List<SavingPlanDeltaAllocator.PlanInput> inputs = new ArrayList<>();
		for (AssessorEngine.SavingPlanItem plan : layerPlans) {
			if (plan == null) {
				continue;
			}
			AssessorEngine.PlanKey key = new AssessorEngine.PlanKey(normalizeIsin(plan.isin()), plan.depotId());
			BigDecimal weight = planWeights == null ? null : planWeights.get(key);
			if (weight == null || weight.signum() <= 0) {
				weight = BigDecimal.ONE;
			}
			inputs.add(new SavingPlanDeltaAllocator.PlanInput(key, safeAmount(plan.amount()), weight));
		}
		return inputs;
	}

	private void appendLayerAllocationSuggestions(List<AssessorEngine.SavingPlanSuggestion> suggestions,
												SavingPlanDeltaAllocator.Allocation allocation,
												Map<AssessorEngine.PlanKey, AssessorEngine.SavingPlanItem> planMap) {
		for (Map.Entry<AssessorEngine.PlanKey, BigDecimal> entry : allocation.deltas().entrySet()) {
			BigDecimal planDelta = entry.getValue();
			if (planDelta == null || planDelta.signum() == 0) {
				continue;
			}
			AssessorEngine.SavingPlanItem plan = planMap.get(entry.getKey());
			if (plan == null) {
				continue;
			}
			BigDecimal oldAmount = safeAmount(plan.amount());
			BigDecimal newAmount = allocation.proposedAmounts().getOrDefault(entry.getKey(), oldAmount);
			if (newAmount.compareTo(BigDecimal.ZERO) < 0) {
				newAmount = BigDecimal.ZERO;
				planDelta = newAmount.subtract(oldAmount);
			}
			String type = determinePlanType(oldAmount, newAmount);
			String rationale = buildPlanRationale(type);
			suggestions.add(new AssessorEngine.SavingPlanSuggestion(
					type,
					plan.isin(),
					plan.depotId(),
					oldAmount,
					newAmount,
					planDelta,
					rationale
			));
		}
	}

	private int normalizeProjectionHorizonMonths(Integer projectionHorizonMonths) {
		if (projectionHorizonMonths == null) {
			return 12;
		}
		if (projectionHorizonMonths < 1) {
			return 1;
		}
		if (projectionHorizonMonths > 120) {
			return 120;
		}
		return projectionHorizonMonths;
	}

	private String determinePlanType(BigDecimal oldAmount, BigDecimal newAmount) {
		if (newAmount.signum() == 0 && oldAmount.signum() > 0) {
			return "discard";
		}
		if (oldAmount.signum() == 0 && newAmount.signum() > 0) {
			return "create";
		}
		if (newAmount.compareTo(oldAmount) > 0) {
			return PLAN_TYPE_INCREASE;
		}
		if (newAmount.compareTo(oldAmount) < 0) {
			return "decrease";
		}
		return PLAN_TYPE_INCREASE;
	}

	private String buildPlanRationale(String type) {
		return switch (type) {
			case "discard" -> "Discard to avoid sub-minimum saving plan size.";
			case PLAN_TYPE_INCREASE -> "Increase to align with target layer allocation.";
			case "decrease" -> "Decrease to align with target layer allocation.";
			case "create" -> "Create to align with target layer allocation.";
			default -> "Adjust to align with target layer allocation.";
		};
	}

	private Integer resolveSuggestionLayer(AssessorEngine.SavingPlanSuggestion suggestion, SavingPlanSnapshot savingPlanSnapshot) {
		if (suggestion == null || savingPlanSnapshot == null) {
			return null;
		}
		String isin = normalizeIsin(suggestion.isin());
		PlanMeta meta = savingPlanSnapshot.planMeta().get(new PlanKey(isin, suggestion.depotId()));
		if (meta != null) {
			return meta.layer();
		}
		InstrumentMeta instrument = savingPlanSnapshot.instrumentMeta().get(isin);
		return instrument == null ? null : instrument.layer();
	}

	private List<AssessorNewInstrumentSuggestionDto> toNewInstrumentSuggestionDtos(
			List<AssessorInstrumentSuggestionService.NewInstrumentSuggestion> suggestions,
			Set<String> effectiveInstrumentIsins) {
		if (suggestions == null || suggestions.isEmpty()) {
			return List.of();
		}
		Set<String> effective = normalizeIsinSet(effectiveInstrumentIsins);
		List<AssessorNewInstrumentSuggestionDto> dtos = new ArrayList<>();
		for (AssessorInstrumentSuggestionService.NewInstrumentSuggestion suggestion : suggestions) {
			if (suggestion == null) {
				continue;
			}
			String isin = normalizeIsin(suggestion.isin());
			String action = effective.contains(isin) ? PLAN_TYPE_INCREASE : "new";
			dtos.add(new AssessorNewInstrumentSuggestionDto(
					isin,
					suggestion.name(),
					suggestion.layer(),
					toAmount(suggestion.amount()),
					action,
					suggestion.rationale()
			));
		}
		return List.copyOf(dtos);
	}

	private List<AssessorSavingPlanSuggestionDto> toSuggestionDtos(List<AssessorEngine.SavingPlanSuggestion> suggestions,
												   SavingPlanSnapshot savingPlanSnapshot) {
		List<AssessorSavingPlanSuggestionDto> dtos = new ArrayList<>();
		Map<PlanKey, PlanMeta> planMeta = savingPlanSnapshot == null ? Map.of() : savingPlanSnapshot.planMeta();
		Map<String, InstrumentMeta> instrumentMeta = savingPlanSnapshot == null ? Map.of() : savingPlanSnapshot.instrumentMeta();
		Map<PlanKey, AssessorEngine.SavingPlanSuggestion> suggestionByPlan = new LinkedHashMap<>();
		appendSuggestionDtos(suggestions, dtos, suggestionByPlan, planMeta, instrumentMeta);
		if (savingPlanSnapshot == null || savingPlanSnapshot.plans() == null || savingPlanSnapshot.plans().isEmpty()) {
			return List.copyOf(dtos);
		}
		appendMissingKeepDtos(savingPlanSnapshot.plans(), dtos, suggestionByPlan, planMeta, instrumentMeta);
		return List.copyOf(dtos);
	}

	private void appendSuggestionDtos(List<AssessorEngine.SavingPlanSuggestion> suggestions,
										 List<AssessorSavingPlanSuggestionDto> dtos,
										 Map<PlanKey, AssessorEngine.SavingPlanSuggestion> suggestionByPlan,
										 Map<PlanKey, PlanMeta> planMeta,
										 Map<String, InstrumentMeta> instrumentMeta) {
		if (suggestions == null) {
			return;
		}
		for (AssessorEngine.SavingPlanSuggestion suggestion : suggestions) {
			if (suggestion == null) {
				continue;
			}
			String isin = normalizeIsin(suggestion.isin());
			PlanKey key = new PlanKey(isin, suggestion.depotId());
			suggestionByPlan.put(key, suggestion);
			PlanMeta meta = resolvePlanMeta(key, isin, planMeta, instrumentMeta);
			dtos.add(new AssessorSavingPlanSuggestionDto(
					suggestion.type(),
					isin,
					meta == null ? null : meta.instrumentName(),
					meta == null ? null : meta.layer(),
					suggestion.depotId(),
					meta == null ? null : meta.depotName(),
					toAmount(suggestion.oldAmount()),
					toAmount(suggestion.newAmount()),
					toAmount(suggestion.delta()),
					suggestion.rationale()
			));
		}
	}

	private void appendMissingKeepDtos(List<AssessorEngine.SavingPlanItem> plans,
										 List<AssessorSavingPlanSuggestionDto> dtos,
										 Map<PlanKey, AssessorEngine.SavingPlanSuggestion> suggestionByPlan,
										 Map<PlanKey, PlanMeta> planMeta,
										 Map<String, InstrumentMeta> instrumentMeta) {
		for (AssessorEngine.SavingPlanItem plan : plans) {
			if (plan == null) {
				continue;
			}
			String isin = normalizeIsin(plan.isin());
			PlanKey key = new PlanKey(isin, plan.depotId());
			if (suggestionByPlan.containsKey(key)) {
				continue;
			}
			PlanMeta meta = resolvePlanMeta(key, isin, planMeta, instrumentMeta);
			BigDecimal amount = plan.amount() == null ? BigDecimal.ZERO : plan.amount();
			dtos.add(new AssessorSavingPlanSuggestionDto(
					"keep",
					isin,
					meta == null ? null : meta.instrumentName(),
					meta == null ? null : meta.layer(),
					plan.depotId(),
					meta == null ? null : meta.depotName(),
					toAmount(amount),
					toAmount(amount),
					toAmount(BigDecimal.ZERO),
					"Keep amount."
			));
		}
	}

	private PlanMeta resolvePlanMeta(PlanKey key,
								 String isin,
								 Map<PlanKey, PlanMeta> planMeta,
								 Map<String, InstrumentMeta> instrumentMeta) {
		PlanMeta meta = planMeta.get(key);
		if (meta != null) {
			return meta;
		}
		InstrumentMeta instrument = instrumentMeta.get(isin);
		if (instrument == null) {
			return null;
		}
		return new PlanMeta(instrument.name(), instrument.layer(), null);
	}

	private Map<Integer, Double> toAmountMap(Map<Integer, BigDecimal> input) {
		Map<Integer, Double> output = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal value = input == null ? BigDecimal.ZERO : input.getOrDefault(layer, BigDecimal.ZERO);
			output.put(layer, toAmount(value));
		}
		return output;
	}

	private Map<Integer, Double> toSparseAmountMap(Map<Integer, BigDecimal> input) {
		if (input == null) {
			return Map.of();
		}
		Map<Integer, Double> output = new LinkedHashMap<>();
		for (Map.Entry<Integer, BigDecimal> entry : input.entrySet()) {
			if (entry.getValue() == null || entry.getValue().signum() <= 0) {
				continue;
			}
			output.put(entry.getKey(), toAmount(entry.getValue()));
		}
		return output;
	}

	private Map<String, Double> toInstrumentAmountMap(Map<String, BigDecimal> input) {
		if (input == null) {
			return null;
		}
		Map<String, Double> output = new LinkedHashMap<>();
		for (Map.Entry<String, BigDecimal> entry : input.entrySet()) {
			if (entry.getValue() == null || entry.getValue().signum() <= 0) {
				continue;
			}
			output.put(normalizeIsin(entry.getKey()), toAmount(entry.getValue()));
		}
		return output;
	}

	private Map<Integer, BigDecimal> toBigDecimalMap(Map<Integer, Double> input) {
		Map<Integer, BigDecimal> output = new LinkedHashMap<>();
		if (input == null) {
			return output;
		}
		for (int layer = 1; layer <= 5; layer++) {
			Double value = input.get(layer);
			output.put(layer, value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value));
		}
		return output;
	}

	private BigDecimal toBigDecimal(Double value) {
		if (value == null) {
			return null;
		}
		return BigDecimal.valueOf(value);
	}

	private BigDecimal safeAmount(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	private Double toAmount(BigDecimal value) {
		if (value == null) {
			return null;
		}
		return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
	}

	private String resolveProfileKey(String requested, LayerTargetConfigResponseDto config) {
		String fallback = config == null ? PROFILE_BALANCED : config.getActiveProfileKey();
		if (requested == null || requested.isBlank()) {
			return fallback;
		}
		String normalized = requested.trim().toUpperCase(Locale.ROOT);
		if (config != null && config.getProfiles() != null && config.getProfiles().containsKey(normalized)) {
			return normalized;
		}
		return fallback;
	}

	private record HoldingsSnapshot(LocalDate asOfDate, Map<Integer, BigDecimal> holdingsByLayer) {
	}

	private record RunSetup(
			LayerTargetConfigResponseDto config,
			String selectedProfile,
			LayerTargetConfigResponseDto.LayerTargetProfileDto profile,
			Map<Integer, BigDecimal> targets,
			BigDecimal variance,
			int minimumSavingPlanSize,
			int minimumRebalancingAmount,
			int minimumInstrumentAmount,
			Integer projectionHorizonMonths) {
	}

	private record RunContext(
			SavingPlanSnapshot savingPlanSnapshot,
			HoldingsSnapshot holdings,
			Map<String, Integer> existingInstrumentLayers,
			KbDiagnostics kbDiagnostics,
			AssessorGapDetectionPolicy gapDetectionPolicy,
			LayerTargetRiskThresholds riskThresholds,
			Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer) {
	}

	private record RunAmounts(BigDecimal savingPlanDelta, BigDecimal oneTimeAmount) {
	}

	private record PlanKey(String isin, Long depotId) {
	}

	private record PlanMeta(String instrumentName, int layer, String depotName) {
	}

	private record InstrumentMeta(String name, int layer) {
	}

	private record SavingPlanSnapshot(List<AssessorEngine.SavingPlanItem> plans,
									  Map<PlanKey, PlanMeta> planMeta,
									  Map<String, InstrumentMeta> instrumentMeta) {
	}

	private record DeltaCandidate(int index, BigDecimal weight) {
	}

	private record SavingPlanAllocation(Map<Integer, BigDecimal> targetLayerAmounts,
										List<AssessorSavingPlanSuggestionDto> savingPlanSuggestions,
										List<AssessorNewInstrumentSuggestionDto> newInstruments,
										List<String> allocationNotes) {
	}

	private record SavingPlanPlanResult(
			Map<Integer, BigDecimal> targetLayerAmounts,
			List<AssessorSavingPlanSuggestionDto> savingPlanSuggestions,
			List<AssessorNewInstrumentSuggestionDto> newInstruments,
			List<String> allocationNotes) {
	}

	record LayerDeltaGateAdjustment(Map<Integer, BigDecimal> targetAmounts,
									Map<Integer, BigDecimal> deltas,
									boolean changed,
									List<String> notes) {
	}

	private record NarrativeBundle(String savingPlanNarrative, String oneTimeNarrative) {
	}

	private record KbDiagnostics(boolean enabled, boolean complete, List<String> missingIsins) {
	}

	private record InstrumentRiskMeta(String name, Integer layer) {
	}

	private record NarrativeInput(
			AssessorEngine.AssessorEngineResult result,
			LayerTargetConfigResponseDto config,
			SavingPlanSnapshot savingPlanSnapshot,
			BigDecimal variance,
			Integer minimumSavingPlanSize,
			Integer minimumRebalancingAmount,
			Integer projectionHorizonMonths,
			Integer minimumInstrumentAmount,
			BigDecimal oneTimeAmount,
			Map<Integer, BigDecimal> targetLayerAmounts,
			List<AssessorSavingPlanSuggestionDto> savingPlanSuggestions,
			List<AssessorNewInstrumentSuggestionDto> savingPlanNewInstruments,
			List<String> savingPlanAllocationNotes,
			List<AssessorNewInstrumentSuggestionDto> oneTimeNewInstruments,
			Map<String, BigDecimal> adjustedOneTimeBuckets,
			AssessorGapDetectionPolicy gapDetectionPolicy) {
	}

	private record SavingPlanNarrativeInput(
			AssessorEngine.AssessorEngineResult result,
			LayerTargetConfigResponseDto config,
			BigDecimal variance,
			Integer minimumSavingPlanSize,
			Integer minimumRebalancingAmount,
			Integer projectionHorizonMonths,
			Map<Integer, BigDecimal> targetLayerAmounts,
			List<AssessorSavingPlanSuggestionDto> savingPlanSuggestions,
			List<AssessorNewInstrumentSuggestionDto> savingPlanNewInstruments,
			List<String> savingPlanAllocationNotes,
			AssessorGapDetectionPolicy gapDetectionPolicy) {
	}

	private record InstrumentSuggestionInput(
			AssessorEngine.AssessorEngineResult result,
			SavingPlanSnapshot savingPlanSnapshot,
			BigDecimal savingPlanDelta,
			BigDecimal oneTimeAmount,
			Integer minimumSavingPlanSize,
			Integer minimumRebalancingAmount,
			Integer minimumInstrumentAmount,
			LayerTargetConfigResponseDto config,
			Map<String, Integer> existingInstrumentLayers,
			KbDiagnostics kbDiagnostics,
			LayerTargetRiskThresholds riskThresholds,
			Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer,
			AssessorGapDetectionPolicy gapDetectionPolicy) {
	}

	private record SavingPlanAllocationInput(
			AssessorEngine.AssessorEngineResult result,
			SavingPlanSnapshot savingPlanSnapshot,
			BigDecimal savingPlanDelta,
			Integer minimumSavingPlanSize,
			Integer minimumRebalancingAmount,
			LayerTargetConfigResponseDto config,
			Map<String, Integer> existingInstrumentLayers,
			KbDiagnostics kbDiagnostics,
			LayerTargetRiskThresholds riskThresholds,
			Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer,
			AssessorGapDetectionPolicy gapDetectionPolicy) {
	}
}
