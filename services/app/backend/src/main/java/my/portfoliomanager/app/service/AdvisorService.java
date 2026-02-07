package my.portfoliomanager.app.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.dto.AllocationDto;
import my.portfoliomanager.app.dto.AdvisorRunDetailDto;
import my.portfoliomanager.app.dto.AdvisorRunDto;
import my.portfoliomanager.app.dto.AdvisorSummaryDto;
import my.portfoliomanager.app.dto.ConstraintResultDto;
import my.portfoliomanager.app.dto.InstrumentProposalDto;
import my.portfoliomanager.app.dto.InstrumentProposalGatingDto;
import my.portfoliomanager.app.dto.LayerTargetConfigResponseDto;
import my.portfoliomanager.app.dto.LayerTargetDto;
import my.portfoliomanager.app.dto.PositionDto;
import my.portfoliomanager.app.dto.SavingPlanLayerDto;
import my.portfoliomanager.app.dto.SavingPlanProposalDto;
import my.portfoliomanager.app.dto.SavingPlanProposalLayerDto;
import my.portfoliomanager.app.dto.SavingPlanSummaryDto;
import my.portfoliomanager.app.model.LayerTargetEffectiveConfig;
import my.portfoliomanager.app.model.LayerTargetRiskThresholds;
import my.portfoliomanager.app.service.util.RiskThresholdsUtil;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AdvisorService {
	private static final Logger logger = LoggerFactory.getLogger(AdvisorService.class);
	private static final int INSTRUMENT_HIGHLIGHT_LIMIT = 8;
	private static final int INSTRUMENT_WEIGHT_LIMIT = 5;
	private static final int DEFAULT_PROJECTION_HORIZON_MONTHS = 12;
	private static final int MIN_PROJECTION_HORIZON_MONTHS = 1;
	private static final int MAX_PROJECTION_HORIZON_MONTHS = 120;
	private static final BigDecimal PROJECTION_BLEND_MIN = new BigDecimal("0.15");
	private static final BigDecimal PROJECTION_BLEND_MAX = new BigDecimal("0.45");
	private static final int DEFAULT_MIN_INSTRUMENT_AMOUNT = 25;
	private static final String REASON_GAP_SUGGESTION = "KB_GAP_SUGGESTION";
	private static final String WARNING_RISK_NOT_ACCEPTABLE = "RISK_NOT_ACCEPTABLE";

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper summaryMapper;
	private final LlmNarrativeService llmNarrativeService;
	private final boolean llmEnabled;
	private final InstrumentRebalanceService instrumentRebalanceService;
	private final AssessorInstrumentSuggestionService instrumentSuggestionService;
	private final AssessorInstrumentAssessmentService instrumentAssessmentService;
	private final LayerTargetConfigService layerTargetConfigService;
	private final LlmPromptPolicy llmPromptPolicy;
	private volatile Boolean isPostgres;
	private static final String ADVISOR_RUN_INSERT_SQL = """
			insert into advisor_runs (as_of_date, depot_scope, summary_json, narrative_md, warnings)
			values (?, ?, ?, ?, ?)
			""";
	private static final String ADVISOR_RUN_INSERT_SQL_POSTGRES = """
			insert into advisor_runs (as_of_date, depot_scope, summary_json, narrative_md, warnings)
			values (?, cast(? as jsonb), cast(? as jsonb), ?, ?)
			""";

	public AdvisorService(JdbcTemplate jdbcTemplate,
					  LlmNarrativeService llmNarrativeService,
					  InstrumentRebalanceService instrumentRebalanceService,
					  AssessorInstrumentSuggestionService instrumentSuggestionService,
					  AssessorInstrumentAssessmentService instrumentAssessmentService,
					  LayerTargetConfigService layerTargetConfigService,
					  LlmPromptPolicy llmPromptPolicy) {
		this.jdbcTemplate = jdbcTemplate;
		this.summaryMapper = new ObjectMapper();
		this.llmNarrativeService = llmNarrativeService;
		this.llmEnabled = llmNarrativeService != null && llmNarrativeService.isEnabled();
		this.instrumentRebalanceService = instrumentRebalanceService;
		this.instrumentSuggestionService = instrumentSuggestionService;
		this.instrumentAssessmentService = instrumentAssessmentService;
		this.layerTargetConfigService = layerTargetConfigService;
		this.llmPromptPolicy = llmPromptPolicy;
	}

	public AdvisorSummaryDto summary(LocalDate asOf) {
		double total = sumTotal(asOf);
		List<AllocationDto> layers = loadAllocations(asOf, "layer", total);
		List<AllocationDto> assetClasses = loadAllocations(asOf, "asset_class", total);
		List<PositionDto> topPositions = loadTopPositions(asOf, total);
		SavingPlanMetrics savingPlanMetrics = loadSavingPlanMetrics();
		SavingPlanSummaryDto savingPlanSummary = toSavingPlanSummary(savingPlanMetrics);
		LayerTargetEffectiveConfig targetConfig = layerTargetConfigService.loadEffectiveConfig();
		List<LayerTargetDto> targets = toTargetDtos(targetConfig == null ? null : targetConfig.effectiveLayerTargets());
		Map<Integer, BigDecimal> holdingsByLayer = loadHoldingsByLayer(asOf);
		SavingPlanProposalDto proposal = buildSavingPlanProposal(savingPlanMetrics, targetConfig, holdingsByLayer);
		return new AdvisorSummaryDto(layers, assetClasses, topPositions, savingPlanSummary, targets, proposal);
	}

	public AdvisorRunDetailDto saveRun(LocalDate asOf) {
		List<SnapshotScope> scopes = loadSnapshotScopes(asOf);
		if (scopes.isEmpty()) {
			throw new IllegalArgumentException("No snapshots found for the selected scope.");
		}
		AdvisorSummaryDto summary = summary(asOf);
		LocalDate resolvedAsOf = asOf == null ? resolveMaxSnapshotDate(scopes) : asOf;
		List<String> depotScope = scopes.stream().map(SnapshotScope::depotCode).distinct().sorted().toList();
		String narrative = summary.savingPlanProposal() == null ? null : summary.savingPlanProposal().getNarrative();
		String summaryJson = writeSummaryJson(summary);
		String depotScopeJson = writeSummaryJson(depotScope);

		KeyHolder keyHolder = new GeneratedKeyHolder();
		String insertSql = isPostgres() ? ADVISOR_RUN_INSERT_SQL_POSTGRES : ADVISOR_RUN_INSERT_SQL;
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(insertSql, new String[] { "run_id" });
			if (resolvedAsOf == null) {
				statement.setNull(1, java.sql.Types.DATE);
			} else {
				statement.setDate(1, Date.valueOf(resolvedAsOf));
			}
			statement.setString(2, depotScopeJson);
			statement.setString(3, summaryJson);
			statement.setString(4, narrative);
			statement.setString(5, null);
			return statement;
		}, keyHolder);
		Number key = keyHolder.getKey();
		long runId = key == null ? resolveLatestRunId() : key.longValue();
		return getRun(runId);
	}

	public List<AdvisorRunDto> listRuns() {
		String sql = """
				select run_id, created_at, as_of_date, depot_scope
				from advisor_runs
				order by created_at desc
				""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> new AdvisorRunDto(
				rs.getLong("run_id"),
				toOffsetDateTime(rs.getObject("created_at")),
				rs.getDate("as_of_date") == null ? null : rs.getDate("as_of_date").toLocalDate(),
				parseDepotScope(rs.getString("depot_scope"))
		));
	}

	public AdvisorRunDetailDto getRun(long runId) {
		String sql = """
				select run_id, created_at, as_of_date, depot_scope, summary_json, narrative_md
				from advisor_runs
				where run_id = ?
				""";
		return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new AdvisorRunDetailDto(
				rs.getLong("run_id"),
				toOffsetDateTime(rs.getObject("created_at")),
				rs.getDate("as_of_date") == null ? null : rs.getDate("as_of_date").toLocalDate(),
				parseDepotScope(rs.getString("depot_scope")),
				rs.getString("narrative_md"),
				parseSummaryJson(rs.getString("summary_json"))
		), runId);
	}

	private double sumTotal(LocalDate asOf) {
		String sql = "select sum(sp.value_eur) from snapshot_positions sp "
				+ joinSnapshots(asOf);
		Double total = asOf == null
				? jdbcTemplate.queryForObject(sql, Double.class)
				: jdbcTemplate.queryForObject(sql, Double.class, Date.valueOf(asOf));
		return total == null ? 0.0d : total;
	}

	private List<AllocationDto> loadAllocations(LocalDate asOf, String field, double total) {
		String column = "ie." + field;
		String sql = "select " + column + " as label, sum(sp.value_eur) as value_eur "
				+ "from snapshot_positions sp "
				+ "join instruments_effective ie on ie.isin = sp.isin "
				+ joinSnapshots(asOf)
				+ "group by " + column + " order by value_eur desc";
		return asOf == null
				? jdbcTemplate.query(sql, (rs, rowNum) -> new AllocationDto(
								rs.getString("label"),
								rs.getDouble("value_eur"),
								percentage(rs.getDouble("value_eur"), total)
						))
				: jdbcTemplate.query(sql, (rs, rowNum) -> new AllocationDto(
								rs.getString("label"),
								rs.getDouble("value_eur"),
								percentage(rs.getDouble("value_eur"), total)
						), Date.valueOf(asOf));
	}

	private List<PositionDto> loadTopPositions(LocalDate asOf, double total) {
		String sql = "select sp.isin, coalesce(sp.name, ie.name) as name, sum(sp.value_eur) as value_eur "
				+ "from snapshot_positions sp "
				+ "join instruments_effective ie on ie.isin = sp.isin "
				+ joinSnapshots(asOf)
				+ "group by sp.isin, coalesce(sp.name, ie.name) "
				+ "order by value_eur desc limit 10";
		return asOf == null
				? jdbcTemplate.query(sql, (rs, rowNum) -> new PositionDto(
								rs.getString("isin"),
								rs.getString("name"),
								rs.getDouble("value_eur"),
								percentage(rs.getDouble("value_eur"), total)
						))
				: jdbcTemplate.query(sql, (rs, rowNum) -> new PositionDto(
								rs.getString("isin"),
								rs.getString("name"),
								rs.getDouble("value_eur"),
								percentage(rs.getDouble("value_eur"), total)
						), Date.valueOf(asOf));
	}

	private String joinSnapshots(LocalDate asOf) {
		if (asOf == null) {
			return "join depots d on d.active_snapshot_id = sp.snapshot_id ";
		}
		return "join snapshots s on s.snapshot_id = sp.snapshot_id "
				+ "where s.snapshot_id in ("
				+ "select s2.snapshot_id from snapshots s2 "
				+ "join (select depot_id, max(as_of_date) as max_date from snapshots where as_of_date <= ? group by depot_id) md "
				+ "on s2.depot_id = md.depot_id and s2.as_of_date = md.max_date"
				+ ") ";
	}

	private double percentage(double value, double total) {
		if (total == 0.0d) {
			return 0.0d;
		}
		return value / total * 100.0d;
	}

	private List<SnapshotScope> loadSnapshotScopes(LocalDate asOf) {
		if (asOf == null) {
			String sql = """
					select s.snapshot_id, s.as_of_date, d.depot_code
					from depots d
					join snapshots s on s.snapshot_id = d.active_snapshot_id
					order by d.depot_code
					""";
			return jdbcTemplate.query(sql, (rs, rowNum) -> new SnapshotScope(
					rs.getLong("snapshot_id"),
					rs.getDate("as_of_date").toLocalDate(),
					rs.getString("depot_code")
			));
		}
		String sql = """
				select s.snapshot_id, s.as_of_date, d.depot_code
				from snapshots s
				join depots d on d.depot_id = s.depot_id
				join (
					select depot_id, max(as_of_date) as max_date
					from snapshots
					where as_of_date <= ?
					group by depot_id
				) md on s.depot_id = md.depot_id and s.as_of_date = md.max_date
				order by d.depot_code
				""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> new SnapshotScope(
				rs.getLong("snapshot_id"),
				rs.getDate("as_of_date").toLocalDate(),
				rs.getString("depot_code")
		), Date.valueOf(asOf));
	}

	private LocalDate resolveMaxSnapshotDate(List<SnapshotScope> scopes) {
		return scopes.stream()
				.map(SnapshotScope::asOfDate)
				.filter(date -> date != null)
				.max(LocalDate::compareTo)
				.orElse(null);
	}

	private long resolveLatestRunId() {
		Long latest = jdbcTemplate.queryForObject(
				"select max(run_id) from advisor_runs",
				Long.class
		);
		return latest == null ? 0L : latest;
	}

	private List<String> parseDepotScope(String depotScopeJson) {
		if (depotScopeJson == null || depotScopeJson.isBlank()) {
			return Collections.emptyList();
		}
		try {
			return summaryMapper.readValue(depotScopeJson, new TypeReference<>() {});
		} catch (Exception ex) {
			return Collections.emptyList();
		}
	}

	private AdvisorSummaryDto parseSummaryJson(String summaryJson) {
		if (summaryJson == null || summaryJson.isBlank()) {
			return null;
		}
		try {
			return summaryMapper.readValue(summaryJson, AdvisorSummaryDto.class);
		} catch (Exception ex) {
			logger.warn("Failed to parse summary json: {}", summaryJson, ex);
			return null;
		}
	}

	private String writeSummaryJson(Object payload) {
		if (payload == null) {
			return null;
		}
		try {
			return summaryMapper.writeValueAsString(payload);
		} catch (Exception ex) {
			if (payload instanceof List<?>) {
				return "[]";
			}
			return "{}";
		}
	}

	private OffsetDateTime toOffsetDateTime(Object raw) {
		if (raw instanceof OffsetDateTime offset) {
			return offset;
		}
		if (raw instanceof java.sql.Timestamp timestamp) {
			return timestamp.toInstant().atOffset(ZoneOffset.UTC);
		}
		return null;
	}

	private boolean isPostgres() {
		Boolean cached = isPostgres;
		if (cached != null) {
			return cached;
		}
		synchronized (this) {
			if (isPostgres != null) {
				return isPostgres;
			}
			boolean result = false;
			try {
				result = Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
					String productName = connection.getMetaData().getDatabaseProductName();
					return productName != null && productName.toLowerCase(Locale.ROOT).contains("postgres");
				}));
			} catch (Exception ignored) {
				result = false;
			}
			isPostgres = result;
			return result;
		}
	}

	private SavingPlanMetrics loadSavingPlanMetrics() {
		String sql = """
				select sp.amount_eur,
				       sp.frequency,
				       coalesce(ie.layer, 5) as layer
				from sparplans sp
				left join instruments_effective ie on ie.isin = sp.isin
				where sp.active = true
				""";
		List<SavingPlanRow> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new SavingPlanRow(
				toBigDecimal(rs.getObject("amount_eur")),
				rs.getString("frequency"),
				rs.getInt("layer")
		));

		BigDecimal activeTotal = BigDecimal.ZERO;
		BigDecimal monthlyTotal = BigDecimal.ZERO;
		int activeCount = 0;
		int monthlyCount = 0;
		Map<Integer, BigDecimal> monthlyByLayer = initLayerAmounts();
		Map<Integer, Integer> monthlyCounts = initLayerCounts();

		for (SavingPlanRow row : rows) {
			BigDecimal amount = row.amount() == null ? BigDecimal.ZERO : row.amount();
			String frequency = normalizeFrequency(row.frequency());
			int layer = normalizeLayer(row.layer());

			activeTotal = activeTotal.add(amount);
			activeCount += 1;

			if ("monthly".equals(frequency)) {
				monthlyTotal = monthlyTotal.add(amount);
				monthlyCount += 1;
				monthlyByLayer.put(layer, monthlyByLayer.get(layer).add(amount));
				monthlyCounts.put(layer, monthlyCounts.get(layer) + 1);
			}
		}

		return new SavingPlanMetrics(
				activeTotal,
				monthlyTotal,
				activeCount,
				monthlyCount,
				monthlyByLayer,
				monthlyCounts
		);
	}

	private List<SavingPlanInstrument> loadSavingPlanInstruments() {
		String sql = """
				select sp.isin,
				       coalesce(ie.name, sp.name) as name,
				       sum(sp.amount_eur) as amount_eur,
				       max(sp.last_changed) as last_changed,
				       coalesce(ie.layer, 5) as layer
				from sparplans sp
				left join instruments_effective ie on ie.isin = sp.isin
				where sp.active = true
				  and lower(sp.frequency) = 'monthly'
				group by sp.isin, coalesce(ie.name, sp.name), coalesce(ie.layer, 5)
				""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> new SavingPlanInstrument(
				rs.getString("isin"),
				rs.getString("name"),
				toBigDecimal(rs.getObject("amount_eur")),
				normalizeLayer(rs.getInt("layer")),
				rs.getObject("last_changed", LocalDate.class)
		));
	}

	private Map<Integer, BigDecimal> loadHoldingsByLayer(LocalDate asOf) {
		Map<Integer, BigDecimal> holdings = initLayerAmounts();
		String sql = "select ie.layer as layer, sum(sp.value_eur) as value_eur "
				+ "from snapshot_positions sp "
				+ "join instruments_effective ie on ie.isin = sp.isin "
				+ joinSnapshots(asOf)
				+ "group by ie.layer";
			if (asOf == null) {
				jdbcTemplate.query(sql, rs -> {
					Integer layer = rs.getObject("layer") == null ? null : rs.getInt("layer");
					if (layer == null) {
						return;
					}
					BigDecimal value = toBigDecimal(rs.getObject("value_eur"));
					if (value == null) {
						value = BigDecimal.ZERO;
					}
					int normalized = normalizeLayer(layer);
					holdings.put(normalized, holdings.getOrDefault(normalized, BigDecimal.ZERO).add(value));
				});
				return holdings;
			}
			jdbcTemplate.query(sql, rs -> {
				Integer layer = rs.getObject("layer") == null ? null : rs.getInt("layer");
				if (layer == null) {
					return;
				}
				BigDecimal value = toBigDecimal(rs.getObject("value_eur"));
				if (value == null) {
					value = BigDecimal.ZERO;
				}
				int normalized = normalizeLayer(layer);
				holdings.put(normalized, holdings.getOrDefault(normalized, BigDecimal.ZERO).add(value));
			}, Date.valueOf(asOf));
			return holdings;
	}

	private SavingPlanSummaryDto toSavingPlanSummary(SavingPlanMetrics metrics) {
		if (metrics == null) {
			return new SavingPlanSummaryDto(0.0d, 0.0d, 0, 0, Collections.emptyList());
		}
		List<SavingPlanLayerDto> monthlyLayers = new ArrayList<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal value = metrics.monthlyByLayer().get(layer);
			BigDecimal weight = metrics.monthlyTotal().signum() == 0
					? BigDecimal.ZERO
					: value.divide(metrics.monthlyTotal(), 6, RoundingMode.HALF_UP);
			monthlyLayers.add(new SavingPlanLayerDto(
					layer,
					toAmount(value),
					toWeightPct(weight),
					metrics.monthlyCounts().get(layer)
			));
		}
		return new SavingPlanSummaryDto(
				toAmount(metrics.activeTotal()),
				toAmount(metrics.monthlyTotal()),
				metrics.activeCount(),
				metrics.monthlyCount(),
				monthlyLayers
		);
	}

	private List<LayerTargetDto> toTargetDtos(Map<Integer, BigDecimal> targetWeights) {
		if (targetWeights == null || targetWeights.isEmpty()) {
			return Collections.emptyList();
		}
		List<LayerTargetDto> targets = new ArrayList<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal weight = targetWeights.getOrDefault(layer, BigDecimal.ZERO);
			targets.add(new LayerTargetDto(layer, toWeightPct(weight)));
		}
		return targets;
	}

	private SavingPlanProposalDto buildSavingPlanProposal(SavingPlanMetrics metrics,
									 LayerTargetEffectiveConfig targetConfig,
									 Map<Integer, BigDecimal> holdingsByLayer) {
		if (metrics == null || targetConfig == null) {
			return null;
		}
		BigDecimal monthlyTotal = metrics.monthlyTotal();
		if (monthlyTotal.signum() <= 0) {
			return null;
		}
		Map<Integer, BigDecimal> targetWeights = normalizeTargetWeights(targetConfig.effectiveLayerTargets());
		BigDecimal targetTotal = targetWeights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		if (targetTotal.signum() <= 0) {
			return null;
		}

		Map<Integer, BigDecimal> actualDistribution = computeDistribution(metrics.monthlyByLayer(), monthlyTotal);
		Map<Integer, BigDecimal> roundedTargets = buildProjectedTargets(
				metrics,
				targetWeights,
				holdingsByLayer,
				targetConfig.projectionHorizonMonths(),
				targetConfig.acceptableVariancePct(),
				targetConfig.projectionBlendMin(),
				targetConfig.projectionBlendMax()
		);
		Map<Integer, BigDecimal> projectedDistribution = computeDistribution(roundedTargets, monthlyTotal);
		boolean withinTolerance = isWithinTolerance(actualDistribution, projectedDistribution, targetConfig.acceptableVariancePct());
		MinimumSavingPlanAdjustment minimumSavingPlanAdjustment = applyMinimumSavingPlanSize(roundedTargets, targetConfig.minimumSavingPlanSize());
		boolean minimumSavingPlanRebalanced = minimumSavingPlanAdjustment.rebalanced();
		boolean minimumSavingPlanIncreaseLayerOne = minimumSavingPlanAdjustment.increasedLayerOneToMinimum();
		boolean effectiveWithinTolerance = withinTolerance && !minimumSavingPlanRebalanced;
		Map<Integer, BigDecimal> proposalAmounts = effectiveWithinTolerance
				? metrics.monthlyByLayer()
				: minimumSavingPlanAdjustment.adjustedAmounts();
		MinimumRebalancingAdjustment minimumRebalancingAdjustment = applyMinimumRebalancingAmount(
				proposalAmounts,
				metrics.monthlyByLayer(),
				targetConfig.minimumRebalancingAmount()
		);
		boolean minimumRebalancingApplied = minimumRebalancingAdjustment.applied();
		proposalAmounts = minimumRebalancingAdjustment.adjustedAmounts();
		MinimumSavingPlanImpact minimumSavingPlanImpact = reconcileMinimumSavingPlanImpact(roundedTargets, minimumSavingPlanAdjustment, proposalAmounts);
		minimumSavingPlanRebalanced = minimumSavingPlanImpact.rebalanced();
		minimumSavingPlanIncreaseLayerOne = minimumSavingPlanImpact.increasedLayerOne();
		effectiveWithinTolerance = withinTolerance && !minimumSavingPlanRebalanced;
		Map<Integer, BigDecimal> deviations = computeDeviations(actualDistribution, targetWeights);
		List<ConstraintResultDto> constraintResults = evaluateConstraints(actualDistribution,
				targetConfig.selectedProfile().getConstraints(),
				targetConfig.layerNames());

		Map<Integer, Double> actualDistributionPct = toPercentageMap(actualDistribution);
		Map<Integer, Double> targetDistributionPct = toPercentageMap(targetWeights);

		List<SavingPlanInstrument> savingPlanInstruments = loadSavingPlanInstruments();
		LayerTargetRiskThresholds riskThresholds = resolveRiskThresholds(targetConfig);
		Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer =
				resolveRiskThresholdsByLayer(targetConfig, riskThresholds);
		InstrumentRebalanceService.InstrumentProposalResult instrumentResult = instrumentRebalanceService
				.buildInstrumentProposals(savingPlanInstruments, proposalAmounts, targetConfig.minimumSavingPlanSize(),
						targetConfig.minimumRebalancingAmount(), effectiveWithinTolerance, riskThresholds,
						riskThresholdsByLayer);
		List<InstrumentProposalDto> instrumentProposals = instrumentResult == null
				? new ArrayList<>()
				: new ArrayList<>(instrumentResult.proposals());
		InstrumentProposalGatingDto instrumentGating = instrumentResult == null ? null : instrumentResult.gating();
		List<InstrumentRebalanceService.LayerWeightingSummary> instrumentWeightingSummaries = instrumentResult == null
				? List.of()
				: instrumentResult.weightingSummaries();
		List<InstrumentRebalanceService.InstrumentWarning> warningDetails = instrumentResult == null
				? new ArrayList<>()
				: new ArrayList<>(instrumentResult.warnings());
		List<InstrumentProposalDto> gapProposals = buildGapInstrumentProposals(metrics, proposalAmounts,
				targetConfig, savingPlanInstruments, instrumentGating);
		if (!gapProposals.isEmpty()) {
			instrumentProposals.addAll(gapProposals);
			removeLayerWarningsForGapSuggestions(warningDetails, gapProposals);
		}
		appendRiskWarnings(warningDetails, savingPlanInstruments, targetConfig, instrumentGating);
		instrumentProposals.sort((left, right) -> {
			Integer leftLayer = left == null ? null : left.getLayer();
			Integer rightLayer = right == null ? null : right.getLayer();
			if (leftLayer == null && rightLayer != null) {
				return 1;
			}
			if (leftLayer != null && rightLayer == null) {
				return -1;
			}
			if (leftLayer != null && rightLayer != null) {
				int compare = Integer.compare(leftLayer, rightLayer);
				if (compare != 0) {
					return compare;
				}
			}
			String leftIsin = left == null ? null : left.getIsin();
			String rightIsin = right == null ? null : right.getIsin();
			if (leftIsin == null && rightIsin != null) {
				return 1;
			}
			if (leftIsin != null && rightIsin == null) {
				return -1;
			}
			if (leftIsin == null) {
				return 0;
			}
			return leftIsin.compareTo(rightIsin);
		});
		List<String> instrumentWarnings = warningDetails.isEmpty()
				? List.of()
				: warningDetails.stream().map(InstrumentRebalanceService.InstrumentWarning::message).toList();
		List<String> instrumentWarningCodes = warningDetails.isEmpty()
				? List.of()
				: warningDetails.stream().map(InstrumentRebalanceService.InstrumentWarning::code).toList();
		List<InstrumentDiscard> instrumentDiscards = buildInstrumentDiscards(instrumentProposals);
		boolean minimumRebalancingTriggered = minimumRebalancingApplied || hasReasonCode(instrumentProposals, "MIN_REBALANCE_AMOUNT");
		proposalAmounts = reconcileProposalAmountsWithInstruments(proposalAmounts, instrumentProposals, monthlyTotal);
		BigDecimal proposalTotal = sumAmounts(proposalAmounts);
		Map<Integer, BigDecimal> proposedDistribution = computeDistribution(proposalAmounts, proposalTotal);
		Map<Integer, Double> proposedDistributionPct = toPercentageMap(proposedDistribution);
		Map<Integer, Double> layerBudgets = toAmountMap(proposalAmounts);

		List<String> notes = new ArrayList<>(buildToleranceNotes(effectiveWithinTolerance, targetConfig.acceptableVariancePct()));
		if (!llmEnabled && minimumSavingPlanIncreaseLayerOne) {
			notes.add(buildMinimumSavingPlanIncreaseNotice(targetConfig.minimumSavingPlanSize()));
		}
		if (!llmEnabled && minimumRebalancingTriggered) {
			notes.add(buildMinimumRebalancingNotice(targetConfig.minimumRebalancingAmount()));
		}
		String recommendation = buildRecommendation(effectiveWithinTolerance, minimumSavingPlanRebalanced,
				minimumRebalancingTriggered, targetConfig.minimumRebalancingAmount());
		String narrative = composeNarrative(metrics, targetConfig, actualDistributionPct, targetDistributionPct,
				proposedDistributionPct, effectiveWithinTolerance, constraintResults, minimumSavingPlanRebalanced,
				minimumSavingPlanIncreaseLayerOne, targetConfig.minimumSavingPlanSize(), proposalAmounts.getOrDefault(1, BigDecimal.ZERO),
				instrumentDiscards, instrumentProposals, instrumentWeightingSummaries, minimumRebalancingTriggered,
				targetConfig.minimumRebalancingAmount(), minimumRebalancingAdjustment.skippedLayers());
		List<SavingPlanProposalLayerDto> layers = buildProposalLayers(metrics, targetConfig, actualDistribution,
				targetWeights, proposalAmounts, monthlyTotal, holdingsByLayer);

		double targetWeightTotalPct = toWeightPct(targetWeights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));

		return new SavingPlanProposalDto(
				toAmount(monthlyTotal),
				targetWeightTotalPct,
				effectiveWithinTolerance ? "actual" : "targets",
				narrative,
				notes,
				layers,
				actualDistributionPct,
				targetDistributionPct,
				proposedDistributionPct,
				layerBudgets,
				instrumentProposals,
				instrumentWarnings,
				instrumentWarningCodes,
				instrumentGating,
				toPercentagePoints(deviations),
				effectiveWithinTolerance,
				constraintResults,
				recommendation,
				targetConfig.selectedProfileKey(),
				targetConfig.selectedProfile().getDisplayName()
		);
	}

	private String composeNarrative(SavingPlanMetrics metrics,
									LayerTargetEffectiveConfig targetConfig,
									Map<Integer, Double> actualDistribution,
									Map<Integer, Double> targetDistribution,
									Map<Integer, Double> proposedDistribution,
									boolean withinTolerance,
									List<ConstraintResultDto> constraints,
									boolean minimumSavingPlanRebalanced,
									boolean minimumSavingPlanIncreaseLayerOne,
									Integer minimumSavingPlanSize,
									BigDecimal proposedLayerOneAmount,
									List<InstrumentDiscard> instrumentDiscards,
									List<InstrumentProposalDto> instrumentProposals,
									List<InstrumentRebalanceService.LayerWeightingSummary> instrumentWeightingSummaries,
									boolean minimumRebalancingApplied,
									Integer minimumRebalancingAmount,
									List<Integer> minimumRebalancingLayers) {
		if (llmEnabled) {
			return loadLlmNarrative(metrics, targetConfig, actualDistribution, targetDistribution, proposedDistribution, withinTolerance,
					constraints, minimumSavingPlanRebalanced, minimumSavingPlanIncreaseLayerOne, minimumSavingPlanSize, proposedLayerOneAmount,
					instrumentDiscards, instrumentProposals, instrumentWeightingSummaries, minimumRebalancingApplied,
					minimumRebalancingAmount, minimumRebalancingLayers);
		}
		return buildFallbackNarrative(withinTolerance, actualDistribution, targetDistribution, targetConfig.acceptableVariancePct(),
				targetConfig.projectionHorizonMonths(), targetConfig.layerNames(), constraints, minimumSavingPlanRebalanced,
				minimumSavingPlanIncreaseLayerOne, minimumSavingPlanSize, minimumRebalancingApplied, minimumRebalancingAmount);
	}

	private String loadLlmNarrative(SavingPlanMetrics metrics,
									LayerTargetEffectiveConfig targetConfig,
									Map<Integer, Double> actualDistribution,
									Map<Integer, Double> targetDistribution,
									Map<Integer, Double> proposedDistribution,
									boolean withinTolerance,
									List<ConstraintResultDto> constraints,
									boolean minimumSavingPlanRebalanced,
									boolean minimumSavingPlanIncreaseLayerOne,
									Integer minimumSavingPlanSize,
									BigDecimal proposedLayerOneAmount,
									List<InstrumentDiscard> instrumentDiscards,
									List<InstrumentProposalDto> instrumentProposals,
									List<InstrumentRebalanceService.LayerWeightingSummary> instrumentWeightingSummaries,
									boolean minimumRebalancingApplied,
									Integer minimumRebalancingAmount,
									List<Integer> minimumRebalancingLayers) {
		String prompt = buildLlmPrompt(metrics, targetConfig, actualDistribution, targetDistribution, proposedDistribution, withinTolerance,
				constraints, minimumSavingPlanRebalanced, minimumSavingPlanIncreaseLayerOne, minimumSavingPlanSize, proposedLayerOneAmount,
				instrumentDiscards, instrumentProposals, instrumentWeightingSummaries, minimumRebalancingApplied,
				minimumRebalancingAmount, minimumRebalancingLayers);
		prompt = llmPromptPolicy == null
				? prompt
				: llmPromptPolicy.validatePrompt(prompt, LlmPromptPurpose.REBALANCER_NARRATIVE);
		if (prompt == null) {
			return null;
		}
		return llmNarrativeService.suggestSavingPlanNarrative(prompt);
	}

	private String buildLlmPrompt(SavingPlanMetrics metrics,
							  LayerTargetEffectiveConfig targetConfig,
							  Map<Integer, Double> actualDistribution,
							  Map<Integer, Double> targetDistribution,
							  Map<Integer, Double> proposedDistribution,
							  boolean withinTolerance,
							  List<ConstraintResultDto> constraints,
							  boolean minimumSavingPlanRebalanced,
							  boolean minimumSavingPlanIncreaseLayerOne,
							  Integer minimumSavingPlanSize,
							  BigDecimal proposedLayerOneAmount,
							  List<InstrumentDiscard> instrumentDiscards,
							  List<InstrumentProposalDto> instrumentProposals,
							  List<InstrumentRebalanceService.LayerWeightingSummary> instrumentWeightingSummaries,
							  boolean minimumRebalancingApplied,
							  Integer minimumRebalancingAmount,
							  List<Integer> minimumRebalancingLayers) {
		int horizon = normalizeProjectionHorizonMonths(targetConfig.projectionHorizonMonths());
		BigDecimal blendFactor = projectionBlendFactor(horizon, targetConfig.projectionBlendMin(), targetConfig.projectionBlendMax());
		StringBuilder builder = new StringBuilder();
		builder.append("Explain how the current savings plan distribution relates to the selected profile.\n");
		builder.append("Rules:\n");
		builder.append("- Refer to the provided layer names.\n");
		builder.append("- Mention whether the distribution is within tolerance and highlight the tolerance value.\n");
		builder.append("- Explain that layer budgets blend projected gap weights (using effective holdings) with the current savings plan distribution; longer horizons weight the current distribution more.\n");
		builder.append("- Explain that Target Total values combine current holdings with projected saving plan contributions and do not include market price changes.\n");
		builder.append("- Mention that only low or medium risk instruments are newly suggested; existing instruments remain but may be flagged with warnings.\n");
		builder.append("- If within tolerance, explicitly state that no changes are needed.\n");
		builder.append("- Mention any constraint violations clearly.\n");
		builder.append("- If minimum saving plan rebalancing occurred, explain that lower layers below the minimum were set to 0 and redistributed upward.\n");
		builder.append("- If the minimum saving plan size required increasing Layer 1 above the current total, explain the higher amount.\n");
		builder.append("- If minimum_rebalancing_applied is true, mention that adjustments below the minimum rebalancing amount are not proposed.\n");
		builder.append("- If minimum_rebalancing_layers or minimum_rebalancing_instruments are not none, mention them briefly.\n");
		builder.append("- If instrument_discards is not none, explain which instruments are proposed for discard and use the provided reasons.\n");
		builder.append("- If instrument_proposal_highlights is not none, add a short instrument-level section titled \"**Instrument highlights**\" with 2-4 sentences and optional bullets using lines starting with \"- \"; if highlights are none but instrument_proposals_summary is present, say instruments remain unchanged.\n");
		builder.append("- Use instrument_proposal_highlights as the only examples; do not introduce other instruments.\n");
		builder.append("- If any instrument has KB_WEIGHTED, explain the weighting method using kb_weighting_method and instrument_weighting_by_layer.\n");
		builder.append("- When KB_WEIGHTED applies, mention the factors listed per layer (ter, benchmark_overlap, region_overlap, holdings_overlap) and relate them to lower costs and lower overlap/redundancy.\n");
		builder.append("- If instrument_weighting_by_layer shows score_weighted=true, explain that assessment scores (lower is better) tilt weights toward lower scores.\n");
		builder.append("- If instrument_weighting_by_layer shows weighted=false and score_weighted=false, say equal weights were applied (single instrument or missing KB data).\n");
		builder.append("- Do not invent weighting factors or data that are not listed in the context.\n");
		builder.append("- Do not calculate or adjust numeric targets; they are fixed.\n");
		builder.append("Context:\n");
		builder.append("profile_key=").append(targetConfig.selectedProfileKey()).append("\n");
		builder.append("profile_display_name=").append(targetConfig.selectedProfile().getDisplayName()).append("\n");
		builder.append("tolerance_pct=").append(targetConfig.acceptableVariancePct()).append("\n");
		builder.append("projection_horizon_months=")
				.append(horizon)
				.append("\n");
		builder.append("projection_blend_min=")
				.append(normalizeProjectionBlendMin(targetConfig.projectionBlendMin()).stripTrailingZeros().toPlainString())
				.append("\n");
		builder.append("projection_blend_max=")
				.append(normalizeProjectionBlendMax(targetConfig.projectionBlendMax()).stripTrailingZeros().toPlainString())
				.append("\n");
		builder.append("projection_blend_factor=")
				.append(blendFactor.stripTrailingZeros().toPlainString())
				.append("\n");
		builder.append("minimum_saving_plan_size_eur=").append(minimumSavingPlanSize).append("\n");
		builder.append("minimum_saving_plan_rebalanced=").append(minimumSavingPlanRebalanced).append("\n");
		builder.append("minimum_saving_plan_increase_layer_one=").append(minimumSavingPlanIncreaseLayerOne).append("\n");
		builder.append("proposed_layer_one_amount_eur=").append(proposedLayerOneAmount).append("\n");
		builder.append("minimum_rebalancing_amount_eur=").append(minimumRebalancingAmount).append("\n");
		builder.append("minimum_rebalancing_applied=").append(minimumRebalancingApplied).append("\n");
		builder.append("within_tolerance=").append(withinTolerance).append("\n");
		builder.append("monthly_total_amount_eur=").append(metrics.monthlyTotal()).append("\n");
		builder.append("layer_names=").append(targetConfig.layerNames()).append("\n");
		builder.append("actual_distribution=").append(actualDistribution).append("\n");
		builder.append("target_distribution=").append(targetDistribution).append("\n");
		builder.append("proposed_distribution=").append(proposedDistribution).append("\n");
		builder.append("constraints=").append(constraintSummaries(constraints)).append("\n");
		builder.append("instrument_discards=").append(formatInstrumentDiscards(instrumentDiscards)).append("\n");
		builder.append("instrument_proposals_summary=").append(buildInstrumentProposalSummary(instrumentProposals)).append("\n");
		builder.append("instrument_proposal_highlights=").append(formatInstrumentProposalHighlights(instrumentProposals)).append("\n");
		builder.append("instrument_weighting_by_layer=").append(formatInstrumentWeightingSummaries(instrumentWeightingSummaries, instrumentProposals)).append("\n");
		builder.append("minimum_rebalancing_layers=").append(formatMinimumRebalancingLayers(minimumRebalancingLayers, targetConfig.layerNames())).append("\n");
		builder.append("minimum_rebalancing_instruments=").append(formatMinimumRebalancingInstruments(instrumentProposals)).append("\n");
		if (hasReasonCode(instrumentProposals, "KB_WEIGHTED")) {
			builder.append("kb_weighting_method=score = (1 / (1 + TER)) * (1 - redundancy); redundancy is average overlap of benchmark, regions, and top holdings when available; weights are normalized scores.\n");
		}
		if (hasReasonCode(instrumentProposals, "SCORE_WEIGHTED")) {
			builder.append("score_weighting_method=assessment score factor (lower score -> higher weight), applied as a multiplier and normalized within each layer.\n");
		}
		return builder.toString();
	}

	private List<InstrumentDiscard> buildInstrumentDiscards(List<InstrumentProposalDto> proposals) {
		if (proposals == null || proposals.isEmpty()) {
			return List.of();
		}
		List<InstrumentDiscard> discards = new ArrayList<>();
		for (InstrumentProposalDto proposal : proposals) {
			if (proposal == null || proposal.getProposedAmountEur() == null) {
				continue;
			}
			if (proposal.getProposedAmountEur() > 0.0d) {
				continue;
			}
			List<String> reasons = mapDiscardReasons(proposal.getReasonCodes());
			discards.add(new InstrumentDiscard(proposal.getIsin(), proposal.getInstrumentName(), reasons));
		}
		return discards;
	}

	private List<String> mapDiscardReasons(List<String> reasonCodes) {
		if (reasonCodes == null || reasonCodes.isEmpty()) {
			return List.of("unspecified reason");
		}
		List<String> labels = new ArrayList<>();
		for (String code : reasonCodes) {
			if (code == null) {
				continue;
			}
			switch (code) {
				case "MIN_AMOUNT_DROPPED" -> labels.add("below minimum saving plan size");
				case "LAYER_BUDGET_ZERO" -> labels.add("layer budget is zero");
				default -> {
				}
			}
		}
		if (!labels.isEmpty()) {
			return labels;
		}
		List<String> fallback = new ArrayList<>();
		for (String code : reasonCodes) {
			if (code != null && !code.isBlank()) {
				fallback.add(code);
			}
		}
		return fallback.isEmpty() ? List.of("unspecified reason") : fallback;
	}

	private String formatInstrumentDiscards(List<InstrumentDiscard> discards) {
		if (discards == null || discards.isEmpty()) {
			return "none";
		}
		StringBuilder builder = new StringBuilder();
		builder.append("[");
		for (int i = 0; i < discards.size(); i++) {
			InstrumentDiscard discard = discards.get(i);
			if (i > 0) {
				builder.append(", ");
			}
			builder.append("{isin=").append(discard.isin());
			if (discard.name() != null && !discard.name().isBlank()) {
				builder.append(", name=").append(discard.name());
			}
			builder.append(", reasons=").append(discard.reasons());
			builder.append("}");
		}
		builder.append("]");
		return builder.toString();
	}

	private String formatMinimumRebalancingLayers(List<Integer> layers, Map<Integer, String> layerNames) {
		if (layers == null || layers.isEmpty()) {
			return "none";
		}
		List<String> labels = new ArrayList<>();
		for (Integer layer : layers) {
			if (layer == null) {
				continue;
			}
			String label = layerNames == null ? "Layer " + layer : layerNames.getOrDefault(layer, "Layer " + layer);
			labels.add(label);
		}
		return labels.isEmpty() ? "none" : labels.toString();
	}

	private String formatMinimumRebalancingInstruments(List<InstrumentProposalDto> proposals) {
		if (proposals == null || proposals.isEmpty()) {
			return "none";
		}
		List<String> isins = new ArrayList<>();
		for (InstrumentProposalDto proposal : proposals) {
			if (proposal == null || proposal.getReasonCodes() == null) {
				continue;
			}
			if (proposal.getReasonCodes().contains("MIN_REBALANCE_AMOUNT")) {
				if (proposal.getIsin() != null) {
					isins.add(proposal.getIsin());
				}
			}
		}
		if (isins.isEmpty()) {
			return "none";
		}
		if (isins.size() > INSTRUMENT_HIGHLIGHT_LIMIT) {
			isins = isins.subList(0, INSTRUMENT_HIGHLIGHT_LIMIT);
		}
		return isins.toString();
	}

	private String buildInstrumentProposalSummary(List<InstrumentProposalDto> proposals) {
		if (proposals == null || proposals.isEmpty()) {
			return "none";
		}
		int total = 0;
		int increases = 0;
		int decreases = 0;
		int unchanged = 0;
		int kbWeighted = 0;
		int scoreWeighted = 0;
		int equalWeight = 0;
		int minDropped = 0;
		int budgetZero = 0;
		int noChange = 0;
		int minRebalance = 0;
		int gapSuggestions = 0;

		for (InstrumentProposalDto proposal : proposals) {
			if (proposal == null) {
				continue;
			}
			total += 1;
			Double delta = proposal.getDeltaEur();
			if (isZeroAmount(delta)) {
				unchanged += 1;
			} else if (delta != null && delta > 0.0d) {
				increases += 1;
			} else {
				decreases += 1;
			}
			List<String> codes = proposal.getReasonCodes();
			if (codes == null) {
				continue;
			}
			for (String code : codes) {
				if (code == null) {
					continue;
				}
				switch (code) {
					case "KB_WEIGHTED" -> kbWeighted += 1;
					case "SCORE_WEIGHTED" -> scoreWeighted += 1;
					case "EQUAL_WEIGHT" -> equalWeight += 1;
					case "MIN_AMOUNT_DROPPED" -> minDropped += 1;
					case "LAYER_BUDGET_ZERO" -> budgetZero += 1;
					case "NO_CHANGE_WITHIN_TOLERANCE" -> noChange += 1;
					case "MIN_REBALANCE_AMOUNT" -> minRebalance += 1;
					case REASON_GAP_SUGGESTION -> gapSuggestions += 1;
					default -> {
					}
				}
			}
		}

		return "{total=" + total
				+ ", increases=" + increases
				+ ", decreases=" + decreases
				+ ", unchanged=" + unchanged
				+ ", kb_weighted=" + kbWeighted
				+ ", score_weighted=" + scoreWeighted
				+ ", equal_weight=" + equalWeight
				+ ", min_dropped=" + minDropped
				+ ", layer_budget_zero=" + budgetZero
				+ ", no_change=" + noChange
				+ ", min_rebalance=" + minRebalance
				+ ", gap_suggestions=" + gapSuggestions + "}";
	}

	private String formatInstrumentProposalHighlights(List<InstrumentProposalDto> proposals) {
		if (proposals == null || proposals.isEmpty()) {
			return "none";
		}
		List<InstrumentProposalDto> candidates = new ArrayList<>();
		for (InstrumentProposalDto proposal : proposals) {
			if (proposal == null) {
				continue;
			}
			if (isZeroAmount(proposal.getDeltaEur())) {
				continue;
			}
			candidates.add(proposal);
		}
		if (candidates.isEmpty()) {
			return "none";
		}
		candidates.sort((left, right) -> {
			double leftDelta = left == null || left.getDeltaEur() == null ? 0.0d : Math.abs(left.getDeltaEur());
			double rightDelta = right == null || right.getDeltaEur() == null ? 0.0d : Math.abs(right.getDeltaEur());
			int cmp = Double.compare(rightDelta, leftDelta);
			if (cmp != 0) {
				return cmp;
			}
			String leftIsin = left == null ? null : left.getIsin();
			String rightIsin = right == null ? null : right.getIsin();
			if (leftIsin == null && rightIsin == null) {
				return 0;
			}
			if (leftIsin == null) {
				return 1;
			}
			if (rightIsin == null) {
				return -1;
			}
			return leftIsin.compareTo(rightIsin);
		});
		int limit = Math.min(INSTRUMENT_HIGHLIGHT_LIMIT, candidates.size());
		StringBuilder builder = new StringBuilder();
		builder.append("[");
		for (int i = 0; i < limit; i++) {
			InstrumentProposalDto proposal = candidates.get(i);
			if (i > 0) {
				builder.append(", ");
			}
			builder.append("{isin=").append(proposal.getIsin());
			String name = normalizePromptText(proposal.getInstrumentName());
			if (name != null) {
				builder.append(", name=").append(name);
			}
			if (proposal.getLayer() != null) {
				builder.append(", layer=").append(proposal.getLayer());
			}
			builder.append(", current=").append(formatPromptAmount(proposal.getCurrentAmountEur()));
			builder.append(", proposed=").append(formatPromptAmount(proposal.getProposedAmountEur()));
			builder.append(", delta=").append(formatPromptAmount(proposal.getDeltaEur()));
			builder.append(", reasons=").append(formatReasonCodes(proposal.getReasonCodes()));
			builder.append("}");
		}
		builder.append("]");
		return builder.toString();
	}

	private String formatInstrumentWeightingSummaries(List<InstrumentRebalanceService.LayerWeightingSummary> summaries,
													  List<InstrumentProposalDto> proposals) {
		if (summaries == null || summaries.isEmpty()) {
			return "none";
		}
		Map<String, String> namesByIsin = new HashMap<>();
		if (proposals != null) {
			for (InstrumentProposalDto proposal : proposals) {
				if (proposal == null || proposal.getIsin() == null) {
					continue;
				}
				String name = normalizePromptText(proposal.getInstrumentName());
				if (name != null) {
					namesByIsin.put(proposal.getIsin(), name);
				}
			}
		}
		StringBuilder builder = new StringBuilder();
		builder.append("[");
		int index = 0;
		for (InstrumentRebalanceService.LayerWeightingSummary summary : summaries) {
			if (summary == null) {
				continue;
			}
			if (index > 0) {
				builder.append(", ");
			}
			builder.append("{layer=").append(summary.layer());
			builder.append(", instruments=").append(summary.instrumentCount());
			builder.append(", weighted=").append(summary.weighted());
			builder.append(", score_weighted=").append(summary.scoreWeighted());
			List<String> factors = weightingFactors(summary);
			builder.append(", factors=").append(factors.isEmpty() ? "[]" : factors);
			String note = weightingNote(summary);
			if (note != null) {
				builder.append(", note=").append(note);
			}
			builder.append(", weights=").append(formatWeightEntries(summary.weights(), namesByIsin));
			builder.append("}");
			index++;
		}
		builder.append("]");
		return builder.toString();
	}

	private List<String> weightingFactors(InstrumentRebalanceService.LayerWeightingSummary summary) {
		List<String> factors = new ArrayList<>();
		if (summary.scoreWeighted()) {
			factors.add("assessment_score");
		}
		if (summary.costUsed()) {
			factors.add("ter");
		}
		if (summary.benchmarkUsed()) {
			factors.add("benchmark_overlap");
		}
		if (summary.regionsUsed()) {
			factors.add("region_overlap");
		}
		if (summary.holdingsUsed()) {
			factors.add("holdings_overlap");
		}
		return factors;
	}

	private String weightingNote(InstrumentRebalanceService.LayerWeightingSummary summary) {
		if (summary.weighted()) {
			return null;
		}
		if (summary.scoreWeighted()) {
			return "score-weighted (assessment scores)";
		}
		if (summary.instrumentCount() <= 1) {
			return "single instrument";
		}
		return "equal weight (no KB weighting factors)";
	}

	private String formatWeightEntries(Map<String, BigDecimal> weights, Map<String, String> namesByIsin) {
		if (weights == null || weights.isEmpty()) {
			return "[]";
		}
		List<Map.Entry<String, BigDecimal>> entries = new ArrayList<>(weights.entrySet());
		entries.sort((left, right) -> {
			BigDecimal leftValue = left.getValue() == null ? BigDecimal.ZERO : left.getValue();
			BigDecimal rightValue = right.getValue() == null ? BigDecimal.ZERO : right.getValue();
			int cmp = rightValue.compareTo(leftValue);
			if (cmp != 0) {
				return cmp;
			}
			return left.getKey().compareTo(right.getKey());
		});
		int limit = Math.min(INSTRUMENT_WEIGHT_LIMIT, entries.size());
		StringBuilder builder = new StringBuilder();
		builder.append("[");
		for (int i = 0; i < limit; i++) {
			Map.Entry<String, BigDecimal> entry = entries.get(i);
			if (i > 0) {
				builder.append(", ");
			}
			builder.append("{isin=").append(entry.getKey());
			String name = namesByIsin.get(entry.getKey());
			if (name != null) {
				builder.append(", name=").append(name);
			}
			builder.append(", weight=").append(formatPromptWeight(entry.getValue()));
			builder.append("}");
		}
		builder.append("]");
		return builder.toString();
	}

	private String formatReasonCodes(List<String> codes) {
		if (codes == null || codes.isEmpty()) {
			return "[]";
		}
		return codes.toString();
	}

	private String formatPromptAmount(Double value) {
		if (value == null) {
			return "n/a";
		}
		return String.format(Locale.ROOT, "%.2f", value);
	}

	private String formatPromptWeight(BigDecimal value) {
		if (value == null) {
			return "n/a";
		}
		return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
	}

	private String normalizePromptText(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String cleaned = value.replaceAll("\\s+", " ").trim();
		return cleaned.isEmpty() ? null : cleaned;
	}

	private boolean hasReasonCode(List<InstrumentProposalDto> proposals, String code) {
		if (proposals == null || proposals.isEmpty() || code == null) {
			return false;
		}
		for (InstrumentProposalDto proposal : proposals) {
			if (proposal == null || proposal.getReasonCodes() == null) {
				continue;
			}
			for (String reason : proposal.getReasonCodes()) {
				if (code.equals(reason)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean isZeroAmount(Double value) {
		return value == null || Math.abs(value) < 0.005d;
	}

	private record InstrumentDiscard(String isin, String name, List<String> reasons) {
	}

	private String constraintSummaries(List<ConstraintResultDto> constraints) {
		if (constraints == null || constraints.isEmpty()) {
			return "none";
		}
		StringBuilder builder = new StringBuilder();
		for (ConstraintResultDto constraint : constraints) {
			builder.append(constraint.getName()).append(":").append(constraint.isOk() ? "ok" : "violated");
			builder.append("(").append(constraint.getDetails()).append(")");
			builder.append(", ");
		}
		if (builder.length() == 0) {
			return "none";
		}
		return builder.substring(0, builder.length() - 2);
	}

	private String buildFallbackNarrative(boolean withinTolerance,
									  Map<Integer, Double> actualDistribution,
									  Map<Integer, Double> targetDistribution,
									  BigDecimal variancePct,
									  Integer projectionHorizonMonths,
									  Map<Integer, String> layerNames,
									  List<ConstraintResultDto> constraints,
									  boolean minimumSavingPlanRebalanced,
									  boolean minimumSavingPlanIncreaseLayerOne,
									  Integer minimumSavingPlanSize,
									  boolean minimumRebalancingApplied,
									  Integer minimumRebalancingAmount) {
		int horizon = normalizeProjectionHorizonMonths(projectionHorizonMonths);
		if (withinTolerance) {
			String toleranceText = variancePct == null ? "3.0" : variancePct.stripTrailingZeros().toPlainString();
			return "Savings plan structure matches the selected profile within tolerance (<= " + toleranceText + "%). "
					+ "Projection horizon: " + horizon + " months. Longer horizons keep proposals closer to the current savings plan distribution. "
					+ "Target totals combine current holdings with projected savings contributions and do not include market price changes. "
					+ "New instrument suggestions only include low or medium risk instruments; existing instruments may be flagged if they exceed the risk threshold.";
		}
		int layer = findLayerWithMaxDeviation(actualDistribution, targetDistribution);
		double actual = actualDistribution.getOrDefault(layer, 0.0d);
		double target = targetDistribution.getOrDefault(layer, 0.0d);
		String label = layerNames == null ? "Layer " + layer : layerNames.getOrDefault(layer, "Layer " + layer);
		String toleranceText = variancePct == null ? "3.0" : variancePct.stripTrailingZeros().toPlainString();
		StringBuilder builder = new StringBuilder();
		builder.append(String.format("%s deviates by %.2fpp from the target distribution (tolerance %s%%).", label, Math.abs(actual - target), toleranceText));
		constraints.stream()
				.filter(c -> !c.isOk())
				.findFirst()
				.ifPresent(violation -> builder.append(" ").append(violation.getDetails()));
		if (minimumSavingPlanRebalanced) {
			builder.append(" Amounts were rebalanced to respect the minimum saving plan size");
			if (minimumSavingPlanSize != null) {
				builder.append(" of ").append(minimumSavingPlanSize).append(" EUR");
			}
			builder.append(".");
		}
		if (minimumSavingPlanIncreaseLayerOne) {
			builder.append(" Total savings plan amount is below the minimum saving plan size");
			if (minimumSavingPlanSize != null) {
				builder.append(" of ").append(minimumSavingPlanSize).append(" EUR");
			}
			builder.append("; increase Layer 1 accordingly.");
		}
		if (minimumRebalancingApplied) {
			builder.append(" Adjustments below the minimum rebalancing amount");
			if (minimumRebalancingAmount != null) {
				builder.append(" of ").append(minimumRebalancingAmount).append(" EUR");
			}
			builder.append(" are not proposed.");
		}
		builder.append(" Projection horizon: ").append(horizon).append(" months.")
				.append(" Longer horizons keep proposals closer to the current savings plan distribution.")
				.append(" Target totals combine current holdings with projected savings contributions and do not include market price changes.")
				.append(" New instrument suggestions only include low or medium risk instruments; existing instruments may be flagged if they exceed the risk threshold.");
		return builder.toString();
	}

	private List<SavingPlanProposalLayerDto> buildProposalLayers(SavingPlanMetrics metrics,
									   LayerTargetEffectiveConfig targetConfig,
									   Map<Integer, BigDecimal> actualDistribution,
									   Map<Integer, BigDecimal> targetWeights,
									   Map<Integer, BigDecimal> proposalAmounts,
									   BigDecimal monthlyTotal,
									   Map<Integer, BigDecimal> holdingsByLayer) {
		List<SavingPlanProposalLayerDto> layers = new ArrayList<>();
		Map<Integer, String> layerNames = targetConfig.layerNames();
		Map<Integer, BigDecimal> holdings = normalizeHoldingsByLayer(holdingsByLayer);
		int horizon = normalizeProjectionHorizonMonths(targetConfig.projectionHorizonMonths());
		Map<Integer, BigDecimal> targetTotals = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal holding = holdings.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal proposed = proposalAmounts.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal projected = proposed.multiply(BigDecimal.valueOf(horizon));
			targetTotals.put(layer, holding.add(projected));
		}
		BigDecimal targetTotalSum = sumAmounts(targetTotals);
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal currentAmount = metrics.monthlyByLayer().getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal currentWeight = actualDistribution.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal targetWeight = targetWeights.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal proposedAmount = proposalAmounts.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal delta = proposedAmount.subtract(currentAmount);
			BigDecimal targetTotalAmount = targetTotals.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal targetTotalWeight = targetTotalSum.signum() <= 0
					? BigDecimal.ZERO
					: targetTotalAmount.divide(targetTotalSum, 6, RoundingMode.HALF_UP);
			String name = layerNames == null ? "Layer " + layer : layerNames.getOrDefault(layer, "Layer " + layer);
			layers.add(new SavingPlanProposalLayerDto(
					layer,
					name,
					toAmount(currentAmount),
					toWeightPct(currentWeight),
					toWeightPct(targetWeight),
					toAmount(proposedAmount),
					toAmount(delta),
					toWeightPct(targetTotalWeight),
					toAmount(targetTotalAmount)
			));
		}
		return layers;
	}

	private List<InstrumentProposalDto> buildGapInstrumentProposals(SavingPlanMetrics metrics,
									  Map<Integer, BigDecimal> proposalAmounts,
									  LayerTargetEffectiveConfig targetConfig,
									  List<SavingPlanInstrument> savingPlanInstruments,
									  InstrumentProposalGatingDto instrumentGating) {
		if (instrumentGating == null
				|| !instrumentGating.knowledgeBaseEnabled()
				|| !instrumentGating.kbComplete()) {
			return List.of();
		}
		if (metrics == null || proposalAmounts == null || proposalAmounts.isEmpty()) {
			return List.of();
		}
		Map<Integer, BigDecimal> currentByLayer = metrics.monthlyByLayer() == null
				? Map.of()
				: metrics.monthlyByLayer();
		Map<Integer, BigDecimal> budgets = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal current = currentByLayer.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal proposed = proposalAmounts.getOrDefault(layer, BigDecimal.ZERO);
			if (current.signum() <= 0 && proposed.signum() > 0) {
				budgets.put(layer, proposed);
			}
		}
		if (budgets.isEmpty()) {
			return List.of();
		}
		LayerTargetRiskThresholds riskThresholds = resolveRiskThresholds(targetConfig);
		Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer =
				resolveRiskThresholdsByLayer(targetConfig, riskThresholds);
		int minSaving = targetConfig == null || targetConfig.minimumSavingPlanSize() == null
				? 1
				: targetConfig.minimumSavingPlanSize();
		int minRebalance = targetConfig == null || targetConfig.minimumRebalancingAmount() == null
				? 1
				: targetConfig.minimumRebalancingAmount();
		Map<Integer, Integer> maxSavingPlans = resolveMaxSavingPlans();
		Set<String> existingIsins = new LinkedHashSet<>();
		List<AssessorEngine.SavingPlanItem> savingPlanItems = new ArrayList<>();
		if (savingPlanInstruments != null) {
			for (SavingPlanInstrument instrument : savingPlanInstruments) {
				if (instrument == null || instrument.isin() == null) {
					continue;
				}
				existingIsins.add(normalizeIsin(instrument.isin()));
				savingPlanItems.add(new AssessorEngine.SavingPlanItem(
						normalizeIsin(instrument.isin()),
						null,
						instrument.monthlyAmount(),
						instrument.layer()
				));
			}
		}
		AssessorInstrumentSuggestionService.SuggestionResult suggestions = instrumentSuggestionService.suggest(
				new AssessorInstrumentSuggestionService.SuggestionRequest(
					savingPlanItems,
					existingIsins,
					budgets,
					Map.of(),
					minSaving,
					minRebalance,
					DEFAULT_MIN_INSTRUMENT_AMOUNT,
					maxSavingPlans,
					Set.of(),
					AssessorGapDetectionPolicy.SAVING_PLAN_GAPS,
					riskThresholds,
					riskThresholdsByLayer
				)
		);
		if (suggestions == null || suggestions.savingPlanSuggestions().isEmpty()) {
			return List.of();
		}
		List<InstrumentProposalDto> proposals = new ArrayList<>();
		for (AssessorInstrumentSuggestionService.NewInstrumentSuggestion suggestion : suggestions.savingPlanSuggestions()) {
			if (suggestion == null || suggestion.amount() == null || suggestion.amount().signum() <= 0) {
				continue;
			}
			Double amount = toAmount(suggestion.amount());
			proposals.add(new InstrumentProposalDto(
					suggestion.isin(),
					suggestion.name(),
					0.0d,
					amount,
					amount,
					suggestion.layer(),
					List.of(REASON_GAP_SUGGESTION)
			));
		}
		return proposals;
	}

	private void removeLayerWarningsForGapSuggestions(List<InstrumentRebalanceService.InstrumentWarning> warningDetails,
															 List<InstrumentProposalDto> gapProposals) {
		if (warningDetails == null || warningDetails.isEmpty() || gapProposals == null || gapProposals.isEmpty()) {
			return;
		}
		Set<Integer> layers = new LinkedHashSet<>();
		for (InstrumentProposalDto proposal : gapProposals) {
			if (proposal != null && proposal.getLayer() != null) {
				layers.add(proposal.getLayer());
			}
		}
		if (layers.isEmpty()) {
			return;
		}
		warningDetails.removeIf(warning -> warning != null
				&& "LAYER_NO_INSTRUMENTS".equals(warning.code())
				&& warning.layer() != null
				&& layers.contains(warning.layer()));
	}

	private void appendRiskWarnings(List<InstrumentRebalanceService.InstrumentWarning> warningDetails,
									  List<SavingPlanInstrument> savingPlanInstruments,
									  LayerTargetEffectiveConfig targetConfig,
									  InstrumentProposalGatingDto instrumentGating) {
		if (warningDetails == null || savingPlanInstruments == null || savingPlanInstruments.isEmpty()) {
			return;
		}
		if (instrumentGating != null && (!instrumentGating.knowledgeBaseEnabled() || !instrumentGating.kbComplete())) {
			return;
		}
		LayerTargetRiskThresholds thresholds = resolveRiskThresholds(targetConfig);
		Map<Integer, LayerTargetRiskThresholds> thresholdsByLayer =
				resolveRiskThresholdsByLayer(targetConfig, thresholds);
		Map<String, Integer> scores = instrumentAssessmentService.assessScores(
				savingPlanInstruments.stream()
						.filter(instrument -> instrument != null && instrument.isin() != null)
						.map(instrument -> normalizeIsin(instrument.isin()))
						.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
				thresholds,
				thresholdsByLayer
		);
		if (scores.isEmpty()) {
			return;
		}
		Set<String> warned = new LinkedHashSet<>();
		for (SavingPlanInstrument instrument : savingPlanInstruments) {
			if (instrument == null || instrument.isin() == null) {
				continue;
			}
			String isin = normalizeIsin(instrument.isin());
			if (isin == null || warned.contains(isin)) {
				continue;
			}
			Integer score = scores.get(isin);
			LayerTargetRiskThresholds layerThresholds =
					RiskThresholdsUtil.resolveForLayer(thresholdsByLayer, thresholds, instrument.layer());
			double cutoff = layerThresholds.getHighMin();
			if (score != null && score >= cutoff) {
				String name = instrument.name() == null || instrument.name().isBlank() ? isin : instrument.name();
				warningDetails.add(new InstrumentRebalanceService.InstrumentWarning(
						WARNING_RISK_NOT_ACCEPTABLE,
						String.format("Instrument %s (%s) exceeds acceptable risk for the profile (score %d >= %d).",
								name, isin, score, (int) Math.ceil(cutoff)),
						instrument.layer()
				));
				warned.add(isin);
			}
		}
	}

	private Map<Integer, Integer> resolveMaxSavingPlans() {
		LayerTargetConfigResponseDto config = layerTargetConfigService.getConfigResponse();
		Map<Integer, Integer> raw = config == null ? null : config.getMaxSavingPlansPerLayer();
		return normalizeMaxSavingPlans(raw);
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

	private LayerTargetRiskThresholds resolveRiskThresholds(LayerTargetEffectiveConfig targetConfig) {
		LayerTargetRiskThresholds thresholds = null;
		if (targetConfig != null && targetConfig.selectedProfile() != null) {
			thresholds = targetConfig.selectedProfile().getRiskThresholds();
		}
		return RiskThresholdsUtil.normalize(thresholds);
	}

	private Map<Integer, LayerTargetRiskThresholds> resolveRiskThresholdsByLayer(
			LayerTargetEffectiveConfig targetConfig,
			LayerTargetRiskThresholds fallback) {
		Map<Integer, LayerTargetRiskThresholds> thresholdsByLayer = null;
		if (targetConfig != null && targetConfig.selectedProfile() != null) {
			thresholdsByLayer = targetConfig.selectedProfile().getRiskThresholdsByLayer();
		}
		return RiskThresholdsUtil.normalizeByLayer(thresholdsByLayer, fallback);
	}

	private List<String> buildToleranceNotes(boolean withinTolerance, BigDecimal variancePct) {
		if (!withinTolerance || variancePct == null) {
			return Collections.emptyList();
		}
		String pct = variancePct.stripTrailingZeros().toPlainString();
		return List.of("Existing savings plan distribution is within tolerance (<= " + pct + "%) and does not need adjustment.");
	}

	private String buildMinimumSavingPlanIncreaseNotice(Integer minimumSavingPlanSize) {
		if (minimumSavingPlanSize != null) {
			return "Total savings plan amount is below the minimum saving plan size; increase Layer 1 to " + minimumSavingPlanSize + " EUR.";
		}
		return "Total savings plan amount is below the minimum saving plan size; increase Layer 1 accordingly.";
	}

	private String buildMinimumRebalancingNotice(Integer minimumRebalancingAmount) {
		if (minimumRebalancingAmount != null) {
			return "Adjustments below " + minimumRebalancingAmount + " EUR are not proposed due to the minimum rebalancing amount.";
		}
		return "Adjustments below the minimum rebalancing amount are not proposed.";
	}

	private String buildRecommendation(boolean withinTolerance,
									   boolean minimumSavingPlanRebalanced,
									   boolean minimumRebalancingApplied,
									   Integer minimumRebalancingAmount) {
		if (withinTolerance) {
			return "No change needed; distribution is within tolerance.";
		}
		if (minimumSavingPlanRebalanced) {
			return "Rebalance contributions toward the selected profile while respecting the minimum saving plan size.";
		}
		if (minimumRebalancingApplied) {
			if (minimumRebalancingAmount != null) {
				return "Rebalance contributions toward the selected profile while skipping adjustments below " + minimumRebalancingAmount + " EUR.";
			}
			return "Rebalance contributions toward the selected profile while skipping very small adjustments.";
		}
		return "Rebalance contributions toward the selected profile.";
	}

	private Map<Integer, BigDecimal> reconcileProposalAmountsWithInstruments(Map<Integer, BigDecimal> proposalAmounts,
																			 List<InstrumentProposalDto> instrumentProposals,
																			 BigDecimal expectedTotal) {
		if (proposalAmounts == null || proposalAmounts.isEmpty() || instrumentProposals == null || instrumentProposals.isEmpty()) {
			return proposalAmounts;
		}
		Map<Integer, BigDecimal> totals = new LinkedHashMap<>();
		Map<Integer, Integer> counts = new HashMap<>();
		for (InstrumentProposalDto proposal : instrumentProposals) {
			if (proposal == null) {
				continue;
			}
			Integer layer = proposal.getLayer();
			if (layer == null) {
				continue;
			}
			Double amount = proposal.getProposedAmountEur();
			BigDecimal value = amount == null || amount < 0 ? BigDecimal.ZERO : BigDecimal.valueOf(amount);
			totals.merge(layer, value, BigDecimal::add);
			counts.merge(layer, 1, Integer::sum);
		}
		if (totals.isEmpty()) {
			return proposalAmounts;
		}
		BigDecimal baseline = expectedTotal == null ? sumAmounts(proposalAmounts) : expectedTotal;
		BigDecimal instrumentTotal = sumAmounts(totals);
		if (baseline == null) {
			baseline = sumAmounts(proposalAmounts);
		}
		if (instrumentTotal.subtract(baseline).abs().compareTo(new BigDecimal("0.01")) > 0) {
			return proposalAmounts;
		}
		Map<Integer, BigDecimal> adjusted = new LinkedHashMap<>(proposalAmounts);
		boolean updated = false;
		for (Map.Entry<Integer, BigDecimal> entry : totals.entrySet()) {
			Integer layer = entry.getKey();
			if (counts.getOrDefault(layer, 0) < 1) {
				continue;
			}
			BigDecimal existing = adjusted.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal value = entry.getValue();
			if (existing.compareTo(value) != 0) {
				adjusted.put(layer, value);
				updated = true;
			}
		}
		return updated ? Map.copyOf(adjusted) : proposalAmounts;
	}

	private BigDecimal sumAmounts(Map<Integer, BigDecimal> amounts) {
		if (amounts == null || amounts.isEmpty()) {
			return BigDecimal.ZERO;
		}
		BigDecimal total = BigDecimal.ZERO;
		for (int layer = 1; layer <= 5; layer++) {
			total = total.add(amounts.getOrDefault(layer, BigDecimal.ZERO));
		}
		return total;
	}

	private Map<Integer, BigDecimal> normalizeTargetWeights(Map<Integer, BigDecimal> raw) {
		Map<Integer, BigDecimal> normalized = new LinkedHashMap<>();
		if (raw != null) {
			for (int layer = 1; layer <= 5; layer++) {
				normalized.put(layer, raw.getOrDefault(layer, BigDecimal.ZERO));
			}
		} else {
			for (int layer = 1; layer <= 5; layer++) {
				normalized.put(layer, BigDecimal.ZERO);
			}
		}
		BigDecimal total = normalized.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		if (total.signum() > 0 && total.compareTo(BigDecimal.ONE) != 0) {
			for (Map.Entry<Integer, BigDecimal> entry : normalized.entrySet()) {
				entry.setValue(entry.getValue().divide(total, 6, RoundingMode.HALF_UP));
			}
		}
		return Map.copyOf(normalized);
	}

	private Map<Integer, BigDecimal> normalizeHoldingsByLayer(Map<Integer, BigDecimal> holdingsByLayer) {
		Map<Integer, BigDecimal> normalized = initLayerAmounts();
		if (holdingsByLayer == null || holdingsByLayer.isEmpty()) {
			return normalized;
		}
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal value = holdingsByLayer.getOrDefault(layer, BigDecimal.ZERO);
			normalized.put(layer, value == null ? BigDecimal.ZERO : value);
		}
		return normalized;
	}

	private int normalizeProjectionHorizonMonths(Integer projectionHorizonMonths) {
		if (projectionHorizonMonths == null) {
			return DEFAULT_PROJECTION_HORIZON_MONTHS;
		}
		if (projectionHorizonMonths < MIN_PROJECTION_HORIZON_MONTHS) {
			return MIN_PROJECTION_HORIZON_MONTHS;
		}
		if (projectionHorizonMonths > MAX_PROJECTION_HORIZON_MONTHS) {
			return MAX_PROJECTION_HORIZON_MONTHS;
		}
		return projectionHorizonMonths;
	}

	private BigDecimal projectionBlendFactor(int projectionHorizonMonths,
								 BigDecimal projectionBlendMin,
								 BigDecimal projectionBlendMax) {
		BigDecimal min = normalizeProjectionBlendMin(projectionBlendMin);
		BigDecimal max = normalizeProjectionBlendMax(projectionBlendMax);
		if (max.compareTo(min) < 0) {
			max = min;
		}
		int bounded = Math.max(MIN_PROJECTION_HORIZON_MONTHS,
				Math.min(MAX_PROJECTION_HORIZON_MONTHS, projectionHorizonMonths));
		if (bounded <= MIN_PROJECTION_HORIZON_MONTHS) {
			return min;
		}
		if (bounded >= MAX_PROJECTION_HORIZON_MONTHS) {
			return max;
		}
		BigDecimal span = BigDecimal.valueOf(MAX_PROJECTION_HORIZON_MONTHS - MIN_PROJECTION_HORIZON_MONTHS);
		BigDecimal offset = BigDecimal.valueOf(bounded - MIN_PROJECTION_HORIZON_MONTHS);
		BigDecimal ratio = offset.divide(span, 6, RoundingMode.HALF_UP);
		BigDecimal diff = max.subtract(min);
		return min.add(diff.multiply(ratio)).setScale(6, RoundingMode.HALF_UP);
	}

	private BigDecimal normalizeProjectionBlendMin(BigDecimal value) {
		if (value == null) {
			return PROJECTION_BLEND_MIN;
		}
		if (value.compareTo(BigDecimal.ZERO) < 0) {
			return BigDecimal.ZERO;
		}
		if (value.compareTo(BigDecimal.ONE) > 0) {
			return BigDecimal.ONE;
		}
		return value;
	}

	private BigDecimal normalizeProjectionBlendMax(BigDecimal value) {
		if (value == null) {
			return PROJECTION_BLEND_MAX;
		}
		if (value.compareTo(BigDecimal.ZERO) < 0) {
			return BigDecimal.ZERO;
		}
		if (value.compareTo(BigDecimal.ONE) > 0) {
			return BigDecimal.ONE;
		}
		return value;
	}

	private Map<Integer, BigDecimal> blendWeights(Map<Integer, BigDecimal> gapWeights,
								 Map<Integer, BigDecimal> currentWeights,
								 BigDecimal blendFactor) {
		Map<Integer, BigDecimal> blended = new HashMap<>();
		BigDecimal clamped = blendFactor == null ? BigDecimal.ZERO : blendFactor;
		if (clamped.signum() < 0) {
			clamped = BigDecimal.ZERO;
		} else if (clamped.compareTo(BigDecimal.ONE) > 0) {
			clamped = BigDecimal.ONE;
		}
		BigDecimal inverse = BigDecimal.ONE.subtract(clamped);
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal gap = gapWeights.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal current = currentWeights.getOrDefault(layer, BigDecimal.ZERO);
			blended.put(layer, gap.multiply(inverse).add(current.multiply(clamped)));
		}
		return normalizeTargetWeights(blended);
	}

	private BigDecimal varianceFraction(BigDecimal variancePct) {
		BigDecimal variance = variancePct == null || variancePct.signum() <= 0
				? new BigDecimal("3.0")
				: variancePct;
		return variance.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
	}

	private Map<Integer, BigDecimal> computeDistribution(Map<Integer, BigDecimal> amounts, BigDecimal total) {
		Map<Integer, BigDecimal> distribution = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal value = amounts.getOrDefault(layer, BigDecimal.ZERO);
			if (total.signum() <= 0) {
				distribution.put(layer, BigDecimal.ZERO);
			} else {
				distribution.put(layer, value.divide(total, 6, RoundingMode.HALF_UP));
			}
		}
		return distribution;
	}

	private Map<Integer, BigDecimal> computeDeviations(Map<Integer, BigDecimal> actual, Map<Integer, BigDecimal> target) {
		Map<Integer, BigDecimal> deviations = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal actualValue = actual.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal targetValue = target.getOrDefault(layer, BigDecimal.ZERO);
			deviations.put(layer, actualValue.subtract(targetValue).abs());
		}
		return deviations;
	}

	private Map<Integer, Double> toPercentageMap(Map<Integer, BigDecimal> values) {
		Map<Integer, Double> result = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			result.put(layer, toWeightPct(values.getOrDefault(layer, BigDecimal.ZERO)));
		}
		return result;
	}

	private Map<Integer, Double> toAmountMap(Map<Integer, BigDecimal> values) {
		Map<Integer, Double> result = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			result.put(layer, toAmount(values.getOrDefault(layer, BigDecimal.ZERO)));
		}
		return result;
	}

	private Map<Integer, Double> toPercentagePoints(Map<Integer, BigDecimal> values) {
		Map<Integer, Double> result = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			result.put(layer, toWeightPct(values.getOrDefault(layer, BigDecimal.ZERO)));
		}
		return result;
	}

	private boolean isWithinTolerance(Map<Integer, BigDecimal> actual, Map<Integer, BigDecimal> target, BigDecimal variancePct) {
		BigDecimal tolerance = variancePct == null ? new BigDecimal("3.0") : variancePct;
		tolerance = tolerance.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal actualValue = actual.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal targetValue = target.getOrDefault(layer, BigDecimal.ZERO);
			if (actualValue.subtract(targetValue).abs().compareTo(tolerance) > 0) {
				return false;
			}
		}
		return true;
	}

	private List<ConstraintResultDto> evaluateConstraints(Map<Integer, BigDecimal> distribution,
														  Map<String, BigDecimal> constraints,
														  Map<Integer, String> layerNames) {
		if (constraints == null || constraints.isEmpty()) {
			return List.of();
		}
		Map<Integer, String> names = layerNames == null ? Map.of() : layerNames;
		List<ConstraintResultDto> result = new ArrayList<>();
		BigDecimal coreActual = distribution.getOrDefault(1, BigDecimal.ZERO)
				.add(distribution.getOrDefault(2, BigDecimal.ZERO));
		BigDecimal layer4Actual = distribution.getOrDefault(4, BigDecimal.ZERO);
		BigDecimal layer5Actual = distribution.getOrDefault(5, BigDecimal.ZERO);

		if (constraints.containsKey("core_min")) {
			BigDecimal threshold = constraints.get("core_min");
			boolean ok = coreActual.compareTo(threshold) >= 0;
			String details = String.format("Global Core + Core-Plus = %s (min %s)",
					formatPercentage(coreActual), formatPercentage(threshold));
			result.add(new ConstraintResultDto("core_min", ok, details));
		}
		if (constraints.containsKey("layer4_max")) {
			BigDecimal threshold = constraints.get("layer4_max");
			boolean ok = layer4Actual.compareTo(threshold) <= 0;
			String name = names.getOrDefault(4, "Layer 4");
			String details = String.format("%s = %s (max %s)", name, formatPercentage(layer4Actual), formatPercentage(threshold));
			result.add(new ConstraintResultDto("layer4_max", ok, details));
		}
		if (constraints.containsKey("layer5_max")) {
			BigDecimal threshold = constraints.get("layer5_max");
			boolean ok = layer5Actual.compareTo(threshold) <= 0;
			String name = names.getOrDefault(5, "Layer 5");
			String details = String.format("%s = %s (max %s)", name, formatPercentage(layer5Actual), formatPercentage(threshold));
			result.add(new ConstraintResultDto("layer5_max", ok, details));
		}
		return result;
	}

	private String formatPercentage(BigDecimal fraction) {
		return fraction.multiply(new BigDecimal("100"))
				.setScale(1, RoundingMode.HALF_UP)
				.toPlainString() + "%";
	}

	private int findLayerWithMaxDeviation(Map<Integer, Double> actual, Map<Integer, Double> target) {
		int layer = 1;
		double maxDiff = 0.0d;
		for (int idx = 1; idx <= 5; idx++) {
			double diff = Math.abs(actual.getOrDefault(idx, 0.0d) - target.getOrDefault(idx, 0.0d));
			if (diff > maxDiff) {
				maxDiff = diff;
				layer = idx;
			}
		}
		return layer;
	}

	private Map<Integer, BigDecimal> buildProjectedTargets(SavingPlanMetrics metrics,
								  Map<Integer, BigDecimal> targetWeights,
								  Map<Integer, BigDecimal> holdingsByLayer,
								  Integer projectionHorizonMonths,
								  BigDecimal variancePct,
								  BigDecimal projectionBlendMin,
								  BigDecimal projectionBlendMax) {
		if (metrics == null || targetWeights == null || targetWeights.isEmpty()) {
			return buildRuleBasedTargets(metrics, targetWeights);
		}
		BigDecimal monthlyTotal = metrics.monthlyTotal();
		Map<Integer, BigDecimal> fallback = buildRuleBasedTargets(metrics, targetWeights);
		if (monthlyTotal.signum() <= 0) {
			return fallback;
		}
		Map<Integer, BigDecimal> currentAmounts = metrics.monthlyByLayer() == null
				? initLayerAmounts()
				: metrics.monthlyByLayer();
		Map<Integer, BigDecimal> currentWeights = computeDistribution(currentAmounts, monthlyTotal);
		Map<Integer, BigDecimal> holdings = normalizeHoldingsByLayer(holdingsByLayer);
		BigDecimal holdingsTotal = sumAmounts(holdings);
		if (holdingsTotal.signum() <= 0) {
			return fallback;
		}
		int horizon = normalizeProjectionHorizonMonths(projectionHorizonMonths);
		BigDecimal projectedTotal = holdingsTotal.add(monthlyTotal.multiply(BigDecimal.valueOf(horizon)));
		BigDecimal varianceFraction = varianceFraction(variancePct);
		Map<Integer, BigDecimal> gaps = initLayerAmounts();
		BigDecimal gapTotal = BigDecimal.ZERO;
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal targetWeight = targetWeights.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal targetValue = projectedTotal.multiply(targetWeight);
			BigDecimal holding = holdings.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal gap = targetValue.subtract(holding);
			if (gap.signum() > 0) {
				BigDecimal currentWeight = holding.divide(holdingsTotal, 6, RoundingMode.HALF_UP);
				if (currentWeight.compareTo(targetWeight.add(varianceFraction)) > 0) {
					gap = BigDecimal.ZERO;
				}
			} else {
				gap = BigDecimal.ZERO;
			}
			gaps.put(layer, gap);
			gapTotal = gapTotal.add(gap);
		}
		Map<Integer, BigDecimal> gapWeights = initLayerAmounts();
		if (gapTotal.signum() > 0) {
			for (int layer = 1; layer <= 5; layer++) {
				BigDecimal gap = gaps.getOrDefault(layer, BigDecimal.ZERO);
				BigDecimal weight = gap.signum() == 0
						? BigDecimal.ZERO
						: gap.divide(gapTotal, 6, RoundingMode.HALF_UP);
				gapWeights.put(layer, weight);
			}
		}
		Map<Integer, BigDecimal> weights;
		if (gapTotal.signum() <= 0) {
			weights = normalizeTargetWeights(currentWeights);
		} else {
			BigDecimal blendFactor = projectionBlendFactor(horizon, projectionBlendMin, projectionBlendMax);
			weights = blendWeights(gapWeights, currentWeights, blendFactor);
		}
		if (sumAmounts(weights).signum() <= 0) {
			return fallback;
		}
		Map<Integer, BigDecimal> desired = new HashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal weight = weights.getOrDefault(layer, BigDecimal.ZERO);
			desired.put(layer, monthlyTotal.multiply(weight));
		}
		return roundProposalTargets(desired, monthlyTotal);
	}

	private Map<Integer, BigDecimal> buildRuleBasedTargets(SavingPlanMetrics metrics, Map<Integer, BigDecimal> targetWeights) {
		Map<Integer, BigDecimal> fallback = new HashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal targetWeight = targetWeights.getOrDefault(layer, BigDecimal.ZERO);
			fallback.put(layer, targetWeight.multiply(metrics.monthlyTotal()));
		}
		return fallback;
	}

	private Map<Integer, BigDecimal> roundProposalTargets(Map<Integer, BigDecimal> desiredTargets, BigDecimal total) {
		if (desiredTargets == null || desiredTargets.isEmpty()) {
			return desiredTargets;
		}
		BigDecimal totalRounded = total.setScale(0, RoundingMode.HALF_UP);
		Map<Integer, BigDecimal> rounded = new HashMap<>();
		Map<Integer, BigDecimal> fractions = new HashMap<>();
		BigDecimal sum = BigDecimal.ZERO;

		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal desired = desiredTargets.getOrDefault(layer, BigDecimal.ZERO);
			if (desired.signum() < 0) {
				desired = BigDecimal.ZERO;
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
																   Integer minimumSavingPlanSize) {
		if (proposalAmounts == null || minimumSavingPlanSize == null || minimumSavingPlanSize < 1) {
			return new MinimumSavingPlanAdjustment(proposalAmounts, List.of(), false, false);
		}
		Map<Integer, BigDecimal> adjusted = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			adjusted.put(layer, proposalAmounts.getOrDefault(layer, BigDecimal.ZERO));
		}
		List<Integer> zeroedLayers = new ArrayList<>();
		BigDecimal minimum = new BigDecimal(minimumSavingPlanSize);
		for (int layer = 5; layer >= 2; layer--) {
			BigDecimal amount = adjusted.getOrDefault(layer, BigDecimal.ZERO);
			if (amount.signum() > 0 && amount.compareTo(minimum) < 0) {
				adjusted.put(layer, BigDecimal.ZERO);
				zeroedLayers.add(layer);
				adjusted.put(layer - 1, adjusted.getOrDefault(layer - 1, BigDecimal.ZERO).add(amount));
			}
		}
		boolean increasedLayerOne = false;
		BigDecimal layerOneAmount = adjusted.getOrDefault(1, BigDecimal.ZERO);
		boolean onlyLayerOne = true;
		int nonZeroLayers = 0;
		for (int layer = 1; layer <= 5; layer++) {
			if (adjusted.getOrDefault(layer, BigDecimal.ZERO).signum() > 0) {
				nonZeroLayers += 1;
				if (layer != 1) {
					onlyLayerOne = false;
				}
			}
		}
		if (nonZeroLayers == 1 && onlyLayerOne && layerOneAmount.signum() > 0 && layerOneAmount.compareTo(minimum) < 0) {
			adjusted.put(1, minimum);
			increasedLayerOne = true;
		}
		boolean rebalanced = false;
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal original = proposalAmounts.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal updated = adjusted.getOrDefault(layer, BigDecimal.ZERO);
			if (original.compareTo(updated) != 0) {
				rebalanced = true;
				break;
			}
		}
		return new MinimumSavingPlanAdjustment(Map.copyOf(adjusted), List.copyOf(zeroedLayers), rebalanced, increasedLayerOne);
	}

	private MinimumSavingPlanImpact reconcileMinimumSavingPlanImpact(Map<Integer, BigDecimal> originalTargets,
																	 MinimumSavingPlanAdjustment adjustment,
																	 Map<Integer, BigDecimal> finalAmounts) {
		if (adjustment == null || !adjustment.rebalanced() || originalTargets == null || finalAmounts == null) {
			return new MinimumSavingPlanImpact(false, false);
		}
		Map<Integer, BigDecimal> adjustedAmounts = adjustment.adjustedAmounts();
		boolean rebalanced = false;
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal original = originalTargets.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal adjusted = adjustedAmounts.getOrDefault(layer, BigDecimal.ZERO);
			if (original.compareTo(adjusted) == 0) {
				continue;
			}
			BigDecimal finalAmount = finalAmounts.getOrDefault(layer, BigDecimal.ZERO);
			if (finalAmount.compareTo(adjusted) == 0) {
				rebalanced = true;
				break;
			}
		}
		boolean increasedLayerOne = false;
		if (adjustment.increasedLayerOneToMinimum()) {
			BigDecimal original = originalTargets.getOrDefault(1, BigDecimal.ZERO);
			BigDecimal adjusted = adjustedAmounts.getOrDefault(1, BigDecimal.ZERO);
			BigDecimal finalAmount = finalAmounts.getOrDefault(1, BigDecimal.ZERO);
			if (original.compareTo(adjusted) != 0 && finalAmount.compareTo(adjusted) == 0) {
				increasedLayerOne = true;
			}
		}
		if (increasedLayerOne) {
			rebalanced = true;
		}
		return new MinimumSavingPlanImpact(rebalanced, increasedLayerOne);
	}

	private MinimumRebalancingAdjustment applyMinimumRebalancingAmount(Map<Integer, BigDecimal> proposalAmounts,
																	   Map<Integer, BigDecimal> currentAmounts,
																	   Integer minimumRebalancingAmount) {
		if (proposalAmounts == null || minimumRebalancingAmount == null || minimumRebalancingAmount < 1) {
			return new MinimumRebalancingAdjustment(proposalAmounts, List.of(), false);
		}
		Map<Integer, BigDecimal> adjusted = new LinkedHashMap<>();
		Map<Integer, BigDecimal> current = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			adjusted.put(layer, proposalAmounts.getOrDefault(layer, BigDecimal.ZERO));
			BigDecimal currentAmount = currentAmounts == null ? BigDecimal.ZERO : currentAmounts.getOrDefault(layer, BigDecimal.ZERO);
			current.put(layer, currentAmount);
		}
		BigDecimal targetTotal = sumAmounts(adjusted);
		BigDecimal minimum = new BigDecimal(minimumRebalancingAmount);
		Set<Integer> skipped = new LinkedHashSet<>();
		boolean applied = false;
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal delta = adjusted.get(layer).subtract(current.get(layer));
			if (delta.signum() != 0 && delta.abs().compareTo(minimum) < 0) {
				adjusted.put(layer, current.get(layer));
				skipped.add(layer);
				applied = true;
			}
		}
		BigDecimal residual = sumAmounts(adjusted).subtract(targetTotal);
		int guard = 0;
		while (residual.signum() != 0 && guard < 12) {
			guard += 1;
			BigDecimal reduced = residual.signum() > 0
					? reducePositiveResidual(adjusted, current, minimum, residual, skipped)
					: reduceNegativeResidual(adjusted, current, minimum, residual.abs(), skipped);
			if (reduced.signum() == 0) {
				break;
			}
			applied = true;
			residual = sumAmounts(adjusted).subtract(targetTotal);
		}
		return new MinimumRebalancingAdjustment(Map.copyOf(adjusted), List.copyOf(skipped), applied);
	}

	private BigDecimal reducePositiveResidual(Map<Integer, BigDecimal> adjusted,
											  Map<Integer, BigDecimal> current,
											  BigDecimal minimum,
											  BigDecimal residual,
											  Set<Integer> skipped) {
		List<Integer> candidates = new ArrayList<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal delta = adjusted.getOrDefault(layer, BigDecimal.ZERO).subtract(current.getOrDefault(layer, BigDecimal.ZERO));
			if (delta.signum() > 0) {
				candidates.add(layer);
			}
		}
		candidates.sort((a, b) -> {
			BigDecimal da = adjusted.getOrDefault(a, BigDecimal.ZERO).subtract(current.getOrDefault(a, BigDecimal.ZERO)).abs();
			BigDecimal db = adjusted.getOrDefault(b, BigDecimal.ZERO).subtract(current.getOrDefault(b, BigDecimal.ZERO)).abs();
			int cmp = da.compareTo(db);
			return cmp != 0 ? cmp : Integer.compare(a, b);
		});
		BigDecimal remaining = residual;
		for (int layer : candidates) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal delta = adjusted.get(layer).subtract(current.get(layer));
			BigDecimal reduction = delta.min(remaining);
			BigDecimal updatedDelta = delta.subtract(reduction);
			if (updatedDelta.signum() > 0 && updatedDelta.abs().compareTo(minimum) < 0) {
				reduction = delta;
				updatedDelta = BigDecimal.ZERO;
				skipped.add(layer);
			}
			adjusted.put(layer, current.get(layer).add(updatedDelta));
			remaining = remaining.subtract(reduction);
		}
		return residual.subtract(remaining);
	}

	private BigDecimal reduceNegativeResidual(Map<Integer, BigDecimal> adjusted,
											  Map<Integer, BigDecimal> current,
											  BigDecimal minimum,
											  BigDecimal residual,
											  Set<Integer> skipped) {
		List<Integer> candidates = new ArrayList<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal delta = adjusted.getOrDefault(layer, BigDecimal.ZERO).subtract(current.getOrDefault(layer, BigDecimal.ZERO));
			if (delta.signum() < 0) {
				candidates.add(layer);
			}
		}
		candidates.sort((a, b) -> {
			BigDecimal da = adjusted.getOrDefault(a, BigDecimal.ZERO).subtract(current.getOrDefault(a, BigDecimal.ZERO)).abs();
			BigDecimal db = adjusted.getOrDefault(b, BigDecimal.ZERO).subtract(current.getOrDefault(b, BigDecimal.ZERO)).abs();
			int cmp = da.compareTo(db);
			return cmp != 0 ? cmp : Integer.compare(a, b);
		});
		BigDecimal remaining = residual;
		for (int layer : candidates) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal delta = adjusted.get(layer).subtract(current.get(layer));
			BigDecimal reduction = delta.abs().min(remaining);
			BigDecimal updatedDelta = delta.add(reduction);
			if (updatedDelta.signum() < 0 && updatedDelta.abs().compareTo(minimum) < 0) {
				reduction = delta.abs();
				updatedDelta = BigDecimal.ZERO;
				skipped.add(layer);
			}
			adjusted.put(layer, current.get(layer).add(updatedDelta));
			remaining = remaining.subtract(reduction);
		}
		return residual.subtract(remaining);
	}

	private List<Integer> sortLayersByFraction(Map<Integer, BigDecimal> fractions, boolean ascending) {
		List<Integer> layers = new ArrayList<>(fractions.keySet());
		layers.sort((a, b) -> {
			int cmp = fractions.getOrDefault(a, BigDecimal.ZERO)
					.compareTo(fractions.getOrDefault(b, BigDecimal.ZERO));
			if (cmp == 0) {
				cmp = Integer.compare(a, b);
			}
			return ascending ? cmp : -cmp;
		});
		return layers;
	}

	private Map<Integer, BigDecimal> initLayerAmounts() {
		Map<Integer, BigDecimal> map = new HashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			map.put(layer, BigDecimal.ZERO);
		}
		return map;
	}

	private Map<Integer, Integer> initLayerCounts() {
		Map<Integer, Integer> map = new HashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			map.put(layer, 0);
		}
		return map;
	}

	private String normalizeFrequency(String frequency) {
		if (frequency == null || frequency.isBlank()) {
			return "monthly";
		}
		return frequency.trim().toLowerCase();
	}

	private int normalizeLayer(Integer layer) {
		if (layer == null || layer < 1 || layer > 5) {
			return 5;
		}
		return layer;
	}

	private Integer toLayer(Object raw) {
		if (raw instanceof Number number) {
			return number.intValue();
		}
		if (raw instanceof String text) {
			try {
				return Integer.parseInt(text.trim());
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private BigDecimal toBigDecimal(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof BigDecimal decimal) {
			return decimal;
		}
		if (value instanceof Number number) {
			return BigDecimal.valueOf(number.doubleValue());
		}
		if (value instanceof String text) {
			try {
				return new BigDecimal(text.trim());
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private Double toAmount(BigDecimal value) {
		if (value == null) {
			return null;
		}
		return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
	}

	private String normalizeIsin(String isin) {
		if (isin == null || isin.isBlank()) {
			return null;
		}
		return isin.trim().toUpperCase(Locale.ROOT);
	}

	private Double toWeightPct(BigDecimal fraction) {
		if (fraction == null) {
			return null;
		}
		return fraction.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).doubleValue();
	}

	private record MinimumSavingPlanAdjustment(Map<Integer, BigDecimal> adjustedAmounts,
											   List<Integer> zeroedLayers,
											   boolean rebalanced,
											   boolean increasedLayerOneToMinimum) {
	}

	private record MinimumSavingPlanImpact(boolean rebalanced,
										   boolean increasedLayerOne) {
	}

	private record MinimumRebalancingAdjustment(Map<Integer, BigDecimal> adjustedAmounts,
												List<Integer> skippedLayers,
												boolean applied) {
	}

	private record SavingPlanRow(BigDecimal amount, String frequency, Integer layer) {
	}

	private record SavingPlanMetrics(BigDecimal activeTotal,
								   BigDecimal monthlyTotal,
								   int activeCount,
								   int monthlyCount,
								   Map<Integer, BigDecimal> monthlyByLayer,
								   Map<Integer, Integer> monthlyCounts) {
	}

	private record SnapshotScope(long snapshotId, LocalDate asOfDate, String depotCode) {
	}

}
