package my.portfoliomanager.app.service;

import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AssessorInstrumentSuggestionService {
	private static final Logger logger = LoggerFactory.getLogger(AssessorInstrumentSuggestionService.class);
	private static final Set<String> COMPLETE_STATUSES = Set.of("COMPLETE", "APPROVED", "APPLIED");
	private static final int MAX_SUGGESTIONS_PER_LAYER = 3;
	private static final double EXISTING_BONUS = 0.2;
	private static final Pattern ACC_PATTERN = Pattern.compile("\\bacc(?:umulating)?\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern DIST_PATTERN = Pattern.compile("\\bdist(?:ributing|ribution)?\\b", Pattern.CASE_INSENSITIVE);
	private static final List<LocaleKeyword> LOCALE_KEYWORDS = List.of(
			new LocaleKeyword("united states", "united states"),
			new LocaleKeyword("usa", "united states"),
			new LocaleKeyword("us", "united states"),
			new LocaleKeyword("europe", "europe"),
			new LocaleKeyword("european", "europe"),
			new LocaleKeyword("emerging", "emerging markets"),
			new LocaleKeyword("emerging markets", "emerging markets"),
			new LocaleKeyword("japan", "japan"),
			new LocaleKeyword("asia", "asia"),
			new LocaleKeyword("global", "global"),
			new LocaleKeyword("world", "global"),
			new LocaleKeyword("uk", "united kingdom"),
			new LocaleKeyword("united kingdom", "united kingdom")
	);
	private static final List<String> THEME_KEYWORDS = List.of(
			"technology",
			"health",
			"dividend",
			"real estate",
			"small cap",
			"mid cap",
			"emerging",
			"sustainable",
			"esg",
			"value",
			"growth",
			"quality",
			"momentum",
			"equal weight",
			"infra",
			"infrastructure"
	);
	private static final BigDecimal DEFAULT_EPS_FLOOR = new BigDecimal("0.10");
	private static final double DEFAULT_PB_TARGET = 2.0;

	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
	private final ObjectMapper objectMapper;

	public AssessorInstrumentSuggestionService(NamedParameterJdbcTemplate namedParameterJdbcTemplate,
											   ObjectMapper objectMapper) {
		this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
		this.objectMapper = objectMapper;
	}

	public SuggestionResult suggest(SuggestionRequest request) {
		if (request == null) {
			return SuggestionResult.empty();
		}
		List<AssessorEngine.SavingPlanItem> savingPlans = request.savingPlans() == null
				? List.of()
				: request.savingPlans();
		Map<Integer, BigDecimal> savingPlanBudgets = normalizeBudgetMap(request.savingPlanBudgets());
		Map<Integer, BigDecimal> oneTimeBudgets = normalizeBudgetMap(request.oneTimeBudgets());
		if (savingPlanBudgets.isEmpty() && oneTimeBudgets.isEmpty()) {
			return SuggestionResult.empty();
		}
		AssessorGapDetectionPolicy gapPolicy = request.gapDetectionPolicy() == null
				? AssessorGapDetectionPolicy.SAVING_PLAN_GAPS
				: request.gapDetectionPolicy();
		Set<String> excludedSnapshotIsins = normalizeIsinSet(request.excludedSnapshotIsins());
		Set<String> existingCoverageIsins = normalizeIsinSet(request.existingInstrumentIsins());

		Map<Integer, Integer> maxSavingPlansPerLayer = normalizeMaxSavingPlans(request.maxSavingPlansPerLayer());
		Map<Integer, Integer> savingPlanCounts = countSavingPlans(savingPlans);
		Set<String> savingPlanIsins = collectSavingPlanIsins(savingPlans);
		Set<String> coverageIsins = new HashSet<>(existingCoverageIsins);
		coverageIsins.addAll(savingPlanIsins);

		Map<String, InstrumentProfile> existingProfiles = loadProfiles(coverageIsins);
		Map<String, InstrumentProfile> fallbackExisting = loadEffectiveProfiles(coverageIsins, existingProfiles.keySet());
		if (!fallbackExisting.isEmpty()) {
			Map<String, InstrumentProfile> merged = new LinkedHashMap<>(existingProfiles);
			merged.putAll(fallbackExisting);
			existingProfiles = merged;
		}
		Map<String, InstrumentProfile> savingPlanProfiles = new LinkedHashMap<>();
		for (String isin : savingPlanIsins) {
			InstrumentProfile profile = existingProfiles.get(isin);
			if (profile != null) {
				savingPlanProfiles.put(isin, profile);
			}
		}
		Map<Integer, LayerCoverage> existingCoverage = buildCoverage(existingProfiles.values());
		Map<Integer, LayerCoverage> savingPlanCoverage = buildCoverage(savingPlanProfiles.values());
		Map<Integer, List<InstrumentProfile>> existingByLayer = groupByLayer(existingProfiles.values());

		Map<String, InstrumentProfile> candidateProfiles = loadCandidateProfiles();
		Map<String, InstrumentProfile> fallbackCandidates = loadEffectiveProfiles(coverageIsins, candidateProfiles.keySet());
		if (!fallbackCandidates.isEmpty()) {
			candidateProfiles.putAll(fallbackCandidates);
		}
		Map<Integer, List<InstrumentProfile>> candidatesByLayer = groupByLayer(candidateProfiles.values());
		Map<Integer, LayerCoverage> candidateCoverage = buildCoverageByLayer(candidatesByLayer);
		Set<String> preferredSavingPlanIsins = new HashSet<>(coverageIsins);
		preferredSavingPlanIsins.removeAll(savingPlanIsins);
		Set<String> preferredOneTimeIsins = new HashSet<>(coverageIsins);

		List<NewInstrumentSuggestion> savingPlanSuggestions = new ArrayList<>();
		List<NewInstrumentSuggestion> oneTimeSuggestions = new ArrayList<>();

		for (int layer = 1; layer <= 5; layer++) {
			List<InstrumentProfile> layerCandidates = candidatesByLayer.getOrDefault(layer, List.of());
			if (layerCandidates.isEmpty()) {
				continue;
			}
			LayerCoverage existing = existingCoverage.getOrDefault(layer, new LayerCoverage());
			LayerCoverage savingPlanExisting = savingPlanCoverage.getOrDefault(layer, new LayerCoverage());
			LayerCoverage available = candidateCoverage.getOrDefault(layer, new LayerCoverage());
			int savingPlanCount = savingPlanCounts.getOrDefault(layer, 0);
			boolean needsBaselinePlan = savingPlanCount == 0;
			MissingCategories savingPlanMissing = gapPolicy == AssessorGapDetectionPolicy.PORTFOLIO_GAPS
					? MissingCategories.from(existing, available)
					: MissingCategories.from(savingPlanExisting, available);
			MissingCategories oneTimeMissing = MissingCategories.from(existing, available);
			boolean skipSavingPlanSuggestions = savingPlanMissing.isEmpty() && !needsBaselinePlan;
			List<InstrumentProfile> savingPlanCandidates = layerCandidates;
			if (!savingPlanIsins.isEmpty()) {
				savingPlanCandidates = layerCandidates.stream()
						.filter(profile -> profile != null && !savingPlanIsins.contains(profile.isin()))
						.toList();
			}

			if (!skipSavingPlanSuggestions && savingPlanBudgets.containsKey(layer) && !savingPlanCandidates.isEmpty()) {
				BigDecimal budget = savingPlanBudgets.get(layer);
				int savingPlanMinimumAmount = resolveSavingPlanMinimumAmount(request.minimumSavingPlanSize(),
						request.minimumRebalancingAmount());
				Integer maxPlans = maxSavingPlansPerLayer.getOrDefault(layer, 0);
				int currentCount = savingPlanCounts.getOrDefault(layer, 0);
				int availableSlots = Math.max(0, maxPlans - currentCount);
				int maxByBudget = resolveMaxByBudget(budget, savingPlanMinimumAmount);
				int maxSuggestions = Math.min(MAX_SUGGESTIONS_PER_LAYER, Math.min(availableSlots, maxByBudget));
				if (maxSuggestions > 0) {
					List<NewInstrumentSuggestion> suggestions = savingPlanMissing.isEmpty()
							? selectSuggestionsWithoutGaps(savingPlanCandidates,
							existingByLayer.getOrDefault(layer, List.of()),
							budget, savingPlanMinimumAmount, maxSuggestions, preferredSavingPlanIsins)
							: selectSuggestions(savingPlanCandidates,
							existingByLayer.getOrDefault(layer, List.of()),
							savingPlanMissing,
							budget, savingPlanMinimumAmount, maxSuggestions, preferredSavingPlanIsins);
					savingPlanSuggestions.addAll(suggestions);
				}
			}

			if (oneTimeBudgets.containsKey(layer)) {
				List<InstrumentProfile> oneTimeCandidates = layerCandidates;
				if (!excludedSnapshotIsins.isEmpty()) {
					oneTimeCandidates = layerCandidates.stream()
							.filter(profile -> profile != null && !excludedSnapshotIsins.contains(profile.isin()))
							.toList();
				}
				if (oneTimeCandidates.isEmpty()) {
					continue;
				}
				BigDecimal budget = oneTimeBudgets.get(layer);
				int maxByBudget = resolveMaxByBudget(budget, request.minimumInstrumentAmount());
				int maxSuggestions = Math.min(MAX_SUGGESTIONS_PER_LAYER, maxByBudget);
				if (maxSuggestions > 0) {
					List<NewInstrumentSuggestion> suggestions = oneTimeMissing.isEmpty()
							? selectSuggestionsWithoutGaps(oneTimeCandidates,
							existingByLayer.getOrDefault(layer, List.of()),
							budget, request.minimumInstrumentAmount(), maxSuggestions, preferredOneTimeIsins)
							: selectSuggestions(oneTimeCandidates,
							existingByLayer.getOrDefault(layer, List.of()),
							oneTimeMissing,
							budget, request.minimumInstrumentAmount(), maxSuggestions, preferredOneTimeIsins);
					if (suggestions.isEmpty() && !oneTimeMissing.isEmpty()) {
						suggestions = selectSuggestionsWithoutGaps(oneTimeCandidates,
								existingByLayer.getOrDefault(layer, List.of()),
								budget, request.minimumInstrumentAmount(), maxSuggestions, preferredOneTimeIsins);
					}
					oneTimeSuggestions.addAll(suggestions);
				}
			}
		}

		return new SuggestionResult(List.copyOf(savingPlanSuggestions), List.copyOf(oneTimeSuggestions));
	}

	Map<Integer, Map<String, BigDecimal>> computeSavingPlanWeights(SavingPlanWeightRequest request) {
		if (request == null || request.savingPlans() == null || request.savingPlans().isEmpty()) {
			return Map.of();
		}
		AssessorGapDetectionPolicy gapPolicy = request.gapDetectionPolicy() == null
				? AssessorGapDetectionPolicy.SAVING_PLAN_GAPS
				: request.gapDetectionPolicy();
		Set<String> existingCoverageIsins = normalizeIsinSet(request.existingInstrumentIsins());
		List<AssessorEngine.SavingPlanItem> savingPlans = request.savingPlans();
		Set<String> savingPlanIsins = collectSavingPlanIsins(savingPlans);
		Set<String> coverageIsins = new HashSet<>(existingCoverageIsins);
		coverageIsins.addAll(savingPlanIsins);

		Map<String, InstrumentProfile> existingProfiles = loadProfiles(coverageIsins);
		Map<String, InstrumentProfile> fallbackExisting = loadEffectiveProfiles(coverageIsins, existingProfiles.keySet());
		if (!fallbackExisting.isEmpty()) {
			Map<String, InstrumentProfile> merged = new LinkedHashMap<>(existingProfiles);
			merged.putAll(fallbackExisting);
			existingProfiles = merged;
		}
		Map<String, InstrumentProfile> savingPlanProfiles = new LinkedHashMap<>();
		for (String isin : savingPlanIsins) {
			InstrumentProfile profile = existingProfiles.get(isin);
			if (profile != null) {
				savingPlanProfiles.put(isin, profile);
			}
		}
		Map<Integer, LayerCoverage> existingCoverage = buildCoverage(existingProfiles.values());
		Map<Integer, LayerCoverage> savingPlanCoverage = buildCoverage(savingPlanProfiles.values());
		Map<Integer, List<InstrumentProfile>> existingByLayer = groupByLayer(existingProfiles.values());

		Map<String, InstrumentProfile> candidateProfiles = loadCandidateProfiles();
		Map<String, InstrumentProfile> fallbackCandidates = loadEffectiveProfiles(coverageIsins, candidateProfiles.keySet());
		if (!fallbackCandidates.isEmpty()) {
			candidateProfiles.putAll(fallbackCandidates);
		}
		Map<Integer, List<InstrumentProfile>> candidatesByLayer = groupByLayer(candidateProfiles.values());
		Map<Integer, LayerCoverage> candidateCoverage = buildCoverageByLayer(candidatesByLayer);

		Map<Integer, Map<String, BigDecimal>> weightsByLayer = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			int layerIndex = layer;
			List<InstrumentProfile> layerProfiles = candidatesByLayer.getOrDefault(layer, List.of());
			if (layerProfiles.isEmpty()) {
				continue;
			}
			LayerCoverage existing = existingCoverage.getOrDefault(layer, new LayerCoverage());
			LayerCoverage savingPlanExisting = savingPlanCoverage.getOrDefault(layer, new LayerCoverage());
			LayerCoverage available = candidateCoverage.getOrDefault(layer, new LayerCoverage());
			MissingCategories missing = gapPolicy == AssessorGapDetectionPolicy.PORTFOLIO_GAPS
					? MissingCategories.from(existing, available)
					: MissingCategories.from(savingPlanExisting, available);
			Map<String, BigDecimal> layerWeights = new LinkedHashMap<>();
			List<InstrumentProfile> savingPlanLayerProfiles = savingPlanProfiles.values().stream()
					.filter(profile -> profile != null && profile.layer() == layerIndex)
					.sorted(Comparator.comparing(InstrumentProfile::isin))
					.toList();
			for (InstrumentProfile profile : savingPlanLayerProfiles) {
				double score = scoreCandidate(profile, existingByLayer.getOrDefault(layer, List.of()), missing);
				if (score <= 0) {
					score = 0.1;
				}
				layerWeights.put(profile.isin(), BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP));
			}
			if (!layerWeights.isEmpty()) {
				weightsByLayer.put(layer, Map.copyOf(layerWeights));
			}
		}
		return Map.copyOf(weightsByLayer);
	}

	private Map<Integer, Integer> countSavingPlans(List<AssessorEngine.SavingPlanItem> plans) {
		Map<Integer, Integer> counts = new HashMap<>();
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

	private Set<String> collectSavingPlanIsins(List<AssessorEngine.SavingPlanItem> plans) {
		Set<String> isins = new HashSet<>();
		if (plans == null) {
			return isins;
		}
		for (AssessorEngine.SavingPlanItem plan : plans) {
			if (plan == null) {
				continue;
			}
			String isin = normalizeIsin(plan.isin());
			if (isin != null) {
				isins.add(isin);
			}
		}
		return isins;
	}

	private Map<Integer, Integer> normalizeMaxSavingPlans(Map<Integer, Integer> input) {
		Map<Integer, Integer> values = new HashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			Integer value = input == null ? null : input.get(layer);
			if (value == null || value < 1) {
				value = 0;
			}
			values.put(layer, value);
		}
		return values;
	}

	private Map<Integer, BigDecimal> normalizeBudgetMap(Map<Integer, BigDecimal> raw) {
		Map<Integer, BigDecimal> normalized = new LinkedHashMap<>();
		if (raw == null) {
			return normalized;
		}
		for (Map.Entry<Integer, BigDecimal> entry : raw.entrySet()) {
			if (entry.getKey() == null || entry.getValue() == null) {
				continue;
			}
			if (entry.getValue().signum() <= 0) {
				continue;
			}
			normalized.put(entry.getKey(), entry.getValue());
		}
		return normalized;
	}

	private int resolveMaxByBudget(BigDecimal budget, int minimumAmount) {
		if (budget == null || budget.signum() <= 0 || minimumAmount <= 0) {
			return 0;
		}
		return budget.divide(BigDecimal.valueOf(minimumAmount), 0, RoundingMode.DOWN).intValue();
	}

	private int resolveSavingPlanMinimumAmount(int minimumSavingPlanSize, int minimumRebalancingAmount) {
		int minSaving = minimumSavingPlanSize < 1 ? 0 : minimumSavingPlanSize;
		int minRebalance = minimumRebalancingAmount < 1 ? 0 : minimumRebalancingAmount;
		return Math.max(minSaving, minRebalance);
	}

	private List<NewInstrumentSuggestion> selectSuggestions(List<InstrumentProfile> candidates,
															List<InstrumentProfile> existingProfiles,
															MissingCategories missing,
															BigDecimal budget,
															int minimumAmount,
															int maxSuggestions,
															Set<String> preferredIsins) {
		if (candidates == null || candidates.isEmpty() || maxSuggestions <= 0) {
			return List.of();
		}
		List<CoverageGap> gaps = missing.toGapList();
		gaps.sort(Comparator.comparing(CoverageGap::type));
		Set<String> selectedIsins = new HashSet<>();
		List<SelectedSuggestion> selected = new ArrayList<>();
		for (CoverageGap gap : gaps) {
			if (selected.size() >= maxSuggestions) {
				break;
			}
			InstrumentProfile selectedCandidate = selectCandidateForGap(candidates, existingProfiles,
					missing, gap, selectedIsins, preferredIsins);
			if (selectedCandidate == null) {
				continue;
			}
			selectedIsins.add(selectedCandidate.isin());
			boolean hasExisting = existingProfiles != null && !existingProfiles.isEmpty();
			double redundancy = computeRedundancy(selectedCandidate, existingProfiles);
			String rationale = buildRationale(selectedCandidate, missing.coveredBy(selectedCandidate), gap, redundancy, hasExisting);
			selected.add(new SelectedSuggestion(selectedCandidate, rationale));
		}
		if (selected.isEmpty()) {
			return List.of();
		}
		Map<String, BigDecimal> allocations = allocateSuggestionAmounts(selected, budget, minimumAmount);
		List<NewInstrumentSuggestion> suggestions = new ArrayList<>();
		for (SelectedSuggestion suggestion : selected) {
			BigDecimal amount = allocations.get(suggestion.profile().isin());
			if (amount == null || amount.signum() <= 0) {
				continue;
			}
			suggestions.add(new NewInstrumentSuggestion(
					suggestion.profile().isin(),
					suggestion.profile().name(),
					suggestion.profile().layer(),
					amount,
					suggestion.rationale()));
		}
		return suggestions;
	}

	private List<NewInstrumentSuggestion> selectSuggestionsWithoutGaps(List<InstrumentProfile> candidates,
																	   List<InstrumentProfile> existingProfiles,
																	   BigDecimal budget,
																	   int minimumAmount,
																	   int maxSuggestions,
																	   Set<String> preferredIsins) {
		if (candidates == null || candidates.isEmpty() || maxSuggestions <= 0) {
			return List.of();
		}
		MissingCategories empty = MissingCategories.empty();
		List<InstrumentProfile> ordered = new ArrayList<>(candidates);
		Comparator<InstrumentProfile> comparator = Comparator
				.comparingDouble((InstrumentProfile profile) ->
						scoreCandidate(profile, existingProfiles, empty, preferredIsins))
				.reversed()
				.thenComparing(InstrumentProfile::isin);
		ordered.sort(comparator);
		List<SelectedSuggestion> selected = new ArrayList<>();
		for (InstrumentProfile profile : ordered) {
			if (selected.size() >= maxSuggestions) {
				break;
			}
			String rationale = buildBaselineRationale(profile, existingProfiles);
			selected.add(new SelectedSuggestion(profile, rationale));
		}
		if (selected.isEmpty()) {
			return List.of();
		}
		Map<String, BigDecimal> allocations = allocateSuggestionAmounts(selected, budget, minimumAmount);
		List<NewInstrumentSuggestion> suggestions = new ArrayList<>();
		for (SelectedSuggestion suggestion : selected) {
			BigDecimal amount = allocations.get(suggestion.profile().isin());
			if (amount == null || amount.signum() <= 0) {
				continue;
			}
			suggestions.add(new NewInstrumentSuggestion(
					suggestion.profile().isin(),
					suggestion.profile().name(),
					suggestion.profile().layer(),
					amount,
					suggestion.rationale()));
		}
		return suggestions;
	}

	private String buildBaselineRationale(InstrumentProfile candidate,
										  List<InstrumentProfile> existingProfiles) {
		if (candidate == null) {
			return "";
		}
		boolean hasExisting = existingProfiles != null && !existingProfiles.isEmpty();
		double redundancy = computeRedundancy(candidate, existingProfiles);
		String selection = buildSelectionReason(candidate, redundancy, hasExisting);
		if (selection.isBlank()) {
			return "Adds exposure to build a baseline allocation in this layer.";
		}
		return "Adds exposure to build a baseline allocation in this layer. Selected because " + selection + ".";
	}

	private Map<String, BigDecimal> allocateSuggestionAmounts(List<SelectedSuggestion> selected,
															  BigDecimal budget,
															  int minimumAmount) {
		if (selected == null || selected.isEmpty() || budget == null || budget.signum() <= 0 || minimumAmount <= 0) {
			return Map.of();
		}
		int count = selected.size();
		BigDecimal min = BigDecimal.valueOf(minimumAmount);
		BigDecimal total = budget.setScale(0, RoundingMode.HALF_UP);
		BigDecimal minTotal = min.multiply(BigDecimal.valueOf(count));
		if (total.compareTo(minTotal) < 0) {
			return Map.of();
		}
		BigDecimal remaining = total.subtract(minTotal);
		BigDecimal perExtra = remaining.divide(BigDecimal.valueOf(count), 0, RoundingMode.FLOOR);
		BigDecimal base = min.add(perExtra);
		Map<String, BigDecimal> allocations = new LinkedHashMap<>();
		for (SelectedSuggestion suggestion : selected) {
			allocations.put(suggestion.profile().isin(), base);
		}
		BigDecimal distributed = base.multiply(BigDecimal.valueOf(count));
		BigDecimal diff = total.subtract(distributed);
		int steps = diff.intValue();
		if (steps > 0) {
			List<SelectedSuggestion> order = new ArrayList<>(selected);
			order.sort(Comparator.comparing(suggestion -> suggestion.profile().isin()));
			int index = 0;
			while (steps > 0) {
				SelectedSuggestion suggestion = order.get(index % order.size());
				String isin = suggestion.profile().isin();
				allocations.put(isin, allocations.get(isin).add(BigDecimal.ONE));
				steps -= 1;
				index += 1;
			}
		}
		return allocations;
	}

	private InstrumentProfile selectCandidateForGap(List<InstrumentProfile> candidates,
													List<InstrumentProfile> existingProfiles,
													MissingCategories missing,
													CoverageGap gap,
													Set<String> selectedIsins,
													Set<String> preferredIsins) {
		return candidates.stream()
				.filter(profile -> profile != null && !selectedIsins.contains(profile.isin()))
				.filter(profile -> profile.matchesGap(gap))
				.max(Comparator.comparingDouble(profile ->
						scoreCandidate(profile, existingProfiles, missing, preferredIsins)))
				.orElse(null);
	}

	private double scoreCandidate(InstrumentProfile candidate,
								  List<InstrumentProfile> existingProfiles,
								  MissingCategories missing) {
		double costScore = candidate.ongoingChargesPct() == null
				? 0.5
				: 1.0 / (1.0 + candidate.ongoingChargesPct().doubleValue());
		double redundancy = computeRedundancy(candidate, existingProfiles);
		double uniquenessScore = 1.0 - redundancy;
		double valuationScore = computeValuationScore(candidate);
		double typeScore = isPreferredInstrument(candidate) ? 0.3 : 0.0;
		double penalty = (candidate.singleStock() && candidate.layer() <= 3) ? 0.4 : 0.0;
		int gapCoverage = missing.coverageCount(candidate);
		double gapScore = Math.min(0.4, gapCoverage * 0.1);
		return (costScore * 0.35) + (uniquenessScore * 0.35) + (valuationScore * 0.15) + typeScore + gapScore - penalty;
	}

	private double scoreCandidate(InstrumentProfile candidate,
								  List<InstrumentProfile> existingProfiles,
								  MissingCategories missing,
								  Set<String> preferredIsins) {
		double score = scoreCandidate(candidate, existingProfiles, missing);
		if (preferredIsins != null && preferredIsins.contains(candidate.isin())) {
			score += EXISTING_BONUS;
		}
		return score;
	}

	private double computeRedundancy(InstrumentProfile candidate, java.util.Collection<InstrumentProfile> existing) {
		if (existing == null || existing.isEmpty()) {
			return 0.0;
		}
		double sum = 0.0;
		int components = 0;

		if (candidate.benchmarkIndex() != null) {
			components += 1;
			int matches = 0;
			for (InstrumentProfile profile : existing) {
				if (candidate.benchmarkIndex().equalsIgnoreCase(profile.benchmarkIndex())) {
					matches += 1;
				}
			}
			sum += (double) matches / (double) existing.size();
		}

		double regionOverlap = averageOverlap(candidate.regions(), existing, InstrumentProfile::regions);
		if (regionOverlap >= 0) {
			components += 1;
			sum += regionOverlap;
		}

		double holdingOverlap = averageOverlap(candidate.holdings(), existing, InstrumentProfile::holdings);
		if (holdingOverlap >= 0) {
			components += 1;
			sum += holdingOverlap;
		}

		if (components == 0) {
			return 0.0;
		}
		return sum / components;
	}

	private double computeValuationScore(InstrumentProfile candidate) {
		if (candidate == null) {
			return 0.0;
		}
		double longtermYieldScore = scoreEarningsYield(candidate.earningsYieldLongterm());
		double currentYieldScore = scoreEarningsYield(candidate.earningsYieldCurrent());
		double dividendYieldScore = scoreDividendYield(candidate.dividendYield());
		double evScore = scoreEvToEbitda(candidate.evToEbitda());
		double ebitdaScore = scoreEbitdaEur(candidate.ebitdaEur());
		double netIncomeScore = scoreNetIncomeEur(candidate.netIncomeEur());
		double revenueScore = scoreRevenueEur(candidate.revenueEur());
		double pbScore = scorePriceToBook(candidate.priceToBook());
		double weightSum = 0.0;
		double scoreSum = 0.0;
		if (longtermYieldScore > 0) {
			scoreSum += longtermYieldScore * 0.35;
			weightSum += 0.35;
		}
		if (currentYieldScore > 0) {
			scoreSum += currentYieldScore * 0.15;
			weightSum += 0.15;
		}
		if (dividendYieldScore > 0) {
			scoreSum += dividendYieldScore * 0.07;
			weightSum += 0.07;
		}
		if (evScore > 0) {
			scoreSum += evScore * 0.18;
			weightSum += 0.18;
		}
		if (ebitdaScore > 0) {
			scoreSum += ebitdaScore * 0.10;
			weightSum += 0.10;
		}
		if (netIncomeScore > 0) {
			scoreSum += netIncomeScore * 0.08;
			weightSum += 0.08;
		}
		if (revenueScore > 0) {
			scoreSum += revenueScore * 0.04;
			weightSum += 0.04;
		}
		if (pbScore > 0) {
			scoreSum += pbScore * 0.03;
			weightSum += 0.03;
		}
		if (weightSum <= 0) {
			return 0.0;
		}
		return scoreSum / weightSum;
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

	private double averageOverlap(Set<String> base,
								  java.util.Collection<InstrumentProfile> existing,
								  java.util.function.Function<InstrumentProfile, Set<String>> extractor) {
		if (base == null || base.isEmpty() || existing == null || existing.isEmpty()) {
			return -1.0;
		}
		double sum = 0.0;
		int count = 0;
		for (InstrumentProfile profile : existing) {
			Set<String> other = extractor.apply(profile);
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
			count += 1;
		}
		if (count == 0) {
			return -1.0;
		}
		return sum / (double) count;
	}

	private boolean isPreferredInstrument(InstrumentProfile candidate) {
		if (candidate == null) {
			return false;
		}
		String type = normalizeLabel(candidate.instrumentType());
		String asset = normalizeLabel(candidate.assetClass());
		return (type != null && (type.contains("etf") || type.contains("ucits")))
				|| (asset != null && asset.contains("fund"));
	}

	private String buildRationale(InstrumentProfile candidate,
								  List<CoverageGap> coveredGaps,
								  CoverageGap primaryGap,
								  double redundancy,
								  boolean hasExisting) {
		List<String> gapReasons = new ArrayList<>();
		if (primaryGap != null) {
			gapReasons.add(formatGap(primaryGap));
		}
		for (CoverageGap gap : coveredGaps) {
			if (gapReasons.size() >= 2) {
				break;
			}
			String reason = formatGap(gap);
			if (!gapReasons.contains(reason)) {
				gapReasons.add(reason);
			}
		}
		String gapSentence = gapReasons.isEmpty() ? "Adds coverage for a missing gap." : String.join(" ", gapReasons);
		String selectionSentence = buildSelectionReason(candidate, redundancy, hasExisting);
		if (selectionSentence.isBlank()) {
			return gapSentence;
		}
		return gapSentence + " Selected because " + selectionSentence + ".";
	}

	private String formatGap(CoverageGap gap) {
		if (gap == null) {
			return "";
		}
		return switch (gap.type()) {
			case SUB_CLASS -> "Fills missing sub-class: " + gap.value() + ".";
			case THEME -> "Adds missing theme exposure: " + gap.value() + ".";
			case LOCALISATION -> "Adds regional exposure to " + gap.value() + ".";
			case DISTRIBUTION -> "Adds " + gap.value() + " share class not present in this layer.";
		};
	}

	private String buildSelectionReason(InstrumentProfile candidate, double redundancy, boolean hasExisting) {
		List<String> reasons = new ArrayList<>();
		if (candidate.ongoingChargesPct() != null) {
			reasons.add("low ongoing charges (" + candidate.ongoingChargesPct().setScale(2, RoundingMode.HALF_UP) + "%)");
		}
		if (candidate.benchmarkIndex() != null && !candidate.benchmarkIndex().isBlank()) {
			reasons.add("tracks " + candidate.benchmarkIndex());
		}
		boolean canAssessDiversification = candidate.benchmarkIndex() != null
				|| (candidate.regions() != null && !candidate.regions().isEmpty())
				|| (candidate.holdings() != null && !candidate.holdings().isEmpty());
		if (hasExisting && canAssessDiversification && redundancy < 0.4) {
			reasons.add("diversifies existing holdings");
		}
		if (candidate.layer() != null && candidate.layer() <= 3 && isPreferredInstrument(candidate)) {
			reasons.add("suited to core allocation style");
		}
		if (reasons.isEmpty()) {
			return "";
		}
		return String.join("; ", reasons.subList(0, Math.min(2, reasons.size())));
	}

	private Map<String, InstrumentProfile> loadProfiles(Set<String> isins) {
		if (isins == null || isins.isEmpty()) {
			return Map.of();
		}
		String sql = """
				SELECT kbe.isin, kbe.status, kbe.extracted_json, ie.name AS instrument_name, ie.layer AS instrument_layer
				FROM knowledge_base_extractions kbe
				LEFT JOIN instruments_effective ie ON ie.isin = kbe.isin
				WHERE kbe.isin IN (:isins)
				""";
		MapSqlParameterSource params = new MapSqlParameterSource("isins", isins);
		Map<String, InstrumentProfile> profiles = new LinkedHashMap<>();
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			String isin = normalizeIsin(rs.getString("isin"));
			String status = rs.getString("status");
			if (isin == null || !isComplete(status)) {
				return;
			}
			String json = rs.getString("extracted_json");
			String name = rs.getString("instrument_name");
			Integer layer = rs.getObject("instrument_layer") == null ? null : rs.getInt("instrument_layer");
			InstrumentProfile profile = parseProfile(isin, json, name, layer);
			if (profile != null) {
				profiles.put(isin, profile);
			}
		});
		return profiles;
	}

	private Map<String, InstrumentProfile> loadCandidateProfiles() {
		String sql = """
				SELECT kbe.isin, kbe.status, kbe.extracted_json, ie.name AS instrument_name, ie.layer AS instrument_layer
				FROM knowledge_base_extractions kbe
				LEFT JOIN instruments_effective ie ON ie.isin = kbe.isin
				WHERE kbe.status IN (:statuses)
				""";
		MapSqlParameterSource params = new MapSqlParameterSource("statuses", COMPLETE_STATUSES);
		Map<String, InstrumentProfile> profiles = new LinkedHashMap<>();
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			String isin = normalizeIsin(rs.getString("isin"));
			if (isin == null) {
				return;
			}
			String json = rs.getString("extracted_json");
			String name = rs.getString("instrument_name");
			Integer layer = rs.getObject("instrument_layer") == null ? null : rs.getInt("instrument_layer");
			InstrumentProfile profile = parseProfile(isin, json, name, layer);
			if (profile != null) {
				profiles.put(isin, profile);
			}
		});
		return profiles;
	}

	private Map<String, InstrumentProfile> loadEffectiveProfiles(Set<String> isins, Set<String> knownIsins) {
		Set<String> missing = new HashSet<>(normalizeIsinSet(isins));
		if (knownIsins != null && !knownIsins.isEmpty()) {
			for (String isin : knownIsins) {
				String normalized = normalizeIsin(isin);
				if (normalized != null) {
					missing.remove(normalized);
				}
			}
		}
		if (missing.isEmpty()) {
			return Map.of();
		}
		String sql = """
				SELECT isin, name, layer, instrument_type, asset_class, sub_class, layer_notes
				FROM instruments_effective
				WHERE isin IN (:isins)
				""";
		MapSqlParameterSource params = new MapSqlParameterSource("isins", missing);
		Map<String, InstrumentProfile> profiles = new LinkedHashMap<>();
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			String isin = normalizeIsin(rs.getString("isin"));
			if (isin == null) {
				return;
			}
			Integer layer = rs.getObject("layer") == null ? null : rs.getInt("layer");
			if (layer == null || layer < 1 || layer > 5) {
				return;
			}
			String name = rs.getString("name");
			if (name == null || name.isBlank()) {
				name = isin;
			}
			String instrumentType = rs.getString("instrument_type");
			String assetClass = rs.getString("asset_class");
			String subClass = rs.getString("sub_class");
			String layerNotes = rs.getString("layer_notes");
			String distribution = inferDistribution(name, layerNotes);
			Set<String> themes = extractThemes(subClass, layerNotes);
			Set<String> locales = extractLocales(name, subClass, layerNotes, Set.of());
			boolean singleStock = isSingleStock(instrumentType, subClass, layerNotes);
			InstrumentProfile profile = new InstrumentProfile(isin, name, layer, instrumentType, assetClass, subClass,
					layerNotes, null, null, Set.of(), Set.of(), distribution, themes, locales, singleStock,
					null, null, null, null, null, null, null, null);
			profiles.put(isin, profile);
		});
		return profiles;
	}

	private InstrumentProfile parseProfile(String isin, String json, String fallbackName, Integer fallbackLayer) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			InstrumentDossierExtractionPayload payload = objectMapper.readValue(json, InstrumentDossierExtractionPayload.class);
			String name = trimToNull(payload.name());
			if (name == null) {
				name = trimToNull(fallbackName);
			}
			Integer layer = payload.layer();
			if (layer == null) {
				layer = fallbackLayer;
			}
			if (layer == null || layer < 1 || layer > 5) {
				return null;
			}
			String instrumentType = trimToNull(payload.instrumentType());
			String assetClass = trimToNull(payload.assetClass());
			String subClass = trimToNull(payload.subClass());
			String layerNotes = trimToNull(payload.layerNotes());
			BigDecimal ter = payload.etf() == null ? null : payload.etf().ongoingChargesPct();
			String benchmark = payload.etf() == null ? null : trimToNull(payload.etf().benchmarkIndex());
			Set<String> regions = normalizeRegionNames(payload.regions());
			Set<String> holdings = normalizeHoldingNames(payload.topHoldings());
			BigDecimal earningsYieldLongterm = extractLongtermEarningsYield(payload);
			BigDecimal earningsYieldCurrent = extractCurrentEarningsYield(payload);
			BigDecimal dividendYield = extractDividendYield(payload);
			BigDecimal priceToBook = extractPriceToBook(payload);
			BigDecimal evToEbitda = extractEvToEbitda(payload);
			BigDecimal ebitdaEur = extractEbitdaEur(payload);
			BigDecimal netIncomeEur = extractNetIncomeEur(payload);
			BigDecimal revenueEur = extractRevenueEur(payload);
			String distribution = inferDistribution(name, layerNotes);
			Set<String> themes = extractThemes(subClass, layerNotes);
			Set<String> locales = extractLocales(name, subClass, layerNotes, regions);
			boolean singleStock = isSingleStock(instrumentType, subClass, layerNotes);
			return new InstrumentProfile(isin, name, layer, instrumentType, assetClass, subClass, layerNotes, ter,
					benchmark, regions, holdings, distribution, themes, locales, singleStock,
					earningsYieldLongterm, earningsYieldCurrent, dividendYield, priceToBook, evToEbitda,
					ebitdaEur, netIncomeEur, revenueEur);
		} catch (Exception ex) {
			logger.debug("Failed to parse KB extraction payload for {}: {}", isin, ex.getMessage());
			return null;
		}
	}

	private BigDecimal extractLongtermEarningsYield(InstrumentDossierExtractionPayload payload) {
		if (payload == null || payload.valuation() == null) {
			return null;
		}
		InstrumentDossierExtractionPayload.ValuationPayload valuation = payload.valuation();
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

	private BigDecimal extractCurrentEarningsYield(InstrumentDossierExtractionPayload payload) {
		if (payload == null || payload.valuation() == null) {
			return null;
		}
		InstrumentDossierExtractionPayload.ValuationPayload valuation = payload.valuation();
		BigDecimal yield = valuation.earningsYieldTtmHoldings();
		if (yield != null) {
			return yield;
		}
		BigDecimal peHoldings = valuation.peTtmHoldings();
		if (peHoldings != null && peHoldings.compareTo(BigDecimal.ZERO) > 0) {
			return BigDecimal.ONE.divide(peHoldings, 8, RoundingMode.HALF_UP);
		}
		BigDecimal peCurrent = valuation.peCurrent();
		if (peCurrent != null && peCurrent.compareTo(BigDecimal.ZERO) > 0) {
			return BigDecimal.ONE.divide(peCurrent, 8, RoundingMode.HALF_UP);
		}
		return null;
	}

	private BigDecimal extractPriceToBook(InstrumentDossierExtractionPayload payload) {
		if (payload == null || payload.valuation() == null) {
			return null;
		}
		return payload.valuation().pbCurrent();
	}

	private BigDecimal computeLongtermEarningsYield(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (valuation == null) {
			return null;
		}
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
		List<InstrumentDossierExtractionPayload.EpsHistoryPayload> history = valuation.epsHistory();
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
				? DEFAULT_EPS_FLOOR
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

	private BigDecimal extractEvToEbitda(InstrumentDossierExtractionPayload payload) {
		if (payload == null || payload.valuation() == null) {
			return null;
		}
		InstrumentDossierExtractionPayload.ValuationPayload valuation = payload.valuation();
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

	private BigDecimal extractEbitdaEur(InstrumentDossierExtractionPayload payload) {
		if (payload == null || payload.valuation() == null) {
			return null;
		}
		return extractProfitabilityEur(payload.valuation());
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

	private Map<Integer, LayerCoverage> buildCoverage(java.util.Collection<InstrumentProfile> profiles) {
		Map<Integer, LayerCoverage> coverage = new HashMap<>();
		if (profiles == null) {
			return coverage;
		}
		for (InstrumentProfile profile : profiles) {
			if (profile == null || profile.layer() == null) {
				continue;
			}
			coverage.computeIfAbsent(profile.layer(), key -> new LayerCoverage()).add(profile);
		}
		return coverage;
	}

	private Map<Integer, LayerCoverage> buildCoverageByLayer(Map<Integer, List<InstrumentProfile>> profiles) {
		Map<Integer, LayerCoverage> coverage = new HashMap<>();
		if (profiles == null) {
			return coverage;
		}
		for (Map.Entry<Integer, List<InstrumentProfile>> entry : profiles.entrySet()) {
			LayerCoverage layerCoverage = new LayerCoverage();
			for (InstrumentProfile profile : entry.getValue()) {
				layerCoverage.add(profile);
			}
			coverage.put(entry.getKey(), layerCoverage);
		}
		return coverage;
	}

	private Map<Integer, List<InstrumentProfile>> groupByLayer(java.util.Collection<InstrumentProfile> profiles) {
		Map<Integer, List<InstrumentProfile>> grouped = new HashMap<>();
		if (profiles == null) {
			return grouped;
		}
		for (InstrumentProfile profile : profiles) {
			if (profile == null || profile.layer() == null) {
				continue;
			}
			grouped.computeIfAbsent(profile.layer(), key -> new ArrayList<>()).add(profile);
		}
		return grouped;
	}

	private Set<String> normalizeRegionNames(List<InstrumentDossierExtractionPayload.RegionExposurePayload> regions) {
		if (regions == null || regions.isEmpty()) {
			return Set.of();
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (InstrumentDossierExtractionPayload.RegionExposurePayload region : regions) {
			String name = normalizeLabel(region == null ? null : region.name());
			if (name != null) {
				normalized.add(name);
			}
		}
		return normalized;
	}

	private Set<String> normalizeHoldingNames(List<InstrumentDossierExtractionPayload.HoldingPayload> holdings) {
		if (holdings == null || holdings.isEmpty()) {
			return Set.of();
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (InstrumentDossierExtractionPayload.HoldingPayload holding : holdings) {
			String name = normalizeLabel(holding == null ? null : holding.name());
			if (name != null) {
				normalized.add(name);
			}
		}
		return normalized;
	}

	private boolean isComplete(String status) {
		if (status == null) {
			return false;
		}
		return COMPLETE_STATUSES.contains(status.toUpperCase(Locale.ROOT));
	}

	private String inferDistribution(String name, String layerNotes) {
		String content = ((name == null ? "" : name) + " " + (layerNotes == null ? "" : layerNotes)).trim();
		if (content.isBlank()) {
			return null;
		}
		if (ACC_PATTERN.matcher(content).find()) {
			return "accumulating";
		}
		if (DIST_PATTERN.matcher(content).find()) {
			return "distributing";
		}
		return null;
	}

	private Set<String> extractThemes(String subClass, String layerNotes) {
		Set<String> themes = new LinkedHashSet<>();
		String normalizedSub = normalizeLabel(subClass);
		if (normalizedSub != null) {
			themes.add(normalizedSub);
		}
		String normalizedNotes = normalizeLabel(layerNotes);
		if (normalizedNotes == null) {
			return themes;
		}
		for (String keyword : THEME_KEYWORDS) {
			if (normalizedNotes.contains(keyword)) {
				themes.add(keyword);
			}
		}
		return themes;
	}

	private Set<String> extractLocales(String name, String subClass, String layerNotes, Set<String> regions) {
		Set<String> locales = new LinkedHashSet<>();
		if (regions != null) {
			locales.addAll(regions);
		}
		String combined = normalizeLabel((name == null ? "" : name) + " " + (subClass == null ? "" : subClass)
				+ " " + (layerNotes == null ? "" : layerNotes));
		if (combined == null) {
			return locales;
		}
		for (LocaleKeyword keyword : LOCALE_KEYWORDS) {
			if (combined.contains(keyword.keyword())) {
				locales.add(keyword.label());
			}
		}
		return locales;
	}

	private boolean isSingleStock(String instrumentType, String subClass, String layerNotes) {
		String type = normalizeLabel(instrumentType);
		String notes = normalizeLabel(layerNotes);
		String sub = normalizeLabel(subClass);
		if (notes != null && (notes.contains("single stock") || notes.contains("single-company") || notes.contains("single issuer"))) {
			return true;
		}
		if (sub != null && sub.contains("single-stock")) {
			return true;
		}
		return type != null && (type.contains("share") || type.contains("equity")) && (type.contains("etf") == false);
	}

	private static String normalizeIsin(String isin) {
		if (isin == null || isin.isBlank()) {
			return null;
		}
		return isin.trim().toUpperCase(Locale.ROOT);
	}

	private static Set<String> normalizeIsinSet(Set<String> isins) {
		if (isins == null || isins.isEmpty()) {
			return Set.of();
		}
		Set<String> normalized = new HashSet<>();
		for (String isin : isins) {
			String value = normalizeIsin(isin);
			if (value != null) {
				normalized.add(value);
			}
		}
		return Set.copyOf(normalized);
	}

	private static String normalizeLabel(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		return raw.trim().toLowerCase(Locale.ROOT);
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	public record SuggestionRequest(List<AssessorEngine.SavingPlanItem> savingPlans,
									Set<String> existingInstrumentIsins,
									Map<Integer, BigDecimal> savingPlanBudgets,
									Map<Integer, BigDecimal> oneTimeBudgets,
									int minimumSavingPlanSize,
									int minimumRebalancingAmount,
									int minimumInstrumentAmount,
									Map<Integer, Integer> maxSavingPlansPerLayer,
									Set<String> excludedSnapshotIsins,
									AssessorGapDetectionPolicy gapDetectionPolicy) {
	}

	record SavingPlanWeightRequest(List<AssessorEngine.SavingPlanItem> savingPlans,
								   Set<String> existingInstrumentIsins,
								   AssessorGapDetectionPolicy gapDetectionPolicy) {
	}

	public record SuggestionResult(List<NewInstrumentSuggestion> savingPlanSuggestions,
								   List<NewInstrumentSuggestion> oneTimeSuggestions) {
		public static SuggestionResult empty() {
			return new SuggestionResult(List.of(), List.of());
		}
	}

	public record NewInstrumentSuggestion(String isin,
										  String name,
										  Integer layer,
										  BigDecimal amount,
										  String rationale) {
	}

	private record SelectedSuggestion(InstrumentProfile profile, String rationale) {
	}

	private record InstrumentProfile(String isin,
									 String name,
									 Integer layer,
									 String instrumentType,
									 String assetClass,
									 String subClass,
									 String layerNotes,
									 BigDecimal ongoingChargesPct,
									 String benchmarkIndex,
									 Set<String> regions,
									 Set<String> holdings,
									 String distribution,
	 Set<String> themes,
	 Set<String> locales,
	 boolean singleStock,
	 BigDecimal earningsYieldLongterm,
	 BigDecimal earningsYieldCurrent,
	 BigDecimal dividendYield,
	 BigDecimal priceToBook,
	 BigDecimal evToEbitda,
	 BigDecimal ebitdaEur,
	 BigDecimal netIncomeEur,
	 BigDecimal revenueEur) {
		boolean matchesGap(CoverageGap gap) {
			if (gap == null || gap.value() == null) {
				return false;
			}
			String value = gap.value();
			return switch (gap.type()) {
				case SUB_CLASS -> subClass != null && subClass.equalsIgnoreCase(value);
				case THEME -> themes != null && themes.contains(value);
				case LOCALISATION -> locales != null && locales.contains(value);
				case DISTRIBUTION -> distribution != null && distribution.equalsIgnoreCase(value);
			};
		}
	}

	private enum GapType {
		SUB_CLASS,
		THEME,
		LOCALISATION,
		DISTRIBUTION
	}

	private record CoverageGap(GapType type, String value) {
	}

	private record MissingCategories(Set<String> subClasses,
									 Set<String> themes,
									 Set<String> locales,
									 Set<String> distributions) {
		static MissingCategories from(LayerCoverage existing, LayerCoverage candidates) {
			Set<String> missingSub = diff(candidates.subClasses, existing.subClasses);
			Set<String> missingThemes = diff(candidates.themes, existing.themes);
			Set<String> missingLocales = diff(candidates.locales, existing.locales);
			Set<String> missingDist = diff(candidates.distributions, existing.distributions);
			return new MissingCategories(missingSub, missingThemes, missingLocales, missingDist);
		}

		static MissingCategories empty() {
			return new MissingCategories(Set.of(), Set.of(), Set.of(), Set.of());
		}

		boolean isEmpty() {
			return subClasses.isEmpty() && themes.isEmpty() && locales.isEmpty() && distributions.isEmpty();
		}

		List<CoverageGap> toGapList() {
			List<CoverageGap> gaps = new ArrayList<>();
			subClasses.forEach(value -> gaps.add(new CoverageGap(GapType.SUB_CLASS, value)));
			themes.forEach(value -> gaps.add(new CoverageGap(GapType.THEME, value)));
			locales.forEach(value -> gaps.add(new CoverageGap(GapType.LOCALISATION, value)));
			distributions.forEach(value -> gaps.add(new CoverageGap(GapType.DISTRIBUTION, value)));
			return gaps;
		}

		int coverageCount(InstrumentProfile profile) {
			if (profile == null) {
				return 0;
			}
			int count = 0;
			if (profile.subClass() != null && subClasses.contains(profile.subClass().toLowerCase(Locale.ROOT))) {
				count += 1;
			}
			if (profile.themes() != null) {
				for (String theme : profile.themes()) {
					if (themes.contains(theme)) {
						count += 1;
						break;
					}
				}
			}
			if (profile.locales() != null) {
				for (String locale : profile.locales()) {
					if (locales.contains(locale)) {
						count += 1;
						break;
					}
				}
			}
			if (profile.distribution() != null && distributions.contains(profile.distribution().toLowerCase(Locale.ROOT))) {
				count += 1;
			}
			return count;
		}

		List<CoverageGap> coveredBy(InstrumentProfile profile) {
			List<CoverageGap> covered = new ArrayList<>();
			if (profile == null) {
				return covered;
			}
			String sub = normalize(profile.subClass());
			if (sub != null && subClasses.contains(sub)) {
				covered.add(new CoverageGap(GapType.SUB_CLASS, sub));
			}
			if (profile.themes() != null) {
				for (String theme : profile.themes()) {
					if (themes.contains(theme)) {
						covered.add(new CoverageGap(GapType.THEME, theme));
					}
				}
			}
			if (profile.locales() != null) {
				for (String locale : profile.locales()) {
					if (locales.contains(locale)) {
						covered.add(new CoverageGap(GapType.LOCALISATION, locale));
					}
				}
			}
			String dist = normalize(profile.distribution());
			if (dist != null && distributions.contains(dist)) {
				covered.add(new CoverageGap(GapType.DISTRIBUTION, dist));
			}
			return covered;
		}

		private static Set<String> diff(Set<String> candidates, Set<String> existing) {
			if (candidates == null || candidates.isEmpty()) {
				return Set.of();
			}
			Set<String> missing = new LinkedHashSet<>();
			for (String value : candidates) {
				if (value == null || value.isBlank()) {
					continue;
				}
				if (existing == null || !existing.contains(value)) {
					missing.add(value);
				}
			}
			return missing;
		}

		private static String normalize(String value) {
			if (value == null) {
				return null;
			}
			return value.trim().toLowerCase(Locale.ROOT);
		}
	}

	private static class LayerCoverage {
		private final Set<String> subClasses = new LinkedHashSet<>();
		private final Set<String> themes = new LinkedHashSet<>();
		private final Set<String> locales = new LinkedHashSet<>();
		private final Set<String> distributions = new LinkedHashSet<>();

		void add(InstrumentProfile profile) {
			if (profile == null) {
				return;
			}
			String sub = normalizeLabel(profile.subClass());
			if (sub != null) {
				subClasses.add(sub);
			}
			if (profile.themes() != null) {
				for (String theme : profile.themes()) {
					if (theme != null && !theme.isBlank()) {
						themes.add(theme);
					}
				}
			}
			if (profile.locales() != null) {
				for (String locale : profile.locales()) {
					if (locale != null && !locale.isBlank()) {
						locales.add(locale);
					}
				}
			}
			String dist = normalizeLabel(profile.distribution());
			if (dist != null) {
				distributions.add(dist);
			}
		}
	}

	private record LocaleKeyword(String keyword, String label) {
	}
}
