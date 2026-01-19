package my.portfoliomanager.app.service;

import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.dto.AssessorDiagnosticsDto;
import my.portfoliomanager.app.dto.AssessorInstrumentBucketDto;
import my.portfoliomanager.app.dto.AssessorNewInstrumentSuggestionDto;
import my.portfoliomanager.app.dto.AssessorOneTimeAllocationDto;
import my.portfoliomanager.app.dto.AssessorRunRequestDto;
import my.portfoliomanager.app.dto.AssessorRunResponseDto;
import my.portfoliomanager.app.dto.AssessorSavingPlanSuggestionDto;
import my.portfoliomanager.app.dto.LayerTargetConfigResponseDto;
import my.portfoliomanager.app.repository.SavingPlanRepository;
import my.portfoliomanager.app.repository.projection.SavingPlanListProjection;
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

	private final SavingPlanRepository savingPlanRepository;
	private final LayerTargetConfigService layerTargetConfigService;
	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
	private final AppProperties properties;
	private final AssessorEngine assessorEngine;
	private final AssessorInstrumentSuggestionService instrumentSuggestionService;
	private final LlmNarrativeService llmNarrativeService;
	private final boolean llmEnabled;

	public AssessorService(SavingPlanRepository savingPlanRepository,
						   LayerTargetConfigService layerTargetConfigService,
						   NamedParameterJdbcTemplate namedParameterJdbcTemplate,
						   AppProperties properties,
						   AssessorEngine assessorEngine,
						   AssessorInstrumentSuggestionService instrumentSuggestionService,
						   LlmNarrativeService llmNarrativeService) {
		this.savingPlanRepository = savingPlanRepository;
		this.layerTargetConfigService = layerTargetConfigService;
		this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
		this.properties = properties;
		this.assessorEngine = assessorEngine;
		this.instrumentSuggestionService = instrumentSuggestionService;
		this.llmNarrativeService = llmNarrativeService;
		this.llmEnabled = llmNarrativeService != null && llmNarrativeService.isEnabled();
	}

	public AssessorRunResponseDto run(AssessorRunRequestDto request) {
		LayerTargetConfigResponseDto config = layerTargetConfigService.getConfigResponse();
		String selectedProfile = resolveProfileKey(null, config);
		LayerTargetConfigResponseDto.LayerTargetProfileDto profile = config.getProfiles().get(selectedProfile);
		if (profile == null) {
			profile = config.getProfiles().get(config.getActiveProfileKey());
		}
		if (profile == null && config.getProfiles() != null && !config.getProfiles().isEmpty()) {
			profile = config.getProfiles().values().iterator().next();
		}
		boolean applyOverrides = config.isCustomOverridesEnabled()
				&& selectedProfile.equalsIgnoreCase(config.getActiveProfileKey());

		Map<Integer, Double> targetSource = profile.getLayerTargets();
		if (applyOverrides && config.getCustomLayerTargets() != null && !config.getCustomLayerTargets().isEmpty()) {
			targetSource = config.getCustomLayerTargets();
		}
		Map<Integer, BigDecimal> targets = toBigDecimalMap(targetSource);
		Double varianceSource = applyOverrides && config.getCustomAcceptableVariancePct() != null
				? config.getCustomAcceptableVariancePct()
				: profile.getAcceptableVariancePct();
		BigDecimal variance = toBigDecimal(varianceSource);
		Integer minSavingPlan = applyOverrides && config.getCustomMinimumSavingPlanSize() != null
				? config.getCustomMinimumSavingPlanSize()
				: profile.getMinimumSavingPlanSize();
		Integer minRebalance = applyOverrides && config.getCustomMinimumRebalancingAmount() != null
				? config.getCustomMinimumRebalancingAmount()
				: profile.getMinimumRebalancingAmount();
		Integer minInstrument = request == null ? null : request.minimumInstrumentAmountEur();
		if (variance == null) {
			variance = DEFAULT_VARIANCE_PCT;
		}
		if (minSavingPlan == null || minSavingPlan < 1) {
			minSavingPlan = DEFAULT_MIN_SAVING_PLAN;
		}
		if (minRebalance == null || minRebalance < 1) {
			minRebalance = DEFAULT_MIN_REBALANCE;
		}
		if (minInstrument == null || minInstrument < 1) {
			minInstrument = DEFAULT_MIN_INSTRUMENT;
		}

		List<String> depotScope = request == null ? null : request.depotScope();
		SavingPlanSnapshot savingPlanSnapshot = loadSavingPlans(depotScope);
		List<AssessorEngine.SavingPlanItem> plans = savingPlanSnapshot.plans();
		HoldingsSnapshot holdings = loadHoldingsByLayer(depotScope);
		Map<String, Integer> existingInstrumentLayers =
				mergeSavingPlanInstrumentLayers(loadSnapshotInstrumentLayers(depotScope), savingPlanSnapshot);
		KbDiagnostics kbDiagnostics = buildKbDiagnostics(existingInstrumentLayers.keySet());
		AssessorGapDetectionPolicy gapDetectionPolicy =
				AssessorGapDetectionPolicy.from(request == null ? null : request.gapDetectionPolicy());

		BigDecimal savingPlanDelta = request == null ? null : toBigDecimal(request.savingPlanAmountDeltaEur());
		if (savingPlanDelta != null && savingPlanDelta.signum() < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Saving plan amount delta must be zero or positive.");
		}
		if (savingPlanDelta != null && savingPlanDelta.signum() > 0) {
			BigDecimal minimumSavingPlan = BigDecimal.valueOf(minSavingPlan);
			if (savingPlanDelta.compareTo(minimumSavingPlan) < 0) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Saving plan amount delta must be at least the minimum saving plan size.");
			}
		}
		BigDecimal oneTimeAmount = request == null ? null : toBigDecimal(request.oneTimeAmountEur());
		if (oneTimeAmount != null && oneTimeAmount.signum() > 0) {
			BigDecimal minInstrumentAmount = minInstrument == null ? null : BigDecimal.valueOf(minInstrument);
			if (minInstrumentAmount != null && minInstrumentAmount.signum() > 0
					&& oneTimeAmount.compareTo(minInstrumentAmount) < 0) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"One-time amount must be at least the minimum amount per instrument.");
			}
		}
		boolean instrumentAllocationEnabled = shouldAllocateInstruments(oneTimeAmount, plans);

		AssessorEngine.AssessorEngineResult result = assessorEngine.assess(new AssessorEngine.AssessorEngineInput(
				selectedProfile,
				targets,
				variance,
				minSavingPlan,
				minRebalance,
				minInstrument,
				plans,
				savingPlanDelta,
				oneTimeAmount,
				holdings.holdingsByLayer(),
				instrumentAllocationEnabled
		));

		LocalDate asOfDate = holdings.asOfDate() == null ? LocalDate.now() : holdings.asOfDate();
		List<AssessorSavingPlanSuggestionDto> suggestionDtos = toSuggestionDtos(result.savingPlanSuggestions(), savingPlanSnapshot);
		AssessorInstrumentSuggestionService.SuggestionResult instrumentSuggestions = buildInstrumentSuggestions(
				result,
				savingPlanSnapshot,
				savingPlanDelta,
				oneTimeAmount,
				minSavingPlan,
				minRebalance,
				minInstrument,
				config,
				existingInstrumentLayers,
				kbDiagnostics,
				gapDetectionPolicy);
		Set<String> oneTimeSuggestionIsins = collectSuggestionIsins(instrumentSuggestions.oneTimeSuggestions());
		Set<String> effectiveInstrumentIsins = loadEffectiveInstrumentIsins(oneTimeSuggestionIsins);
		List<AssessorNewInstrumentSuggestionDto> oneTimeNewInstruments =
				toNewInstrumentSuggestionDtos(instrumentSuggestions.oneTimeSuggestions(), effectiveInstrumentIsins);
		Map<String, BigDecimal> adjustedOneTimeBuckets =
				adjustOneTimeInstrumentBuckets(result.oneTimeAllocation(), savingPlanSnapshot, oneTimeNewInstruments);
		SavingPlanAllocation savingPlanAllocation = buildSavingPlanAllocation(result, savingPlanSnapshot, savingPlanDelta,
				minSavingPlan, minRebalance, config, existingInstrumentLayers, kbDiagnostics, gapDetectionPolicy);
		List<AssessorNewInstrumentSuggestionDto> savingPlanNewInstruments;
		List<AssessorSavingPlanSuggestionDto> adjustedSuggestions;
		Map<Integer, BigDecimal> targetLayerAmounts;
		List<String> savingPlanAllocationNotes;
		if (savingPlanAllocation == null) {
			savingPlanNewInstruments = toNewInstrumentSuggestionDtos(instrumentSuggestions.savingPlanSuggestions(), Set.of());
			adjustedSuggestions = applyNewInstrumentReservations(suggestionDtos, savingPlanNewInstruments);
			targetLayerAmounts = result.targetLayerDistribution();
			Map<Integer, BigDecimal> derivedTargets = deriveTargetLayerDistribution(adjustedSuggestions);
			if (derivedTargets != null) {
				targetLayerAmounts = applyNewInstrumentTargets(derivedTargets, savingPlanNewInstruments);
			}
			savingPlanAllocationNotes = List.of();
		} else {
			savingPlanNewInstruments = savingPlanAllocation.newInstruments();
			adjustedSuggestions = savingPlanAllocation.savingPlanSuggestions();
			targetLayerAmounts = savingPlanAllocation.targetLayerAmounts();
			savingPlanAllocationNotes = savingPlanAllocation.allocationNotes();
		}
		NarrativeBundle narratives = buildNarratives(result, config, savingPlanSnapshot, variance, minSavingPlan,
				minRebalance, minInstrument, oneTimeAmount, targetLayerAmounts, adjustedSuggestions,
				savingPlanNewInstruments, savingPlanAllocationNotes, oneTimeNewInstruments, adjustedOneTimeBuckets,
				gapDetectionPolicy);

		return new AssessorRunResponseDto(
				result.selectedProfile(),
				asOfDate,
				toAmount(result.currentMonthlyTotal()),
				toAmountMap(result.currentLayerDistribution()),
				toAmountMap(targetLayerAmounts),
				adjustedSuggestions,
				savingPlanNewInstruments,
				narratives.savingPlanNarrative(),
				toOneTimeDto(result.oneTimeAllocation(), savingPlanSnapshot, oneTimeNewInstruments, adjustedOneTimeBuckets),
				narratives.oneTimeNarrative(),
				toDiagnosticsDto(result.diagnostics(), kbDiagnostics)
		);
	}

	private SavingPlanSnapshot loadSavingPlans(List<String> depotScope) {
		List<SavingPlanListProjection> rows = savingPlanRepository.findAllWithDetails();
		List<AssessorEngine.SavingPlanItem> plans = new ArrayList<>();
		Map<PlanKey, PlanMeta> planMeta = new LinkedHashMap<>();
		Set<String> isins = new HashSet<>();
		Set<String> depotFilter = normalizeDepotScope(depotScope);
		for (SavingPlanListProjection row : rows) {
			if (row == null || !Boolean.TRUE.equals(row.getActive())) {
				continue;
			}
			String frequency = row.getFrequency();
			if (frequency == null || !frequency.trim().equalsIgnoreCase("monthly")) {
				continue;
			}
			if (!depotFilter.isEmpty() && (row.getDepotCode() == null || !depotFilter.contains(row.getDepotCode().toLowerCase(Locale.ROOT)))) {
				continue;
			}
			int layer = row.getLayer() == null ? 5 : row.getLayer();
			if (layer < 1 || layer > 5) {
				layer = 5;
			}
			String isin = normalizeIsin(row.getIsin());
			if (isin.isBlank()) {
				continue;
			}
			plans.add(new AssessorEngine.SavingPlanItem(
					isin,
					row.getDepotId(),
					row.getAmountEur(),
					layer
			));
			isins.add(isin);
			planMeta.put(new PlanKey(isin, row.getDepotId()), new PlanMeta(row.getName(), layer, row.getDepotName()));
		}
		Map<String, InstrumentMeta> instrumentMeta = loadInstrumentMetadata(isins);
		Map<PlanKey, PlanMeta> resolvedPlanMeta = new LinkedHashMap<>();
		for (Map.Entry<PlanKey, PlanMeta> entry : planMeta.entrySet()) {
			InstrumentMeta meta = instrumentMeta.get(entry.getKey().isin());
			String name = meta != null && meta.name() != null && !meta.name().isBlank()
					? meta.name()
					: entry.getValue().instrumentName();
			int layer = meta != null ? meta.layer() : entry.getValue().layer();
			resolvedPlanMeta.put(entry.getKey(), new PlanMeta(name, layer, entry.getValue().depotName()));
		}
		Map<String, InstrumentMeta> resolvedInstrumentMeta = new LinkedHashMap<>(instrumentMeta);
		for (Map.Entry<PlanKey, PlanMeta> entry : resolvedPlanMeta.entrySet()) {
			resolvedInstrumentMeta.putIfAbsent(entry.getKey().isin(),
					new InstrumentMeta(entry.getValue().instrumentName(), entry.getValue().layer()));
		}
		return new SavingPlanSnapshot(plans, resolvedPlanMeta, resolvedInstrumentMeta);
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
			Integer layer = rs.getObject("layer") == null ? null : rs.getInt("layer");
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
		MapSqlParameterSource params = new MapSqlParameterSource("isins", isins);
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
		MapSqlParameterSource params = new MapSqlParameterSource("isins", isins);
		Map<String, InstrumentMeta> metadata = new LinkedHashMap<>();
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			String isin = normalizeIsin(rs.getString("isin"));
			if (isin.isBlank()) {
				return;
			}
			String name = rs.getString("name");
			Integer layer = rs.getObject("layer") == null ? null : rs.getInt("layer");
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
		MapSqlParameterSource params = new MapSqlParameterSource("isins", normalized);
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
			Integer layer = rs.getObject("layer") == null ? null : rs.getInt("layer");
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
			if (entry.getValue() == null || entry.getValue().signum() <= 0) {
				continue;
			}
			String isin = normalizeIsin(entry.getKey());
			InstrumentMeta meta = instrumentMeta == null ? null : instrumentMeta.get(isin);
			detailed.add(new AssessorInstrumentBucketDto(
					isin,
					toAmount(entry.getValue()),
					meta == null ? null : meta.name(),
					meta == null ? null : meta.layer()
			));
		}
		detailed.sort((a, b) -> {
			int layerA = a.layer() == null ? 99 : a.layer();
			int layerB = b.layer() == null ? 99 : b.layer();
			int cmp = Integer.compare(layerA, layerB);
			if (cmp != 0) {
				return cmp;
			}
			return a.isin().compareTo(b.isin());
		});
		return List.copyOf(detailed);
	}

	private NarrativeBundle buildNarratives(AssessorEngine.AssessorEngineResult result,
											LayerTargetConfigResponseDto config,
											SavingPlanSnapshot savingPlanSnapshot,
											BigDecimal variance,
											Integer minimumSavingPlanSize,
											Integer minimumRebalancingAmount,
											Integer minimumInstrumentAmount,
											BigDecimal oneTimeAmount,
											Map<Integer, BigDecimal> targetLayerAmounts,
											List<AssessorSavingPlanSuggestionDto> savingPlanSuggestions,
											List<AssessorNewInstrumentSuggestionDto> savingPlanNewInstruments,
											List<String> savingPlanAllocationNotes,
											List<AssessorNewInstrumentSuggestionDto> oneTimeNewInstruments,
											Map<String, BigDecimal> adjustedOneTimeBuckets,
											AssessorGapDetectionPolicy gapDetectionPolicy) {
		if (!llmEnabled || result == null) {
			return new NarrativeBundle(null, null);
		}
		String savingPlanNarrative = null;
		boolean hasSavingPlanContext = (savingPlanSuggestions != null && !savingPlanSuggestions.isEmpty())
				|| (savingPlanNewInstruments != null && !savingPlanNewInstruments.isEmpty());
		if (hasSavingPlanContext) {
			String prompt = buildSavingPlanNarrativePrompt(result, config, variance,
					minimumSavingPlanSize, minimumRebalancingAmount, targetLayerAmounts, savingPlanSuggestions,
					savingPlanNewInstruments, savingPlanAllocationNotes, gapDetectionPolicy);
			savingPlanNarrative = llmNarrativeService.suggestSavingPlanNarrative(prompt);
		}
		String oneTimeNarrative = null;
		boolean hasOneTimeBuckets = result.oneTimeAllocation() != null && result.oneTimeAllocation().layerBuckets() != null
				&& !result.oneTimeAllocation().layerBuckets().isEmpty();
		boolean hasOneTimeNewInstruments = oneTimeNewInstruments != null && !oneTimeNewInstruments.isEmpty();
		if ((hasOneTimeBuckets || hasOneTimeNewInstruments)
				&& oneTimeAmount != null && oneTimeAmount.signum() > 0) {
			String prompt = buildOneTimeNarrativePrompt(result, config, savingPlanSnapshot, oneTimeAmount,
					minimumInstrumentAmount, oneTimeNewInstruments, adjustedOneTimeBuckets);
			oneTimeNarrative = llmNarrativeService.suggestSavingPlanNarrative(prompt);
		}
		return new NarrativeBundle(savingPlanNarrative, oneTimeNarrative);
	}

	private String buildSavingPlanNarrativePrompt(AssessorEngine.AssessorEngineResult result,
												 LayerTargetConfigResponseDto config,
												 BigDecimal variance,
												 Integer minimumSavingPlanSize,
												 Integer minimumRebalancingAmount,
												 Map<Integer, BigDecimal> targetLayerAmounts,
												 List<AssessorSavingPlanSuggestionDto> savingPlanSuggestions,
												 List<AssessorNewInstrumentSuggestionDto> savingPlanNewInstruments,
												 List<String> savingPlanAllocationNotes,
												 AssessorGapDetectionPolicy gapDetectionPolicy) {
		StringBuilder builder = new StringBuilder();
		builder.append("Write a concise narrative (2-5 sentences) describing the savings plan proposal.\n");
		builder.append("Rules:\n");
		builder.append("- Use the provided layer names.\n");
		builder.append("- Mention whether the current distribution is within tolerance.\n");
		builder.append("- Summarize the proposed actions using instrument name, ISIN, layer, and delta amounts.\n");
		builder.append("- If new_instruments is not none, add a sentence for each explaining the portfolio gap and why the specific instrument was selected, using the rationale.\n");
		builder.append("- If new_instruments is not none, explain the weighting of new instrument amounts using allocation_rules.\n");
		builder.append("- If allocation_notes is not none, mention the redistribution or gate impact using those notes.\n");
		builder.append("- If new_instruments is none, add one sentence using no_new_instruments_reason verbatim.\n");
		builder.append("- Do not invent instruments or reasons.\n");
		builder.append("Context:\n");
		builder.append("profile_key=").append(result.selectedProfile()).append("\n");
		builder.append("profile_display_name=").append(resolveProfileDisplayName(result.selectedProfile(), config)).append("\n");
		builder.append("gap_detection_policy=").append(gapDetectionPolicy == null
				? AssessorGapDetectionPolicy.SAVING_PLAN_GAPS.id()
				: gapDetectionPolicy.id()).append("\n");
		builder.append("tolerance_pct=").append(variance == null ? DEFAULT_VARIANCE_PCT : variance).append("\n");
		builder.append("minimum_saving_plan_size_eur=").append(minimumSavingPlanSize).append("\n");
		builder.append("minimum_rebalancing_amount_eur=").append(minimumRebalancingAmount).append("\n");
		builder.append("allocation_rules=New instrument amounts are split evenly after reserving the minimum per instrument. ")
				.append("Any remaining euros are distributed alphabetically by ISIN.\n");
		builder.append("within_tolerance=").append(result.diagnostics() != null && result.diagnostics().withinTolerance()).append("\n");
		builder.append("monthly_total_eur=").append(toAmount(result.currentMonthlyTotal())).append("\n");
		builder.append("layer_names=").append(config == null ? Map.of() : config.getLayerNames()).append("\n");
		builder.append("current_layer_amounts_eur=").append(toAmountMap(result.currentLayerDistribution())).append("\n");
		Map<Integer, BigDecimal> targets = targetLayerAmounts == null ? result.targetLayerDistribution() : targetLayerAmounts;
		builder.append("target_layer_amounts_eur=").append(toAmountMap(targets)).append("\n");
		builder.append("allocation_notes=").append(formatAllocationNotes(savingPlanAllocationNotes)).append("\n");
		builder.append("allocation_notes_guidance=Use layer numbers in allocation_notes and map them to layer_names when writing the narrative.\n");
		builder.append("suggestions=\n").append(formatSuggestions(savingPlanSuggestions, config)).append("\n");
		builder.append("new_instruments=\n").append(formatNewInstrumentSuggestions(savingPlanNewInstruments, config)).append("\n");
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
		builder.append("Rules:\n");
		builder.append("- Use the provided layer names.\n");
		builder.append("- Mention the total one-time amount.\n");
		builder.append("- Summarize the layer allocation and any instrument buckets if provided.\n");
		builder.append("- If new_instruments is not none, add a sentence for each explaining the portfolio gap and why the specific instrument was selected, using the rationale.\n");
		builder.append("- If new_instruments is none, add one sentence using no_new_instruments_reason verbatim.\n");
		builder.append("- Do not mention saving plan changes.\n");
		builder.append("Context:\n");
		builder.append("amount_eur=").append(oneTimeAmount).append("\n");
		builder.append("minimum_instrument_amount_eur=").append(minimumInstrumentAmount).append("\n");
		builder.append("layer_names=").append(config == null ? Map.of() : config.getLayerNames()).append("\n");
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
			String layerName = layer == null ? null : layerNames.getOrDefault(layer, "Layer " + layer);
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
			String layerName = layer == null ? null : layerNames.getOrDefault(layer, "Layer " + layer);
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
			String layerName = layer == null ? null : layerNames.getOrDefault(layer, "Layer " + layer);
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
		Map<Integer, BigDecimal> reservedByLayer = new LinkedHashMap<>();
		for (AssessorNewInstrumentSuggestionDto suggestion : newInstruments) {
			if (suggestion == null || suggestion.layer() == null || suggestion.amount() == null) {
				continue;
			}
			BigDecimal amount = toBigDecimal(suggestion.amount());
			if (amount == null || amount.signum() <= 0) {
				continue;
			}
			reservedByLayer.merge(suggestion.layer(), amount, BigDecimal::add);
		}
		if (reservedByLayer.isEmpty()) {
			return suggestions;
		}
		List<AssessorSavingPlanSuggestionDto> adjusted = new ArrayList<>(suggestions);
		for (Map.Entry<Integer, BigDecimal> entry : reservedByLayer.entrySet()) {
			Integer layer = entry.getKey();
			if (layer == null) {
				continue;
			}
			BigDecimal reserved = entry.getValue();
			if (reserved == null || reserved.signum() <= 0) {
				continue;
			}
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
			if (totalPositive.signum() <= 0) {
				continue;
			}
			BigDecimal remaining = totalPositive.subtract(reserved);
			if (remaining.signum() < 0) {
				remaining = BigDecimal.ZERO;
			}
			Map<Integer, BigDecimal> updatedDeltas = allocateDeltasByWeight(candidates, remaining);
			for (DeltaCandidate candidate : candidates) {
				AssessorSavingPlanSuggestionDto original = suggestions.get(candidate.index());
				BigDecimal newDelta = updatedDeltas.getOrDefault(candidate.index(), BigDecimal.ZERO);
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
					type = "increase";
					if (rationale == null || rationale.isBlank()) {
						rationale = "Increase amount.";
					}
				}
				adjusted.set(candidate.index(), new AssessorSavingPlanSuggestionDto(
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
				));
			}
		}
		return List.copyOf(adjusted);
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
		Map<Integer, BigDecimal> reservedByLayer = new LinkedHashMap<>();
		for (AssessorNewInstrumentSuggestionDto suggestion : newInstruments) {
			if (suggestion == null || suggestion.layer() == null || suggestion.amount() == null) {
				continue;
			}
			BigDecimal amount = toBigDecimal(suggestion.amount());
			if (amount == null || amount.signum() <= 0) {
				continue;
			}
			reservedByLayer.merge(suggestion.layer(), amount, BigDecimal::add);
		}
		if (reservedByLayer.isEmpty()) {
			return allocation.instrumentBuckets();
		}
		Map<String, BigDecimal> adjusted = new LinkedHashMap<>(allocation.instrumentBuckets());
		Map<Integer, List<InstrumentAmountCandidate>> candidatesByLayer = new LinkedHashMap<>();
		for (Map.Entry<String, BigDecimal> entry : allocation.instrumentBuckets().entrySet()) {
			BigDecimal amount = entry.getValue();
			if (amount == null || amount.signum() <= 0) {
				continue;
			}
			Integer layer = resolveInstrumentLayer(entry.getKey(), savingPlanSnapshot);
			if (layer == null) {
				continue;
			}
			candidatesByLayer.computeIfAbsent(layer, key -> new ArrayList<>())
					.add(new InstrumentAmountCandidate(entry.getKey(), amount));
		}
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
			BigDecimal totalPositive = BigDecimal.ZERO;
			for (InstrumentAmountCandidate candidate : candidates) {
				totalPositive = totalPositive.add(candidate.weight());
			}
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
		BigDecimal totalReserved = reservedByLayer.values().stream()
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal totalAllocation = BigDecimal.ZERO;
		if (allocation.layerBuckets() != null) {
			for (BigDecimal value : allocation.layerBuckets().values()) {
				if (value != null) {
					totalAllocation = totalAllocation.add(value);
				}
			}
		}
		BigDecimal allowedExisting = totalAllocation.subtract(totalReserved);
		if (allowedExisting.signum() < 0) {
			allowedExisting = BigDecimal.ZERO;
		}
		BigDecimal currentExistingTotal = BigDecimal.ZERO;
		List<InstrumentAmountCandidate> allCandidates = new ArrayList<>();
		for (Map.Entry<String, BigDecimal> entry : adjusted.entrySet()) {
			BigDecimal amount = entry.getValue();
			if (amount != null && amount.signum() > 0) {
				currentExistingTotal = currentExistingTotal.add(amount);
				allCandidates.add(new InstrumentAmountCandidate(entry.getKey(), amount));
			}
		}
		if (!allCandidates.isEmpty() && currentExistingTotal.compareTo(allowedExisting) > 0) {
			Map<String, BigDecimal> updated = allocateAmountsByWeight(allCandidates, allowedExisting);
			for (InstrumentAmountCandidate candidate : allCandidates) {
				adjusted.put(candidate.isin(), updated.getOrDefault(candidate.isin(), BigDecimal.ZERO));
			}
		}
		return Map.copyOf(adjusted);
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

	private AssessorDiagnosticsDto toDiagnosticsDto(AssessorEngine.Diagnostics diagnostics, KbDiagnostics kbDiagnostics) {
		boolean kbEnabled = kbDiagnostics != null && kbDiagnostics.enabled();
		boolean kbComplete = kbDiagnostics != null && kbDiagnostics.complete();
		List<String> missingIsins = kbDiagnostics == null ? List.of() : kbDiagnostics.missingIsins();
		if (diagnostics == null) {
			return new AssessorDiagnosticsDto(false, 0, null, List.of(), kbEnabled, kbComplete, missingIsins);
		}
		return new AssessorDiagnosticsDto(
				diagnostics.withinTolerance(),
				diagnostics.suppressedDeltasCount(),
				toAmount(diagnostics.suppressedAmountTotal()),
				diagnostics.redistributionNotes(),
				kbEnabled,
				kbComplete,
				missingIsins
		);
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
		MapSqlParameterSource params = new MapSqlParameterSource("isins", normalized);
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

	private AssessorInstrumentSuggestionService.SuggestionResult buildInstrumentSuggestions(
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
			AssessorGapDetectionPolicy gapDetectionPolicy) {
		if (result == null || savingPlanSnapshot == null) {
			return AssessorInstrumentSuggestionService.SuggestionResult.empty();
		}
		if (properties.kb() == null || !properties.kb().enabled()) {
			return AssessorInstrumentSuggestionService.SuggestionResult.empty();
		}
		Map<Integer, BigDecimal> savingPlanBudgets = Map.of();
		if (savingPlanDelta != null && savingPlanDelta.signum() > 0) {
			savingPlanBudgets = computeSavingPlanDeltaBudgets(result.targetLayerDistribution(),
					result.currentLayerDistribution(),
					result.savingPlanSuggestions(),
					savingPlanSnapshot);
		}
		Map<Integer, BigDecimal> oneTimeBudgets = Map.of();
		if (oneTimeAmount != null && oneTimeAmount.signum() > 0
				&& result.oneTimeAllocation() != null
				&& result.oneTimeAllocation().layerBuckets() != null) {
			oneTimeBudgets = result.oneTimeAllocation().layerBuckets();
		}
		if (savingPlanBudgets.isEmpty() && oneTimeBudgets.isEmpty()) {
			return AssessorInstrumentSuggestionService.SuggestionResult.empty();
		}
		Set<String> existingCoverageIsins = existingInstrumentLayers == null
				? Set.of()
				: existingInstrumentLayers.keySet();
		if (kbDiagnostics == null || !kbDiagnostics.complete()) {
			return AssessorInstrumentSuggestionService.SuggestionResult.empty();
		}
		Set<String> excludedSnapshotIsins = Set.of();
		Map<Integer, Integer> maxSavingPlans = normalizeMaxSavingPlans(config == null ? null : config.getMaxSavingPlansPerLayer());
		int minSaving = minimumSavingPlanSize == null ? DEFAULT_MIN_SAVING_PLAN : minimumSavingPlanSize;
		int minRebalance = minimumRebalancingAmount == null ? DEFAULT_MIN_REBALANCE : minimumRebalancingAmount;
		int minInstrument = minimumInstrumentAmount == null ? DEFAULT_MIN_INSTRUMENT : minimumInstrumentAmount;
		return instrumentSuggestionService.suggest(new AssessorInstrumentSuggestionService.SuggestionRequest(
				savingPlanSnapshot.plans(),
				existingCoverageIsins,
				savingPlanBudgets,
				oneTimeBudgets,
				minSaving,
				minRebalance,
				minInstrument,
				maxSavingPlans,
				excludedSnapshotIsins,
				gapDetectionPolicy
		));
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
		Map<Integer, BigDecimal> planTotals = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal current = currentAmounts == null ? BigDecimal.ZERO : currentAmounts.getOrDefault(layer, BigDecimal.ZERO);
			planTotals.put(layer, current);
		}
		if (suggestions != null && !suggestions.isEmpty()) {
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

	private SavingPlanAllocation buildSavingPlanAllocation(AssessorEngine.AssessorEngineResult result,
														   SavingPlanSnapshot savingPlanSnapshot,
														   BigDecimal savingPlanDelta,
														   Integer minimumSavingPlanSize,
														   Integer minimumRebalancingAmount,
														   LayerTargetConfigResponseDto config,
														   Map<String, Integer> existingInstrumentLayers,
														   KbDiagnostics kbDiagnostics,
														   AssessorGapDetectionPolicy gapDetectionPolicy) {
		if (result == null || savingPlanSnapshot == null) {
			return null;
		}
		if (savingPlanDelta == null || savingPlanDelta.signum() <= 0) {
			return null;
		}
		if (properties.kb() == null || !properties.kb().enabled()) {
			return null;
		}
		if (kbDiagnostics == null || !kbDiagnostics.complete()) {
			return null;
		}
		Map<Integer, BigDecimal> baseTargets = normalizeLayerAmounts(result.targetLayerDistribution());
		if (baseTargets.isEmpty()) {
			return null;
		}
		Map<Integer, BigDecimal> currentAmounts = normalizeLayerAmounts(result.currentLayerDistribution());
		Map<Integer, BigDecimal> effectiveTargets = new LinkedHashMap<>(baseTargets);
		Map<Integer, Integer> planCounts = countSavingPlans(savingPlanSnapshot.plans());
		Map<Integer, Integer> maxSavingPlans = normalizeMaxSavingPlans(config == null ? null : config.getMaxSavingPlansPerLayer());
		int minSaving = minimumSavingPlanSize == null ? DEFAULT_MIN_SAVING_PLAN : minimumSavingPlanSize;
		int minRebalance = minimumRebalancingAmount == null ? DEFAULT_MIN_REBALANCE : minimumRebalancingAmount;
		int minInstrument = DEFAULT_MIN_INSTRUMENT;
		Set<String> existingCoverageIsins = existingInstrumentLayers == null
				? Set.of()
				: existingInstrumentLayers.keySet();
		AssessorInstrumentSuggestionService.SuggestionResult savingPlanSuggestionResult = AssessorInstrumentSuggestionService.SuggestionResult.empty();
		List<String> allocationNotes = new ArrayList<>();
		int guard = 0;
		while (guard < 6) {
			guard += 1;
			boolean changed = false;
			LayerDeltaGateAdjustment gateAdjustment = applySavingPlanLayerGates(effectiveTargets, currentAmounts, minSaving, minRebalance);
			if (gateAdjustment.changed()) {
				effectiveTargets = gateAdjustment.targetAmounts();
				allocationNotes.addAll(gateAdjustment.notes());
			}
			Map<Integer, BigDecimal> deltas = gateAdjustment.deltas();
			Map<Integer, BigDecimal> positiveBudgets = filterPositiveBudgets(deltas);
			if (!positiveBudgets.isEmpty()) {
				savingPlanSuggestionResult = instrumentSuggestionService.suggest(new AssessorInstrumentSuggestionService.SuggestionRequest(
						savingPlanSnapshot.plans(),
						existingCoverageIsins,
						positiveBudgets,
						Map.of(),
						minSaving,
						minRebalance,
						minInstrument,
						maxSavingPlans,
						Set.of(),
						gapDetectionPolicy
				));
			} else {
				savingPlanSuggestionResult = AssessorInstrumentSuggestionService.SuggestionResult.empty();
			}
			Map<Integer, BigDecimal> newInstrumentTotals = sumNewInstrumentTotals(savingPlanSuggestionResult.savingPlanSuggestions());
			for (int layer = 1; layer <= 5; layer++) {
				BigDecimal budget = positiveBudgets.getOrDefault(layer, BigDecimal.ZERO);
				if (budget.signum() <= 0) {
					continue;
				}
				boolean hasPlans = planCounts.getOrDefault(layer, 0) > 0;
				BigDecimal used = newInstrumentTotals.getOrDefault(layer, BigDecimal.ZERO);
				BigDecimal remainder = budget.subtract(used);
				if (hasPlans && remainder.signum() > 0) {
					if (used.signum() > 0) {
						allocationNotes.add(String.format(
								"Layer %d remaining budget %s EUR was allocated to existing saving plans (KB-weighted) after new plan gates/gaps.",
								layer, toAmount(remainder)));
					} else {
						allocationNotes.add(String.format(
								"Layer %d budget %s EUR was allocated to existing saving plans (KB-weighted) because no eligible new saving plans were proposed.",
								layer, toAmount(remainder)));
					}
				}
				if (hasPlans) {
					continue;
				}
				BigDecimal shift = used.signum() == 0 ? budget : remainder;
				if (shift.signum() > 0) {
					if (used.signum() > 0) {
						allocationNotes.add(String.format(
								"Layer %d could not allocate %s EUR to new saving plans (gates/gaps); redistributed to higher layers.",
								layer, toAmount(shift)));
					} else {
						allocationNotes.add(String.format(
								"Layer %d had no eligible saving plan proposals; redistributed %s EUR to higher layers.",
								layer, toAmount(shift)));
					}
					effectiveTargets = redistributeBudgetToHigherLayers(layer, shift, effectiveTargets, baseTargets);
					changed = true;
				}
			}
			if (!changed) {
				break;
			}
		}
		effectiveTargets = adjustTargetsToExpected(effectiveTargets, result.currentMonthlyTotal());
		List<AssessorNewInstrumentSuggestionDto> newInstruments =
				toNewInstrumentSuggestionDtos(savingPlanSuggestionResult.savingPlanSuggestions(), Set.of());
		Map<Integer, BigDecimal> newInstrumentTotals = sumNewInstrumentTotalsFromDtos(newInstruments);
		Map<Integer, Map<String, BigDecimal>> weightsByLayer = instrumentSuggestionService.computeSavingPlanWeights(
				new AssessorInstrumentSuggestionService.SavingPlanWeightRequest(
						savingPlanSnapshot.plans(),
						existingCoverageIsins,
						gapDetectionPolicy
				));
		Map<AssessorEngine.PlanKey, BigDecimal> planWeights = buildPlanWeights(savingPlanSnapshot.plans(), weightsByLayer);
		List<AssessorEngine.SavingPlanSuggestion> weightedSuggestions = buildWeightedSavingPlanSuggestions(
				savingPlanSnapshot.plans(),
				effectiveTargets,
				currentAmounts,
				newInstrumentTotals,
				planWeights,
				BigDecimal.valueOf(minRebalance),
				BigDecimal.valueOf(minSaving)
		);
		List<AssessorSavingPlanSuggestionDto> suggestionDtos = toSuggestionDtos(weightedSuggestions, savingPlanSnapshot);
		return new SavingPlanAllocation(effectiveTargets, suggestionDtos, newInstruments, List.copyOf(allocationNotes));
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
		BigDecimal gate = BigDecimal.valueOf(gateValue);
		Map<Integer, BigDecimal> adjustedDeltas = new LinkedHashMap<>(deltas);
		List<String> notes = new ArrayList<>();
		boolean changed = false;
		for (int layer = 5; layer >= 1; layer--) {
			BigDecimal delta = adjustedDeltas.getOrDefault(layer, BigDecimal.ZERO);
			if (delta.signum() == 0) {
				continue;
			}
			if (delta.abs().compareTo(gate) >= 0) {
				continue;
			}
			BigDecimal newDelta = delta.signum() < 0 ? gate.negate() : BigDecimal.ZERO;
			BigDecimal diff = delta.subtract(newDelta);
			adjustedDeltas.put(layer, newDelta);
			if (layer > 1 && diff.signum() != 0) {
				adjustedDeltas.put(layer - 1, adjustedDeltas.getOrDefault(layer - 1, BigDecimal.ZERO).add(diff));
				notes.add(String.format(
						"Layer %d delta %s EUR was below the minimum gate %s EUR; shifted %s EUR to Layer %d.",
						layer, toAmount(delta), toAmount(gate), toAmount(diff), layer - 1));
			} else {
				notes.add(String.format(
						"Layer %d delta %s EUR was below the minimum gate %s EUR; suppressed.",
						layer, toAmount(delta), toAmount(gate)));
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
																	 Map<Integer, Map<String, BigDecimal>> weightsByLayer) {
		Map<AssessorEngine.PlanKey, BigDecimal> weights = new LinkedHashMap<>();
		if (plans == null) {
			return weights;
		}
		for (AssessorEngine.SavingPlanItem plan : plans) {
			if (plan == null) {
				continue;
			}
			String isin = normalizeIsin(plan.isin());
			if (isin.isBlank()) {
				continue;
			}
			Map<String, BigDecimal> layerWeights = weightsByLayer == null ? null : weightsByLayer.get(plan.layer());
			BigDecimal weight = layerWeights == null ? null : layerWeights.get(isin);
			if (weight == null || weight.signum() <= 0) {
				weight = BigDecimal.ONE;
			}
			weights.put(new AssessorEngine.PlanKey(isin, plan.depotId()), weight);
		}
		return weights;
	}

	private List<AssessorEngine.SavingPlanSuggestion> buildWeightedSavingPlanSuggestions(
			List<AssessorEngine.SavingPlanItem> plans,
			Map<Integer, BigDecimal> targets,
			Map<Integer, BigDecimal> currentAmounts,
			Map<Integer, BigDecimal> newInstrumentTotals,
			Map<AssessorEngine.PlanKey, BigDecimal> planWeights,
			BigDecimal minimumRebalancing,
			BigDecimal minimumSavingPlanSize) {
		if (plans == null || plans.isEmpty()) {
			return List.of();
		}
		Map<AssessorEngine.PlanKey, AssessorEngine.SavingPlanItem> planMap = new LinkedHashMap<>();
		Map<Integer, List<AssessorEngine.SavingPlanItem>> byLayer = new LinkedHashMap<>();
		for (AssessorEngine.SavingPlanItem plan : plans) {
			if (plan == null) {
				continue;
			}
			AssessorEngine.PlanKey key = new AssessorEngine.PlanKey(normalizeIsin(plan.isin()), plan.depotId());
			planMap.put(key, plan);
			byLayer.computeIfAbsent(plan.layer(), layer -> new ArrayList<>()).add(plan);
		}
		List<AssessorEngine.SavingPlanSuggestion> suggestions = new ArrayList<>();
		for (int layer = 1; layer <= 5; layer++) {
			List<AssessorEngine.SavingPlanItem> layerPlans = byLayer.getOrDefault(layer, List.of());
			if (layerPlans.isEmpty()) {
				continue;
			}
			BigDecimal target = targets.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal current = currentAmounts.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal reserved = newInstrumentTotals.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal existingTarget = target.subtract(reserved);
			BigDecimal delta = existingTarget.subtract(current);
			if (delta.signum() == 0) {
				continue;
			}
			Map<AssessorEngine.PlanKey, BigDecimal> deltas = allocatePlanDeltasByWeights(
					layerPlans,
					delta,
					planWeights,
					minimumRebalancing,
					minimumSavingPlanSize);
			for (Map.Entry<AssessorEngine.PlanKey, BigDecimal> entry : deltas.entrySet()) {
				BigDecimal planDelta = entry.getValue();
				if (planDelta == null || planDelta.signum() == 0) {
					continue;
				}
				AssessorEngine.SavingPlanItem plan = planMap.get(entry.getKey());
				if (plan == null) {
					continue;
				}
				BigDecimal oldAmount = safeAmount(plan.amount());
				BigDecimal newAmount = oldAmount.add(planDelta);
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
		suggestions.sort(Comparator.comparing(AssessorEngine.SavingPlanSuggestion::type)
				.thenComparing(AssessorEngine.SavingPlanSuggestion::isin));
		return List.copyOf(suggestions);
	}

	Map<AssessorEngine.PlanKey, BigDecimal> allocatePlanDeltasByWeights(
			List<AssessorEngine.SavingPlanItem> plans,
			BigDecimal delta,
			Map<AssessorEngine.PlanKey, BigDecimal> planWeights,
			BigDecimal minimumRebalancing,
			BigDecimal minimumSavingPlanSize) {
		if (plans == null || plans.isEmpty() || delta == null || delta.signum() == 0) {
			return Map.of();
		}
		List<PlanCandidate> candidates = new ArrayList<>();
		for (AssessorEngine.SavingPlanItem plan : plans) {
			if (plan == null) {
				continue;
			}
			AssessorEngine.PlanKey key = new AssessorEngine.PlanKey(normalizeIsin(plan.isin()), plan.depotId());
			BigDecimal weight = planWeights == null ? null : planWeights.get(key);
			if (weight == null || weight.signum() <= 0) {
				weight = BigDecimal.ONE;
			}
			candidates.add(new PlanCandidate(key, weight, safeAmount(plan.amount())));
		}
		candidates.sort(Comparator.comparing(PlanCandidate::weight).reversed()
				.thenComparing(candidate -> candidate.key().isin()));
		if (delta.signum() > 0) {
			if (minimumRebalancing != null && minimumRebalancing.signum() > 0
					&& delta.compareTo(minimumRebalancing) < 0) {
				return Map.of();
			}
			return allocatePositivePlanDeltas(candidates, delta, minimumRebalancing);
		}
		return allocateNegativePlanDeltas(candidates, delta.abs(), minimumRebalancing, minimumSavingPlanSize);
	}

	private Map<AssessorEngine.PlanKey, BigDecimal> allocatePositivePlanDeltas(List<PlanCandidate> candidates,
																			  BigDecimal total,
																			  BigDecimal minimumRebalancing) {
		List<PlanCandidate> remaining = new ArrayList<>(candidates);
		int guard = 0;
		while (!remaining.isEmpty() && guard < 12) {
			guard += 1;
			Map<AssessorEngine.PlanKey, BigDecimal> allocations = allocatePlanDeltasByWeight(remaining, total);
			boolean valid = true;
			if (minimumRebalancing != null && minimumRebalancing.signum() > 0) {
				for (BigDecimal value : allocations.values()) {
					if (value.signum() != 0 && value.compareTo(minimumRebalancing) < 0) {
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
				if (value.signum() != 0 && minimumRebalancing != null
						&& value.compareTo(minimumRebalancing) < 0) {
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

	private BigDecimal normalizeMinimum(BigDecimal value) {
		if (value == null || value.signum() <= 0) {
			return BigDecimal.ZERO;
		}
		return value;
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

	private String determinePlanType(BigDecimal oldAmount, BigDecimal newAmount) {
		if (newAmount.signum() == 0 && oldAmount.signum() > 0) {
			return "disable";
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

	private String buildPlanRationale(String type) {
		return switch (type) {
			case "disable" -> "Disable to avoid sub-minimum saving plan size.";
			case "increase" -> "Increase to align with target layer allocation.";
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
			String action = effective.contains(isin) ? "increase" : "new";
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
		if (suggestions != null) {
			for (AssessorEngine.SavingPlanSuggestion suggestion : suggestions) {
				if (suggestion == null) {
					continue;
				}
				String isin = normalizeIsin(suggestion.isin());
				PlanKey key = new PlanKey(isin, suggestion.depotId());
				suggestionByPlan.put(key, suggestion);
				PlanMeta meta = planMeta.get(key);
				if (meta == null) {
					InstrumentMeta instrument = instrumentMeta.get(isin);
					if (instrument != null) {
						meta = new PlanMeta(instrument.name(), instrument.layer(), null);
					}
				}
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
		if (savingPlanSnapshot == null || savingPlanSnapshot.plans() == null || savingPlanSnapshot.plans().isEmpty()) {
			return List.copyOf(dtos);
		}
		for (AssessorEngine.SavingPlanItem plan : savingPlanSnapshot.plans()) {
			if (plan == null) {
				continue;
			}
			String isin = normalizeIsin(plan.isin());
			PlanKey key = new PlanKey(isin, plan.depotId());
			if (suggestionByPlan.containsKey(key)) {
				continue;
			}
			PlanMeta meta = planMeta.get(key);
			if (meta == null) {
				InstrumentMeta instrument = instrumentMeta.get(isin);
				if (instrument != null) {
					meta = new PlanMeta(instrument.name(), instrument.layer(), null);
				}
			}
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
		return List.copyOf(dtos);
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
		String fallback = config == null ? "BALANCED" : config.getActiveProfileKey();
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

	private record PlanCandidate(AssessorEngine.PlanKey key, BigDecimal weight, BigDecimal amount) {
	}

	private record ReductionCandidate(AssessorEngine.PlanKey key,
									  BigDecimal weight,
									  BigDecimal amount,
									  BigDecimal capacity,
									  BigDecimal eligibleCapacity) {
	}

	private record SavingPlanAllocation(Map<Integer, BigDecimal> targetLayerAmounts,
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
}
