package my.portfoliomanager.app.service;

import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import my.portfoliomanager.app.model.LayerTargetRiskThresholds;
import my.portfoliomanager.app.service.util.RiskThresholdsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
public class AssessorInstrumentAssessmentService {
	private static final Logger logger = LoggerFactory.getLogger(AssessorInstrumentAssessmentService.class);
	private static final Set<String> COMPLETE_STATUSES = Set.of("COMPLETE", "APPROVED", "APPLIED");
	private static final Set<String> APPROVED_EXTRACTION_STATUSES = Set.of("APPROVED", "APPLIED");
	private static final double DATA_QUALITY_MISSING_WEIGHT = 3.0;
	private static final double DATA_QUALITY_WARNING_WEIGHT = 5.0;
	private static final double MAX_DATA_QUALITY_PENALTY = 20.0;
	private static final double MAX_DATA_QUALITY_PENALTY_CORE = 10.0;
	private static final double SINGLE_STOCK_PENALTY = 15.0;
	private static final double MISSING_SRI_PENALTY = 5.0;
	private static final double MISSING_SRI_PENALTY_WITH_SECTION = 2.0;
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

	public AssessorInstrumentAssessmentService(NamedParameterJdbcTemplate namedParameterJdbcTemplate,
										ObjectMapper objectMapper,
										AppProperties properties) {
		this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	public AssessmentResult assess(List<String> instrumentIsins,
						 int amountEur,
						 Map<Integer, BigDecimal> layerTargets,
						 LayerTargetRiskThresholds riskThresholds,
						 Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer) {
		LayerTargetRiskThresholds thresholds = RiskThresholdsUtil.normalize(riskThresholds);
		Map<Integer, LayerTargetRiskThresholds> normalizedByLayer =
				RiskThresholdsUtil.normalizeByLayer(riskThresholdsByLayer, thresholds);
		double scoreCutoff = thresholds.getHighMin();
		List<String> normalizedIsins = normalizeIsins(instrumentIsins);
		if (normalizedIsins.isEmpty() || amountEur <= 0) {
			return AssessmentResult.empty(scoreCutoff, amountEur, thresholds, normalizedByLayer);
		}
		boolean kbEnabled = properties != null && properties.kb() != null && properties.kb().enabled();
		if (!kbEnabled) {
			return AssessmentResult.missing(scoreCutoff, amountEur, normalizedIsins, thresholds, normalizedByLayer);
		}
		Set<String> isinSet = new LinkedHashSet<>(normalizedIsins);
		Map<String, KbExtraction> kbExtractions = loadLatestExtractions(isinSet);
		Map<String, ApprovedExtraction> approvedExtractions = loadApprovedExtractions(isinSet);
		List<String> missingIsins = resolveMissingIsins(normalizedIsins, kbExtractions, approvedExtractions);
		if (!missingIsins.isEmpty()) {
			return AssessmentResult.missing(scoreCutoff, amountEur, missingIsins, thresholds, normalizedByLayer);
		}
		Map<String, InstrumentFallback> fallbacks = loadInstrumentFallbacks(isinSet);
		List<AssessmentItem> items = new ArrayList<>();
		CriteriaTracker criteria = new CriteriaTracker();
		for (String isin : normalizedIsins) {
			KbExtraction extraction = kbExtractions.get(isin);
			InstrumentDossierExtractionPayload payload = extraction == null ? null : extraction.payload();
			if (payload == null) {
				missingIsins.add(isin);
				continue;
			}
			InstrumentFallback fallback = fallbacks.get(isin);
			String name = resolveName(payload, fallback, isin);
			Integer layer = resolveLayer(payload, fallback);
			LayerTargetRiskThresholds layerThresholds =
					RiskThresholdsUtil.resolveForLayer(normalizedByLayer, thresholds, layer);
			double layerCutoff = layerThresholds.getHighMin();
			ScoreResult scoreResult = computeAssessmentScore(payload, criteria, layerCutoff, layer);
			items.add(new AssessmentItem(isin, name, layer, scoreResult.score(), scoreResult.badFinancials(),
					scoreResult.scoreComponents()));
		}
		if (!missingIsins.isEmpty()) {
			missingIsins.sort(String::compareTo);
			return AssessmentResult.missing(scoreCutoff, amountEur, missingIsins, thresholds, normalizedByLayer);
		}
		AllocationResult allocation = allocate(amountEur, items, layerTargets, kbExtractions, criteria, thresholds, normalizedByLayer);
		return new AssessmentResult(scoreCutoff, amountEur, allocation.items(), List.of(), criteria.scoreCriteria(),
				criteria.allocationCriteria(), allocation.layerBudgets(), thresholds, normalizedByLayer);
	}

	public Map<String, Integer> assessScores(Set<String> instrumentIsins,
						 LayerTargetRiskThresholds riskThresholds,
						 Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer) {
		LayerTargetRiskThresholds thresholds = RiskThresholdsUtil.normalize(riskThresholds);
		Map<Integer, LayerTargetRiskThresholds> normalizedByLayer =
				RiskThresholdsUtil.normalizeByLayer(riskThresholdsByLayer, thresholds);
		List<String> normalizedIsins = normalizeIsins(instrumentIsins == null
				? List.of()
				: new ArrayList<>(instrumentIsins));
		if (normalizedIsins.isEmpty()) {
			return Map.of();
		}
		boolean kbEnabled = properties != null && properties.kb() != null && properties.kb().enabled();
		if (!kbEnabled) {
			return Map.of();
		}
		Set<String> isinSet = new LinkedHashSet<>(normalizedIsins);
		Map<String, KbExtraction> kbExtractions = loadLatestExtractions(isinSet);
		Map<String, Integer> scores = new LinkedHashMap<>();
		CriteriaTracker criteria = new CriteriaTracker();
		for (String isin : normalizedIsins) {
			KbExtraction extraction = kbExtractions.get(isin);
			InstrumentDossierExtractionPayload payload = extraction == null ? null : extraction.payload();
			if (payload == null) {
				continue;
			}
			Integer layer = resolveLayer(payload, null);
			LayerTargetRiskThresholds layerThresholds =
					RiskThresholdsUtil.resolveForLayer(normalizedByLayer, thresholds, layer);
			double layerCutoff = layerThresholds.getHighMin();
			ScoreResult scoreResult = computeAssessmentScore(payload, criteria, layerCutoff, layer);
			scores.put(isin, scoreResult.score());
		}
		return scores;
	}

	private List<String> normalizeIsins(List<String> isins) {
		if (isins == null || isins.isEmpty()) {
			return List.of();
		}
		List<String> normalized = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (String isin : isins) {
			String normalizedIsin = normalizeIsin(isin);
			if (normalizedIsin == null || seen.contains(normalizedIsin)) {
				continue;
			}
			seen.add(normalizedIsin);
			normalized.add(normalizedIsin);
		}
		return normalized;
	}

	private String normalizeIsin(String isin) {
		if (isin == null || isin.isBlank()) {
			return null;
		}
		return isin.trim().toUpperCase(Locale.ROOT);
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

	private Map<String, ApprovedExtraction> loadApprovedExtractions(Set<String> isins) {
		if (isins == null || isins.isEmpty()) {
			return Map.of();
		}
		String sql = """
				SELECT isin, status, auto_approved, created_at
				FROM (
					SELECT d.isin AS isin,
						e.status AS status,
						e.auto_approved AS auto_approved,
						e.created_at AS created_at,
						ROW_NUMBER() OVER (PARTITION BY d.isin ORDER BY e.created_at DESC, e.extraction_id DESC) AS rn
					FROM instrument_dossiers d
					JOIN instrument_dossier_extractions e ON e.dossier_id = d.dossier_id
					WHERE d.isin IN (:isins)
						AND e.status IN ('APPROVED', 'APPLIED')
				) ranked
				WHERE rn = 1
				""";
		MapSqlParameterSource params = new MapSqlParameterSource("isins", isins);
		Map<String, ApprovedExtraction> result = new HashMap<>();
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			String isin = normalizeIsin(rs.getString("isin"));
			String status = rs.getString("status");
			boolean autoApproved = rs.getBoolean("auto_approved");
			if (isin != null) {
				result.put(isin, new ApprovedExtraction(isin, status, autoApproved));
			}
		});
		return result;
	}

	private Map<String, InstrumentFallback> loadInstrumentFallbacks(Set<String> isins) {
		if (isins == null || isins.isEmpty()) {
			return Map.of();
		}
		String sql = """
				SELECT isin, name, layer
				FROM instruments_effective
				WHERE isin IN (:isins)
				""";
		MapSqlParameterSource params = new MapSqlParameterSource("isins", isins);
		Map<String, InstrumentFallback> result = new HashMap<>();
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			String isin = normalizeIsin(rs.getString("isin"));
			String name = rs.getString("name");
			Integer layer = rs.getObject("layer") == null ? null : rs.getInt("layer");
			if (isin != null) {
				result.put(isin, new InstrumentFallback(isin, name, layer));
			}
		});
		return result;
	}

	private List<String> resolveMissingIsins(List<String> isins,
									  Map<String, KbExtraction> extractions,
									  Map<String, ApprovedExtraction> approvedExtractions) {
		List<String> missing = new ArrayList<>();
		for (String isin : isins) {
			KbExtraction extraction = extractions.get(isin);
			if (extraction == null || !isComplete(extraction.status()) || extraction.payload() == null) {
				missing.add(isin);
				continue;
			}
			ApprovedExtraction approved = approvedExtractions.get(isin);
			if (approved == null || !isApproved(approved.status())) {
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

	private boolean isApproved(String status) {
		if (status == null) {
			return false;
		}
		return APPROVED_EXTRACTION_STATUSES.contains(status.toUpperCase(Locale.ROOT));
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

	private String resolveName(InstrumentDossierExtractionPayload payload, InstrumentFallback fallback, String isin) {
		String name = payload == null ? null : payload.name();
		if (name == null || name.isBlank()) {
			name = fallback == null ? null : fallback.name();
		}
		if (name == null || name.isBlank()) {
			return isin;
		}
		return name;
	}

	private Integer resolveLayer(InstrumentDossierExtractionPayload payload, InstrumentFallback fallback) {
		Integer layer = payload == null ? null : payload.layer();
		if (layer == null) {
			layer = fallback == null ? null : fallback.layer();
		}
		if (layer == null || layer < 1 || layer > 5) {
			return null;
		}
		return layer;
	}

	private ScoreResult computeAssessmentScore(InstrumentDossierExtractionPayload payload,
			CriteriaTracker criteria,
			double scoreCutoff,
			Integer layer) {
		boolean badFinancials = false;
		if (payload == null) {
			return new ScoreResult((int) Math.ceil(scoreCutoff), true, List.of());
		}
		InstrumentDossierExtractionPayload.ValuationPayload valuation = payload.valuation();
		InstrumentDossierExtractionPayload.FinancialsPayload financials = payload.financials();
		if (valuation != null) {
			BigDecimal ebitda = valuation.ebitdaEur();
			if (ebitda != null && ebitda.signum() < 0) {
				badFinancials = true;
			}
			BigDecimal peCurrent = valuation.peCurrent();
			if (peCurrent != null && peCurrent.signum() <= 0) {
				badFinancials = true;
			}
			BigDecimal earningsYield = valuation.earningsYieldLongterm();
			if (earningsYield != null && earningsYield.signum() <= 0) {
				badFinancials = true;
			}
		}
		if (financials != null) {
			BigDecimal netIncome = financials.netIncomeEur();
			if (netIncome != null && netIncome.signum() < 0) {
				badFinancials = true;
			}
			BigDecimal revenue = financials.revenueEur();
			if (revenue != null && revenue.signum() < 0) {
				badFinancials = true;
			}
		}
		List<ScoreComponent> components = new ArrayList<>();
		double costPenalty = scoreCostPenalty(payload, layer, criteria, components);
		double riskPenalty = scoreRiskPenalty(payload, layer, criteria, components);
		double valuationPenalty = scoreValuationPenalty(payload, criteria, components);
		double concentrationPenalty = scoreConcentrationPenalty(payload, layer, criteria, components);
		double dataPenalty = scoreDataQualityPenalty(payload, layer, criteria, components);
		double singleStockPenalty = scoreSingleStockPenalty(payload, criteria, components);
		double rawScore = costPenalty + riskPenalty + valuationPenalty + concentrationPenalty + dataPenalty + singleStockPenalty;
		List<ScoreComponent> adjustedComponents = scaleComponentsIfNeeded(components, rawScore, 100.0);
		double adjustedScore = sumComponents(adjustedComponents);
		int rounded = (int) Math.round(Math.min(100.0, Math.max(0.0, adjustedScore)));
		if (badFinancials && rounded < scoreCutoff) {
			double floorPenalty = scoreCutoff - adjustedScore;
			if (floorPenalty > 0) {
				List<ScoreComponent> extended = new ArrayList<>(adjustedComponents);
				extended.add(new ScoreComponent("Bad financials floor", floorPenalty));
				adjustedComponents = extended;
				adjustedScore += floorPenalty;
			}
			int floorCutoff = (int) Math.ceil(scoreCutoff);
			floorCutoff = Math.min(100, Math.max(0, floorCutoff));
			rounded = floorCutoff;
		}
		return new ScoreResult(rounded, badFinancials, List.copyOf(adjustedComponents));
	}

	private double scoreCostPenalty(InstrumentDossierExtractionPayload payload,
			Integer layer,
			CriteriaTracker criteria,
			List<ScoreComponent> components) {
		if (payload == null || payload.etf() == null || payload.etf().ongoingChargesPct() == null) {
			return 0.0;
		}
		criteria.addScoreCriterion("TER");
		BigDecimal ter = payload.etf().ongoingChargesPct();
		double value = ter.doubleValue();
		double penalty = resolveTerPenalty(value, layer);
		recordScoreComponent(components, "TER", penalty);
		return penalty;
	}

	private double resolveTerPenalty(double terPct, Integer layer) {
		if (layer == null) {
			return resolveLegacyTerPenalty(terPct);
		}
		if (layer == 1) {
			if (terPct <= 0.25) {
				return 0.0;
			}
			if (terPct <= 0.40) {
				return 5.0;
			}
			if (terPct <= 0.70) {
				return 10.0;
			}
			return 20.0;
		}
		if (layer == 2) {
			if (terPct <= 0.30) {
				return 0.0;
			}
			if (terPct <= 0.50) {
				return 5.0;
			}
			if (terPct <= 0.80) {
				return 10.0;
			}
			return 20.0;
		}
		if (layer == 3) {
			if (terPct <= 0.50) {
				return 0.0;
			}
			if (terPct <= 0.80) {
				return 5.0;
			}
			if (terPct <= 1.20) {
				return 10.0;
			}
			return 20.0;
		}
		return resolveLegacyTerPenalty(terPct);
	}

	private double resolveLegacyTerPenalty(double terPct) {
		if (terPct <= 0.2) {
			return 0.0;
		}
		if (terPct <= 0.5) {
			return 10.0;
		}
		if (terPct <= 1.0) {
			return 20.0;
		}
		return 30.0;
	}

	private double scoreRiskPenalty(InstrumentDossierExtractionPayload payload,
			Integer layer,
			CriteriaTracker criteria,
			List<ScoreComponent> components) {
		if (payload == null || payload.risk() == null || payload.risk().summaryRiskIndicator() == null) {
			if (isFundLayer(layer)) {
				double penalty = MISSING_SRI_PENALTY;
				if (payload != null && payload.risk() != null && Boolean.TRUE.equals(payload.risk().sectionPresent())) {
					penalty = MISSING_SRI_PENALTY_WITH_SECTION;
				}
				criteria.addScoreCriterion("Risk indicator");
				recordScoreComponent(components, "Risk indicator missing", penalty);
				return penalty;
			}
			return 0.0;
		}
		Integer value = payload.risk().summaryRiskIndicator().value();
		if (value == null || value <= 0) {
			return 0.0;
		}
		criteria.addScoreCriterion("Risk indicator");
		if (value <= 3) {
			recordScoreComponent(components, "Risk indicator", 0.0);
			return 0.0;
		}
		double penalty = (value - 3) * 8.0;
		recordScoreComponent(components, "Risk indicator", penalty);
		return penalty;
	}

	private boolean isFundLayer(Integer layer) {
		return layer != null && layer >= 1 && layer <= 3;
	}

	private double scoreValuationPenalty(InstrumentDossierExtractionPayload payload, CriteriaTracker criteria,
					 List<ScoreComponent> components) {
		if (payload == null || payload.valuation() == null) {
			return 0.0;
		}
		InstrumentDossierExtractionPayload.ValuationPayload valuation = payload.valuation();
		double penalty = 0.0;
		List<ScoreComponent> valuationComponents = new ArrayList<>();
		BigDecimal peCurrent = valuation.peCurrent();
		if (peCurrent != null) {
			criteria.addScoreCriterion("P/E");
			double pe = peCurrent.doubleValue();
			double pePenalty = 0.0;
			if (pe > 60) {
				pePenalty = 30.0;
			} else if (pe > 40) {
				pePenalty = 20.0;
			} else if (pe > 30) {
				pePenalty = 10.0;
			}
			Double epsCagrPercent = computeEpsCagrPercent(valuation);
			Double pegRatio = computePegRatio(peCurrent, epsCagrPercent);
			double adjustedPePenalty = applyPegAdjustment(pePenalty, pegRatio);
			penalty += adjustedPePenalty;
			String label = adjustedPePenalty == pePenalty ? "P/E" : "P/E (PEG-adjusted)";
			valuationComponents.add(new ScoreComponent(label, adjustedPePenalty));
		}
		BigDecimal evToEbitda = valuation.evToEbitda();
		if (evToEbitda != null) {
			criteria.addScoreCriterion("EV/EBITDA");
			double ev = evToEbitda.doubleValue();
			double evPenalty = 0.0;
			if (ev > 30) {
				evPenalty = 25.0;
			} else if (ev > 20) {
				evPenalty = 15.0;
			}
			penalty += evPenalty;
			valuationComponents.add(new ScoreComponent("EV/EBITDA", evPenalty));
		}
		BigDecimal pbCurrent = valuation.pbCurrent();
		if (pbCurrent != null) {
			criteria.addScoreCriterion("P/B");
			double pb = pbCurrent.doubleValue();
			double pbPenalty = 0.0;
			if (pb > 8) {
				pbPenalty = 20.0;
			} else if (pb > 4) {
				pbPenalty = 10.0;
			}
			penalty += pbPenalty;
			valuationComponents.add(new ScoreComponent("P/B", pbPenalty));
		}
		BigDecimal earningsYield = valuation.earningsYieldLongterm();
		if (earningsYield != null) {
			criteria.addScoreCriterion("Earnings yield");
			double yield = earningsYield.doubleValue();
			double yieldPenalty = 0.0;
			if (yield > 0 && yield < 0.02) {
				yieldPenalty = 20.0;
			} else if (yield > 0 && yield < 0.03) {
				yieldPenalty = 10.0;
			}
			penalty += yieldPenalty;
			valuationComponents.add(new ScoreComponent("Earnings yield", yieldPenalty));
		}
		double cappedPenalty = Math.min(35.0, penalty);
		if (!valuationComponents.isEmpty()) {
			components.addAll(scaleComponentsIfNeeded(valuationComponents, penalty, cappedPenalty));
		}
		return cappedPenalty;
	}

	private Double computeEpsCagrPercent(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (valuation == null || valuation.epsHistory() == null || valuation.epsHistory().isEmpty()) {
			return null;
		}
		Map<Integer, BigDecimal> epsByYear = new HashMap<>();
		for (InstrumentDossierExtractionPayload.EpsHistoryPayload entry : valuation.epsHistory()) {
			if (entry == null || entry.year() == null || entry.eps() == null) {
				continue;
			}
			if (entry.eps().signum() <= 0) {
				continue;
			}
			epsByYear.put(entry.year(), entry.eps());
		}
		if (epsByYear.size() < 2) {
			return null;
		}
		List<Integer> years = new ArrayList<>(epsByYear.keySet());
		years.sort(Integer::compareTo);
		Integer firstYear = years.get(0);
		Integer lastYear = years.get(years.size() - 1);
		if (firstYear == null || lastYear == null) {
			return null;
		}
		int span = lastYear - firstYear;
		if (span < 2) {
			return null;
		}
		BigDecimal first = epsByYear.get(firstYear);
		BigDecimal last = epsByYear.get(lastYear);
		if (first == null || last == null || first.signum() <= 0 || last.signum() <= 0) {
			return null;
		}
		double ratio = last.doubleValue() / first.doubleValue();
		if (!Double.isFinite(ratio) || ratio <= 0.0) {
			return null;
		}
		double cagr = Math.pow(ratio, 1.0 / span) - 1.0;
		if (!Double.isFinite(cagr) || cagr <= 0.0) {
			return null;
		}
		return cagr * 100.0;
	}

	private Double computePegRatio(BigDecimal peCurrent, Double epsCagrPercent) {
		if (peCurrent == null || epsCagrPercent == null) {
			return null;
		}
		double pe = peCurrent.doubleValue();
		if (!Double.isFinite(pe) || pe <= 0.0) {
			return null;
		}
		if (epsCagrPercent <= 0.0) {
			return null;
		}
		double peg = pe / epsCagrPercent;
		if (!Double.isFinite(peg) || peg <= 0.0) {
			return null;
		}
		return peg;
	}

	private double applyPegAdjustment(double pePenalty, Double pegRatio) {
		if (pePenalty <= 0.0 || pegRatio == null) {
			return pePenalty;
		}
		if (pegRatio <= 1.0) {
			return pePenalty * 0.25;
		}
		if (pegRatio <= 1.5) {
			return pePenalty * 0.5;
		}
		if (pegRatio <= 2.0) {
			return pePenalty * 0.75;
		}
		return pePenalty;
	}

	private double scoreConcentrationPenalty(InstrumentDossierExtractionPayload payload,
			Integer layer,
			CriteriaTracker criteria,
			List<ScoreComponent> components) {
		if (payload == null) {
			return 0.0;
		}
		double penalty = 0.0;
		List<InstrumentDossierExtractionPayload.HoldingPayload> holdings = payload.topHoldings();
		if (holdings != null && !holdings.isEmpty()) {
			criteria.addScoreCriterion("Top holdings concentration");
			List<BigDecimal> weights = new ArrayList<>();
			for (InstrumentDossierExtractionPayload.HoldingPayload holding : holdings) {
				BigDecimal weight = normalizeWeightPct(holding.weightPct());
				if (weight != null) {
					weights.add(weight);
				}
			}
			weights.sort(Comparator.reverseOrder());
			if (!weights.isEmpty()) {
				double max = weights.get(0).doubleValue();
				if (max >= 0.15) {
					penalty += 15.0;
				} else if (max >= 0.10) {
					penalty += 10.0;
				}
			}
			if (weights.size() >= 3) {
				double top3 = weights.get(0).add(weights.get(1)).add(weights.get(2)).doubleValue();
				if (top3 >= 0.35) {
					penalty += 15.0;
				} else if (top3 >= 0.25) {
					penalty += 10.0;
				}
			}
			double adjustedHoldingsPenalty = adjustHoldingsPenaltyForLayer(penalty, layer);
			recordScoreComponent(components, "Top holdings concentration", adjustedHoldingsPenalty);
			penalty = adjustedHoldingsPenalty;
		}
		List<InstrumentDossierExtractionPayload.RegionExposurePayload> regions = payload.regions();
		if (regions != null && !regions.isEmpty()) {
			criteria.addScoreCriterion("Region concentration");
			double maxRegion = 0.0;
			for (InstrumentDossierExtractionPayload.RegionExposurePayload region : regions) {
				BigDecimal weight = normalizeWeightPct(region.weightPct());
				if (weight != null) {
					maxRegion = Math.max(maxRegion, weight.doubleValue());
				}
			}
			double regionPenalty = 0.0;
			if (maxRegion >= 0.75) {
				regionPenalty = 15.0;
			} else if (maxRegion >= 0.60) {
				regionPenalty = 10.0;
			}
			penalty += regionPenalty;
			recordScoreComponent(components, "Region concentration", regionPenalty);
		}
		return penalty;
	}

	private double scoreDataQualityPenalty(InstrumentDossierExtractionPayload payload,
			Integer layer,
			CriteriaTracker criteria,
			List<ScoreComponent> components) {
		if (payload == null) {
			return 0.0;
		}
		int missing = payload.missingFields() == null ? 0 : payload.missingFields().size();
		int warnings = payload.warnings() == null ? 0 : payload.warnings().size();
		if (missing == 0 && warnings == 0) {
			return 0.0;
		}
		criteria.addScoreCriterion("Data quality");
		double penalty = (missing * DATA_QUALITY_MISSING_WEIGHT) + (warnings * DATA_QUALITY_WARNING_WEIGHT);
		double cappedPenalty = Math.min(resolveDataQualityCap(layer), penalty);
		recordScoreComponent(components, "Data quality", cappedPenalty);
		return cappedPenalty;
	}

	private double adjustHoldingsPenaltyForLayer(double penalty, Integer layer) {
		if (penalty <= 0.0) {
			return penalty;
		}
		if (layer != null && layer == 3) {
			return penalty * 0.5;
		}
		return penalty;
	}

	private double resolveDataQualityCap(Integer layer) {
		if (layer != null && (layer == 1 || layer == 2)) {
			return MAX_DATA_QUALITY_PENALTY_CORE;
		}
		return MAX_DATA_QUALITY_PENALTY;
	}

	private double scoreSingleStockPenalty(InstrumentDossierExtractionPayload payload, CriteriaTracker criteria,
									  List<ScoreComponent> components) {
		if (!isSingleStock(payload)) {
			return 0.0;
		}
		criteria.addScoreCriterion("Single-stock risk premium");
		recordScoreComponent(components, "Single-stock risk premium", SINGLE_STOCK_PENALTY);
		return SINGLE_STOCK_PENALTY;
	}

	private double scoreWeightFactor(int score, double scoreCutoff) {
		if (scoreCutoff <= 0.0) {
			return 1.0;
		}
		double normalized = ((double) scoreCutoff - (double) score + 1.0) / (double) scoreCutoff;
		if (normalized > 1.0) {
			return 1.0;
		}
		if (normalized < MIN_SCORE_WEIGHT_FACTOR) {
			return MIN_SCORE_WEIGHT_FACTOR;
		}
		return normalized;
	}

	private void recordScoreComponent(List<ScoreComponent> components, String criterion, double points) {
		if (components == null || criterion == null || criterion.isBlank()) {
			return;
		}
		components.add(new ScoreComponent(criterion, points));
	}

	private double sumComponents(List<ScoreComponent> components) {
		if (components == null || components.isEmpty()) {
			return 0.0;
		}
		double total = 0.0;
		for (ScoreComponent component : components) {
			if (component != null) {
				total += component.points();
			}
		}
		return total;
	}

	private List<ScoreComponent> scaleComponentsIfNeeded(List<ScoreComponent> components,
											 double rawTotal,
											 double cappedTotal) {
		if (components == null || components.isEmpty()) {
			return List.of();
		}
		if (rawTotal <= 0.0 || rawTotal <= cappedTotal) {
			return List.copyOf(components);
		}
		double ratio = cappedTotal / rawTotal;
		List<ScoreComponent> scaled = new ArrayList<>(components.size());
		for (ScoreComponent component : components) {
			if (component == null) {
				continue;
			}
			scaled.add(new ScoreComponent(component.criterion(), component.points() * ratio));
		}
		return scaled;
	}

	private AllocationResult allocate(int amountEur,
							 List<AssessmentItem> items,
							 Map<Integer, BigDecimal> layerTargets,
							 Map<String, KbExtraction> extractions,
							 CriteriaTracker criteria,
							 LayerTargetRiskThresholds fallback,
							 Map<Integer, LayerTargetRiskThresholds> thresholdsByLayer) {
		Map<Integer, List<AssessmentItem>> byLayer = groupByLayer(items);
		Map<Integer, BigDecimal> layerBudgets = allocateLayerBudgets(
				amountEur,
				byLayer,
				layerTargets,
				fallback,
				thresholdsByLayer
		);
		List<AssessmentItem> allocatedItems = new ArrayList<>();
		Set<String> allocatedIsins = new LinkedHashSet<>();
		for (Map.Entry<Integer, List<AssessmentItem>> entry : byLayer.entrySet()) {
			Integer layer = entry.getKey();
			List<AssessmentItem> layerItems = entry.getValue();
			BigDecimal layerBudget = layerBudgets.getOrDefault(layer, BigDecimal.ZERO);
			LayerTargetRiskThresholds layerThresholds =
					RiskThresholdsUtil.resolveForLayer(thresholdsByLayer, fallback, layer);
			double layerCutoff = layerThresholds.getHighMin();
			Map<String, BigDecimal> weights = computeWeights(layerItems, extractions, criteria, layerCutoff, layer);
			Map<String, BigDecimal> amounts = allocateInstrumentAmounts(layerItems, layerBudget, weights, layerCutoff);
			for (AssessmentItem item : layerItems) {
				BigDecimal allocation = amounts.getOrDefault(item.isin(), BigDecimal.ZERO);
				allocatedItems.add(item.withAllocation(allocation));
				allocatedIsins.add(item.isin());
			}
		}
		for (AssessmentItem item : items) {
			if (!allocatedIsins.contains(item.isin())) {
				allocatedItems.add(item.withAllocation(BigDecimal.ZERO));
			}
		}
		allocatedItems.sort(Comparator.comparing(AssessmentItem::isin));
		criteria.addAllocationCriterion("Layer targets");
		criteria.addAllocationCriterion("KB weighting");
		criteria.addAllocationCriterion("Assessment score tie-break");
		return new AllocationResult(allocatedItems, layerBudgets);
	}

	private Map<Integer, List<AssessmentItem>> groupByLayer(List<AssessmentItem> items) {
		Map<Integer, List<AssessmentItem>> grouped = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			grouped.put(layer, new ArrayList<>());
		}
		for (AssessmentItem item : items) {
			if (item.layer() == null) {
				continue;
			}
			grouped.computeIfAbsent(item.layer(), key -> new ArrayList<>()).add(item);
		}
		return grouped;
	}

	private Map<Integer, BigDecimal> allocateLayerBudgets(int amountEur,
							 Map<Integer, List<AssessmentItem>> byLayer,
							 Map<Integer, BigDecimal> layerTargets,
							 LayerTargetRiskThresholds fallback,
							 Map<Integer, LayerTargetRiskThresholds> thresholdsByLayer) {
		Map<Integer, BigDecimal> budgets = new LinkedHashMap<>();
		if (amountEur <= 0) {
			return budgets;
		}
		Map<Integer, BigDecimal> eligibleTargets = new LinkedHashMap<>();
		BigDecimal targetSum = BigDecimal.ZERO;
		for (int layer = 1; layer <= 5; layer++) {
			List<AssessmentItem> layerItems = byLayer.getOrDefault(layer, List.of());
			LayerTargetRiskThresholds layerThresholds =
					RiskThresholdsUtil.resolveForLayer(thresholdsByLayer, fallback, layer);
			double layerCutoff = layerThresholds.getHighMin();
			boolean hasEligible = layerItems.stream().anyMatch(item -> item.score() < layerCutoff);
			if (!hasEligible) {
				continue;
			}
			BigDecimal weight = layerTargets == null ? BigDecimal.ZERO : layerTargets.getOrDefault(layer, BigDecimal.ZERO);
			if (weight == null || weight.signum() < 0) {
				weight = BigDecimal.ZERO;
			}
			eligibleTargets.put(layer, weight);
			targetSum = targetSum.add(weight);
		}
		if (eligibleTargets.isEmpty()) {
			for (int layer = 1; layer <= 5; layer++) {
				budgets.put(layer, BigDecimal.ZERO);
			}
			return budgets;
		}
		if (targetSum.signum() == 0) {
			BigDecimal equal = BigDecimal.ONE.divide(BigDecimal.valueOf(eligibleTargets.size()), 8, RoundingMode.HALF_UP);
			for (Integer layer : eligibleTargets.keySet()) {
				eligibleTargets.put(layer, equal);
			}
			targetSum = BigDecimal.ONE;
		}
		Map<Integer, BigDecimal> raw = new LinkedHashMap<>();
		BigDecimal total = BigDecimal.valueOf(amountEur);
		for (Map.Entry<Integer, BigDecimal> entry : eligibleTargets.entrySet()) {
			BigDecimal normalized = entry.getValue().divide(targetSum, 8, RoundingMode.HALF_UP);
			raw.put(entry.getKey(), total.multiply(normalized));
		}
		budgets.putAll(roundLayerAmounts(raw, total));
		for (int layer = 1; layer <= 5; layer++) {
			budgets.putIfAbsent(layer, BigDecimal.ZERO);
		}
		return budgets;
	}

	private Map<Integer, BigDecimal> roundLayerAmounts(Map<Integer, BigDecimal> raw, BigDecimal total) {
		Map<Integer, BigDecimal> rounded = new LinkedHashMap<>();
		Map<Integer, BigDecimal> remainders = new LinkedHashMap<>();
		BigDecimal sum = BigDecimal.ZERO;
		for (Map.Entry<Integer, BigDecimal> entry : raw.entrySet()) {
			BigDecimal floor = entry.getValue().setScale(0, RoundingMode.FLOOR);
			rounded.put(entry.getKey(), floor);
			remainders.put(entry.getKey(), entry.getValue().subtract(floor));
			sum = sum.add(floor);
		}
		int remaining = total.subtract(sum).intValue();
		List<Integer> order = new ArrayList<>(remainders.keySet());
		order.sort((left, right) -> {
			int compare = remainders.get(right).compareTo(remainders.get(left));
			if (compare != 0) {
				return compare;
			}
			return Integer.compare(left, right);
		});
		int index = 0;
		while (remaining > 0 && !order.isEmpty()) {
			Integer layer = order.get(index % order.size());
			rounded.put(layer, rounded.get(layer).add(BigDecimal.ONE));
			remaining -= 1;
			index += 1;
		}
		return rounded;
	}

	private Map<String, BigDecimal> allocateInstrumentAmounts(List<AssessmentItem> items,
							  BigDecimal layerBudget,
							  Map<String, BigDecimal> weights,
							  double scoreCutoff) {
		Map<String, BigDecimal> allocations = new LinkedHashMap<>();
		if (layerBudget == null || layerBudget.signum() <= 0 || items.isEmpty()) {
			for (AssessmentItem item : items) {
				allocations.put(item.isin(), BigDecimal.ZERO);
			}
			return allocations;
		}
		List<AssessmentItem> eligible = new ArrayList<>();
		for (AssessmentItem item : items) {
			if (item.score() < scoreCutoff) {
				eligible.add(item);
			}
		}
		if (eligible.isEmpty()) {
			for (AssessmentItem item : items) {
				allocations.put(item.isin(), BigDecimal.ZERO);
			}
			return allocations;
		}
		Map<String, BigDecimal> raw = new LinkedHashMap<>();
		BigDecimal sum = BigDecimal.ZERO;
		for (AssessmentItem item : eligible) {
			BigDecimal weight = weights.getOrDefault(item.isin(), BigDecimal.ZERO);
			raw.put(item.isin(), layerBudget.multiply(weight));
			BigDecimal floor = raw.get(item.isin()).setScale(0, RoundingMode.FLOOR);
			sum = sum.add(floor);
		}
		Map<String, BigDecimal> rounded = new LinkedHashMap<>();
		Map<String, BigDecimal> remainders = new LinkedHashMap<>();
		for (AssessmentItem item : eligible) {
			BigDecimal value = raw.getOrDefault(item.isin(), BigDecimal.ZERO);
			BigDecimal floor = value.setScale(0, RoundingMode.FLOOR);
			rounded.put(item.isin(), floor);
			remainders.put(item.isin(), value.subtract(floor));
		}
		int remaining = layerBudget.subtract(sum).intValue();
		List<AssessmentItem> order = new ArrayList<>(eligible);
		order.sort((left, right) -> {
			int compare = remainders.get(right.isin()).compareTo(remainders.get(left.isin()));
			if (compare != 0) {
				return compare;
			}
			compare = Integer.compare(left.score(), right.score());
			if (compare != 0) {
				return compare;
			}
			return left.isin().compareTo(right.isin());
		});
		int index = 0;
		while (remaining > 0 && !order.isEmpty()) {
			AssessmentItem item = order.get(index % order.size());
			rounded.put(item.isin(), rounded.get(item.isin()).add(BigDecimal.ONE));
			remaining -= 1;
			index += 1;
		}
		for (AssessmentItem item : items) {
			BigDecimal allocation = rounded.getOrDefault(item.isin(), BigDecimal.ZERO);
			allocations.put(item.isin(), allocation);
		}
		return allocations;
	}

	private Map<String, BigDecimal> computeWeights(List<AssessmentItem> items,
							  Map<String, KbExtraction> extractions,
							  CriteriaTracker criteria,
							  double scoreCutoff,
							  Integer layer) {
		List<AssessmentItem> eligible = items.stream()
				.filter(item -> item.score() < scoreCutoff)
				.toList();
		if (eligible.isEmpty()) {
			return Map.of();
		}
		if (eligible.size() == 1) {
			return Map.of(eligible.get(0).isin(), BigDecimal.ONE);
		}
		boolean useScoreWeighting = eligible.size() > 1;
		Map<String, Double> scoreFactors = useScoreWeighting
				? buildScoreWeightFactors(eligible, scoreCutoff)
				: Map.of();
		boolean useCost = true;
		boolean useBenchmark = true;
		boolean useRegions = true;
		boolean useHoldings = true;
		boolean useSectors = true;
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

		for (AssessmentItem item : eligible) {
			KbExtraction extraction = extractions.get(item.isin());
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
					regionWeights.put(item.isin(), regionWeight);
				}
				Map<String, BigDecimal> holdingWeight = normalizeHoldingWeights(payload.topHoldings());
				if (holdingWeight != null && !holdingWeight.isEmpty()) {
					holdingWeights.put(item.isin(), holdingWeight);
				}
				Map<String, BigDecimal> sectorWeight = normalizeSectorWeights(payload.sectors(), payload.gicsSector());
				if (sectorWeight != null && !sectorWeight.isEmpty()) {
					sectorWeights.put(item.isin(), sectorWeight);
				}
				valuationScore = computeValuationScore(payload);
				dataPenalty = computeDataQualityPenalty(payload);
			}
			if (ter == null) {
				useCost = false;
			} else {
				ters.put(item.isin(), ter);
			}
			if (benchmark == null || benchmark.isBlank()) {
				useBenchmark = false;
			} else {
				benchmarks.put(item.isin(), benchmark.trim());
			}
			if (regions == null || regions.isEmpty()) {
				useRegions = false;
			} else {
				regionNames.put(item.isin(), regions);
			}
			if (holdings == null || holdings.isEmpty()) {
				useHoldings = false;
			} else {
				holdingNames.put(item.isin(), holdings);
			}
			if (sectors == null || sectors.isEmpty()) {
				useSectors = false;
			} else {
				sectorNames.put(item.isin(), sectors);
			}
			if (valuationScore == null) {
				useValuation = false;
			} else {
				valuationScores.put(item.isin(), valuationScore);
			}
			if (dataPenalty > 0) {
				dataPenalties.put(item.isin(), dataPenalty);
			}
		}

		boolean useRedundancy = useBenchmark || useRegions || useHoldings || useSectors;
		if (!useCost && !useRedundancy && !useValuation) {
			return scoreWeightedWeights(eligible, scoreFactors, criteria, useScoreWeighting);
		}

		Map<String, Integer> benchmarkCounts = new HashMap<>();
		if (useBenchmark) {
			for (String benchmark : benchmarks.values()) {
				benchmarkCounts.merge(benchmark, 1, Integer::sum);
			}
		}

		Map<String, BigDecimal> scores = new HashMap<>();
		BigDecimal total = BigDecimal.ZERO;
		for (AssessmentItem item : eligible) {
			BigDecimal score = BigDecimal.ONE;
			if (useCost) {
				BigDecimal ter = ters.get(item.isin());
				if (ter != null) {
					BigDecimal divisor = BigDecimal.ONE.add(ter.max(BigDecimal.ZERO));
					score = score.divide(divisor, 8, RoundingMode.HALF_UP);
				}
			}
			if (useRedundancy) {
				double redundancy = computeRedundancy(item.isin(), eligible.size(), benchmarks, benchmarkCounts,
						regionNames, holdingNames, sectorNames, regionWeights, holdingWeights, sectorWeights,
						useBenchmark, useRegions, useHoldings, useSectors, sectorWeightFactor(layer));
				BigDecimal uniqueness = BigDecimal.ONE.subtract(BigDecimal.valueOf(redundancy));
				if (uniqueness.compareTo(BigDecimal.ZERO) < 0) {
					uniqueness = BigDecimal.ZERO;
				}
				score = score.multiply(uniqueness);
			}
			if (useValuation) {
				BigDecimal valuationScore = valuationScores.get(item.isin());
				if (valuationScore != null) {
					BigDecimal factor = BigDecimal.valueOf(0.7)
							.add(valuationScore.multiply(BigDecimal.valueOf(0.3)));
					score = score.multiply(factor);
				}
			}
			double penalty = dataPenalties.getOrDefault(item.isin(), 0.0);
			if (penalty > 0) {
				double factor = Math.max(0.0, 1.0 - penalty);
				score = score.multiply(BigDecimal.valueOf(factor));
			}
			if (useScoreWeighting) {
				double scoreFactor = scoreFactors.getOrDefault(item.isin(), 1.0);
				score = score.multiply(BigDecimal.valueOf(scoreFactor));
			}
			scores.put(item.isin(), score);
			total = total.add(score);
		}
		if (total.signum() <= 0) {
			return scoreWeightedWeights(eligible, scoreFactors, criteria, useScoreWeighting);
		}
		Map<String, BigDecimal> weights = new HashMap<>();
		for (AssessmentItem item : eligible) {
			BigDecimal score = scores.getOrDefault(item.isin(), BigDecimal.ZERO);
			weights.put(item.isin(), score.divide(total, 8, RoundingMode.HALF_UP));
		}
		if (useScoreWeighting) {
			criteria.addAllocationCriterion("Assessment score weighting");
		}
		criteria.addAllocationCriterion("KB weighting");
		return weights;
	}

	private Map<String, Double> buildScoreWeightFactors(List<AssessmentItem> eligible, double scoreCutoff) {
		Map<String, Double> factors = new HashMap<>();
		for (AssessmentItem item : eligible) {
			if (item == null || item.isin() == null) {
				continue;
			}
			factors.put(item.isin(), scoreWeightFactor(item.score(), scoreCutoff));
		}
		return factors;
	}

	private Map<String, BigDecimal> scoreWeightedWeights(List<AssessmentItem> eligible,
												 Map<String, Double> scoreFactors,
												 CriteriaTracker criteria,
												 boolean useScoreWeighting) {
		if (eligible == null || eligible.isEmpty()) {
			return Map.of();
		}
		if (eligible.size() == 1) {
			return Map.of(eligible.get(0).isin(), BigDecimal.ONE);
		}
		Map<String, BigDecimal> weights = new HashMap<>();
		BigDecimal total = BigDecimal.ZERO;
		for (AssessmentItem item : eligible) {
			double factor = scoreFactors == null ? 1.0 : scoreFactors.getOrDefault(item.isin(), 1.0);
			BigDecimal weight = BigDecimal.valueOf(Math.max(0.0, factor));
			weights.put(item.isin(), weight);
			total = total.add(weight);
		}
		if (total.signum() <= 0) {
			return equalWeights(eligible);
		}
		Map<String, BigDecimal> normalized = new HashMap<>();
		for (AssessmentItem item : eligible) {
			BigDecimal weight = weights.getOrDefault(item.isin(), BigDecimal.ZERO);
			normalized.put(item.isin(), weight.divide(total, 8, RoundingMode.HALF_UP));
		}
		if (useScoreWeighting) {
			criteria.addAllocationCriterion("Assessment score weighting");
		}
		return normalized;
	}

	private Map<String, BigDecimal> equalWeights(List<AssessmentItem> items) {
		Map<String, BigDecimal> weights = new HashMap<>();
		if (items == null || items.isEmpty()) {
			return weights;
		}
		BigDecimal share = BigDecimal.ONE.divide(BigDecimal.valueOf(items.size()), 8, RoundingMode.HALF_UP);
		for (AssessmentItem item : items) {
			weights.put(item.isin(), share);
		}
		return weights;
	}

	private double computeRedundancy(String isin,
							 int total,
							 Map<String, String> benchmarks,
							 Map<String, Integer> benchmarkCounts,
							 Map<String, Set<String>> regions,
							 Map<String, Set<String>> holdings,
							 Map<String, Set<String>> sectors,
							 Map<String, Map<String, BigDecimal>> regionWeights,
							 Map<String, Map<String, BigDecimal>> holdingWeights,
							 Map<String, Map<String, BigDecimal>> sectorWeights,
							 boolean useBenchmark,
							 boolean useRegions,
							 boolean useHoldings,
							 boolean useSectors,
							 double sectorWeightFactor) {
		if (total <= 1) {
			return 0.0;
		}
		double sum = 0.0;
		double weightSum = 0.0;
		if (useBenchmark) {
			String benchmark = benchmarks.get(isin);
			if (benchmark != null) {
				int count = benchmarkCounts.getOrDefault(benchmark, 0);
				sum += Math.max(0.0, (double) (count - 1) / (double) (total - 1));
				weightSum += 1.0;
			}
		}
		if (useRegions) {
			double overlap = averageWeightedOverlap(regionWeights.get(isin), regions, regionWeights);
			if (overlap < 0) {
				overlap = averageOverlap(regions.get(isin), regions);
			}
			if (overlap >= 0) {
				sum += overlap;
				weightSum += 1.0;
			}
		}
		if (useHoldings) {
			double overlap = averageWeightedOverlap(holdingWeights.get(isin), holdings, holdingWeights);
			if (overlap < 0) {
				overlap = averageOverlap(holdings.get(isin), holdings);
			}
			if (overlap >= 0) {
				sum += overlap;
				weightSum += 1.0;
			}
		}
		if (useSectors) {
			double weight = Math.max(0.0, Math.min(1.0, sectorWeightFactor));
			if (weight <= 0.0) {
				// skip
			} else {
				double overlap = averageWeightedOverlap(sectorWeights.get(isin), sectors, sectorWeights);
				if (overlap < 0) {
					overlap = averageOverlap(sectors.get(isin), sectors);
				}
				if (overlap >= 0) {
					sum += overlap * weight;
					weightSum += weight;
				}
			}
		}
		if (weightSum == 0.0) {
			return 0.0;
		}
		return sum / weightSum;
	}

	private double averageOverlap(Set<String> current, Map<String, Set<String>> others) {
		if (current == null || current.isEmpty() || others == null || others.size() <= 1) {
			return -1.0d;
		}
		double sum = 0.0d;
		int count = 0;
		for (Map.Entry<String, Set<String>> entry : others.entrySet()) {
			Set<String> candidate = entry.getValue();
			if (candidate == null || candidate.isEmpty() || candidate == current) {
				continue;
			}
			int overlap = 0;
			for (String value : current) {
				if (candidate.contains(value)) {
					overlap += 1;
				}
			}
			int denom = Math.max(current.size(), candidate.size());
			sum += denom <= 0 ? 0.0d : (double) overlap / (double) denom;
			count += 1;
		}
		if (count == 0) {
			return -1.0d;
		}
		return sum / (double) count;
	}

	private double averageWeightedOverlap(Map<String, BigDecimal> current,
								 Map<String, Set<String>> names,
								 Map<String, Map<String, BigDecimal>> weights) {
		if (current == null || current.isEmpty() || names == null || names.size() <= 1) {
			return -1.0d;
		}
		double sum = 0.0d;
		int count = 0;
		for (Map.Entry<String, Map<String, BigDecimal>> entry : weights.entrySet()) {
			Map<String, BigDecimal> candidate = entry.getValue();
			if (candidate == null || candidate.isEmpty() || candidate == current) {
				continue;
			}
			double overlap = 0.0d;
			double denom = 0.0d;
			for (Map.Entry<String, BigDecimal> currentEntry : current.entrySet()) {
				BigDecimal weight = currentEntry.getValue();
				if (weight == null) {
					continue;
				}
				double value = weight.doubleValue();
				double other = candidate.getOrDefault(currentEntry.getKey(), BigDecimal.ZERO).doubleValue();
				overlap += Math.min(value, other);
				denom += value;
			}
			sum += denom <= 0.0 ? 0.0 : overlap / denom;
			count += 1;
		}
		if (count == 0) {
			return -1.0d;
		}
		return sum / (double) count;
	}

	private BigDecimal computeValuationScore(InstrumentDossierExtractionPayload payload) {
		if (payload == null || payload.valuation() == null) {
			return null;
		}
		InstrumentDossierExtractionPayload.ValuationPayload valuation = payload.valuation();
		BigDecimal longtermYield = extractLongtermEarningsYield(valuation);
		BigDecimal holdingsYield = extractHoldingsEarningsYield(valuation);
		BigDecimal currentYield = extractCurrentEarningsYield(valuation);
		BigDecimal priceToBook = extractPriceToBook(valuation);
		BigDecimal evToEbitda = extractEvToEbitda(valuation);
		BigDecimal ebitdaEur = extractEbitdaEur(valuation);
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
		double quality = valuationQualityMultiplier(valuation.peMethod(), valuation.peHorizon(),
				valuation.negEarningsHandling());
		return BigDecimal.valueOf(baseScore * quality);
	}

	private double computeDataQualityPenalty(InstrumentDossierExtractionPayload payload) {
		if (payload == null) {
			return 0.0;
		}
		int missing = payload.missingFields() == null ? 0 : payload.missingFields().size();
		int warnings = payload.warnings() == null ? 0 : payload.warnings().size();
		double penalty = (missing * 0.01) + (warnings * 0.05);
		return Math.min(0.25, Math.max(0.0, penalty));
	}

	private Set<String> normalizeRegionNames(List<InstrumentDossierExtractionPayload.RegionExposurePayload> regions) {
		if (regions == null || regions.isEmpty()) {
			return Set.of();
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (InstrumentDossierExtractionPayload.RegionExposurePayload region : regions) {
			if (region == null || region.name() == null || region.name().isBlank()) {
				continue;
			}
			normalized.add(region.name().trim().toLowerCase(Locale.ROOT));
		}
		return normalized;
	}

	private Set<String> normalizeHoldingNames(List<InstrumentDossierExtractionPayload.HoldingPayload> holdings) {
		if (holdings == null || holdings.isEmpty()) {
			return Set.of();
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (InstrumentDossierExtractionPayload.HoldingPayload holding : holdings) {
			if (holding == null || holding.name() == null || holding.name().isBlank()) {
				continue;
			}
			normalized.add(holding.name().trim().toLowerCase(Locale.ROOT));
		}
		return normalized;
	}

	private Map<String, BigDecimal> normalizeRegionWeights(List<InstrumentDossierExtractionPayload.RegionExposurePayload> regions) {
		if (regions == null || regions.isEmpty()) {
			return Map.of();
		}
		Map<String, BigDecimal> normalized = new LinkedHashMap<>();
		for (InstrumentDossierExtractionPayload.RegionExposurePayload region : regions) {
			if (region == null || region.name() == null || region.name().isBlank()) {
				continue;
			}
			BigDecimal weight = normalizeWeightPct(region.weightPct());
			if (weight != null) {
				normalized.put(region.name().trim().toLowerCase(Locale.ROOT), weight);
			}
		}
		return normalized;
	}

	private Map<String, BigDecimal> normalizeHoldingWeights(List<InstrumentDossierExtractionPayload.HoldingPayload> holdings) {
		if (holdings == null || holdings.isEmpty()) {
			return Map.of();
		}
		Map<String, BigDecimal> normalized = new LinkedHashMap<>();
		for (InstrumentDossierExtractionPayload.HoldingPayload holding : holdings) {
			if (holding == null || holding.name() == null || holding.name().isBlank()) {
				continue;
			}
			BigDecimal weight = normalizeWeightPct(holding.weightPct());
			if (weight != null) {
				normalized.put(holding.name().trim().toLowerCase(Locale.ROOT), weight);
			}
		}
		return normalized;
	}

	private Set<String> normalizeSectorNames(List<InstrumentDossierExtractionPayload.SectorExposurePayload> sectors,
									 String gicsSector) {
		Set<String> normalized = new LinkedHashSet<>();
		if (sectors != null) {
			for (InstrumentDossierExtractionPayload.SectorExposurePayload sector : sectors) {
				if (sector == null || sector.name() == null || sector.name().isBlank()) {
					continue;
				}
				normalized.add(sector.name().trim().toLowerCase(Locale.ROOT));
			}
		}
		if (normalized.isEmpty() && gicsSector != null && !gicsSector.isBlank()) {
			normalized.add(gicsSector.trim().toLowerCase(Locale.ROOT));
		}
		return normalized.isEmpty() ? Set.of() : normalized;
	}

	private Map<String, BigDecimal> normalizeSectorWeights(
			List<InstrumentDossierExtractionPayload.SectorExposurePayload> sectors,
			String gicsSector) {
		Map<String, BigDecimal> normalized = new LinkedHashMap<>();
		if (sectors != null) {
			for (InstrumentDossierExtractionPayload.SectorExposurePayload sector : sectors) {
				if (sector == null || sector.name() == null || sector.name().isBlank()) {
					continue;
				}
				BigDecimal weight = normalizeWeightPct(sector.weightPct());
				if (weight != null) {
					normalized.put(sector.name().trim().toLowerCase(Locale.ROOT), weight);
				}
			}
		}
		if (normalized.isEmpty() && gicsSector != null && !gicsSector.isBlank()) {
			normalized.put(gicsSector.trim().toLowerCase(Locale.ROOT), BigDecimal.ONE);
		}
		return normalized;
	}

	private BigDecimal normalizeWeightPct(BigDecimal weight) {
		if (weight == null || weight.signum() <= 0) {
			return null;
		}
		return weight.compareTo(BigDecimal.ONE) > 0
				? weight.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
				: weight;
	}

	private double sectorWeightFactor(Integer layer) {
		if (layer == null) {
			return 0.0;
		}
		if (layer == 1) {
			return 0.5;
		}
		if (layer == 5) {
			return 0.0;
		}
		return 1.0;
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

	private BigDecimal extractEvToEbitda(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (valuation == null) {
			return null;
		}
		return valuation.evToEbitda();
	}

	private BigDecimal extractEbitdaEur(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (valuation == null) {
			return null;
		}
		return valuation.ebitdaEur();
	}

	private BigDecimal extractDividendYield(InstrumentDossierExtractionPayload payload) {
		if (payload == null || payload.valuation() == null || payload.financials() == null) {
			return null;
		}
		BigDecimal dividend = payload.financials().dividendPerShare();
		BigDecimal price = payload.valuation().price();
		if (dividend == null || price == null || price.signum() <= 0) {
			return null;
		}
		return dividend.divide(price, 8, RoundingMode.HALF_UP);
	}

	private BigDecimal computeLongtermEarningsYield(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (valuation == null || valuation.epsHistory() == null || valuation.epsHistory().isEmpty()) {
			return null;
		}
		BigDecimal price = valuation.price();
		if (price == null || price.signum() <= 0) {
			return null;
		}
		BigDecimal sum = BigDecimal.ZERO;
		int count = 0;
		for (InstrumentDossierExtractionPayload.EpsHistoryPayload entry : valuation.epsHistory()) {
			if (entry == null || entry.eps() == null || entry.eps().signum() <= 0) {
				continue;
			}
			sum = sum.add(entry.eps());
			count += 1;
		}
		if (count == 0) {
			return null;
		}
		BigDecimal avg = sum.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
		return avg.divide(price, 8, RoundingMode.HALF_UP);
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
		double target = 2.0;
		return Math.min(target / value, 1.0);
	}

	private double scoreEbitdaEur(BigDecimal ebitdaEur) {
		if (ebitdaEur == null) {
			return 0.0;
		}
		double value = ebitdaEur.doubleValue();
		if (value <= 0) {
			return 0.0;
		}
		return 1.0;
	}

	private double valuationQualityMultiplier(String peMethod, String peHorizon, String negEarningsHandling) {
		double quality = 1.0;
		if (peMethod != null && peMethod.equalsIgnoreCase("ttm")) {
			quality *= 0.95;
		}
		if (peHorizon != null && peHorizon.equalsIgnoreCase("short")) {
			quality *= 0.95;
		}
		if (negEarningsHandling != null && negEarningsHandling.equalsIgnoreCase("floor")) {
			quality *= 0.98;
		}
		return quality;
	}

	private boolean isEtf(InstrumentDossierExtractionPayload payload) {
		if (payload == null) {
			return false;
		}
		String type = payload.instrumentType();
		return type != null && type.toLowerCase(Locale.ROOT).contains("etf");
	}

	private boolean isSingleStock(InstrumentDossierExtractionPayload payload) {
		if (payload == null) {
			return false;
		}
		String type = normalizeLabel(payload.instrumentType());
		String subClass = normalizeLabel(payload.subClass());
		String notes = normalizeLabel(payload.layerNotes());
		if (notes != null && (notes.contains("single stock") || notes.contains("single-company") || notes.contains("single issuer"))) {
			return true;
		}
		if (subClass != null && subClass.contains("single-stock")) {
			return true;
		}
		if (type == null) {
			return false;
		}
		if (type.contains("etf") || type.contains("fund")) {
			return false;
		}
		return type.contains("stock") || type.contains("share") || type.contains("equity");
	}

	private boolean isReit(InstrumentDossierExtractionPayload payload) {
		if (payload == null) {
			return false;
		}
		String type = payload.instrumentType();
		String subClass = payload.subClass();
		String notes = payload.layerNotes();
		return (type != null && type.toLowerCase(Locale.ROOT).contains("reit"))
				|| (subClass != null && subClass.toLowerCase(Locale.ROOT).contains("reit"))
				|| (notes != null && notes.toLowerCase(Locale.ROOT).contains("reit"));
	}

	private String normalizeLabel(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}

	private record KbExtraction(String isin, String status, InstrumentDossierExtractionPayload payload) {
	}

	private record ApprovedExtraction(String isin, String status, boolean autoApproved) {
	}

	private record InstrumentFallback(String isin, String name, Integer layer) {
	}

	public record AssessmentItem(String isin, String name, Integer layer, int score, boolean badFinancials,
							 List<ScoreComponent> scoreComponents, BigDecimal allocation) {
		public AssessmentItem {
			scoreComponents = scoreComponents == null ? List.of() : List.copyOf(scoreComponents);
			allocation = allocation == null ? BigDecimal.ZERO : allocation;
		}

		public AssessmentItem(String isin, String name, Integer layer, int score, boolean badFinancials,
							 List<ScoreComponent> scoreComponents) {
			this(isin, name, layer, score, badFinancials, scoreComponents, BigDecimal.ZERO);
		}

		public AssessmentItem withAllocation(BigDecimal allocation) {
			return new AssessmentItem(isin, name, layer, score, badFinancials, scoreComponents, allocation);
		}
	}

	public record ScoreComponent(String criterion, double points) {
	}

	private record ScoreResult(int score, boolean badFinancials, List<ScoreComponent> scoreComponents) {
		private ScoreResult {
			scoreComponents = scoreComponents == null ? List.of() : List.copyOf(scoreComponents);
		}
	}

	private record AllocationResult(List<AssessmentItem> items, Map<Integer, BigDecimal> layerBudgets) {
	}

	public record AssessmentResult(double scoreCutoff,
								int amountEur,
								List<AssessmentItem> items,
								List<String> missingIsins,
								List<String> scoreCriteria,
								List<String> allocationCriteria,
								Map<Integer, BigDecimal> layerBudgets,
								LayerTargetRiskThresholds riskThresholds,
								Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer) {
		static AssessmentResult empty(double scoreCutoff,
											int amountEur,
											LayerTargetRiskThresholds riskThresholds,
											Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer) {
			return new AssessmentResult(scoreCutoff, amountEur, List.of(), List.of(), List.of(), List.of(), Map.of(),
					riskThresholds, riskThresholdsByLayer);
		}

		static AssessmentResult missing(double scoreCutoff,
											int amountEur,
											List<String> missingIsins,
											LayerTargetRiskThresholds riskThresholds,
											Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer) {
			return new AssessmentResult(scoreCutoff, amountEur, List.of(), List.copyOf(missingIsins), List.of(), List.of(), Map.of(),
					riskThresholds, riskThresholdsByLayer);
		}
	}

	private static final class CriteriaTracker {
		private final Set<String> scoreCriteria = new LinkedHashSet<>();
		private final Set<String> allocationCriteria = new LinkedHashSet<>();

		void addScoreCriterion(String criterion) {
			if (criterion != null && !criterion.isBlank()) {
				scoreCriteria.add(criterion);
			}
		}

		void addAllocationCriterion(String criterion) {
			if (criterion != null && !criterion.isBlank()) {
				allocationCriteria.add(criterion);
			}
		}

		List<String> scoreCriteria() {
			return List.copyOf(scoreCriteria);
		}

		List<String> allocationCriteria() {
			return List.copyOf(allocationCriteria);
		}
	}
}
