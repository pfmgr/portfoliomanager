package my.portfoliomanager.app.service;

import tools.jackson.databind.JsonNode;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class KnowledgeBaseQualityGateService {
	private static final List<SectionRequirement> REQUIRED_SECTIONS = List.of(
			new SectionRequirement("quick_profile", Pattern.compile(
					"(?im)^\\s*(?:#{1,6}\\s*|[-*+]\\s+)?\\**\\s*quick\\s*profile\\b")),
			new SectionRequirement("classification", Pattern.compile(
					"(?im)^\\s*(?:#{1,6}\\s*|[-*+]\\s+)?\\**\\s*(classification|layer\\s*notes?)\\b")),
			new SectionRequirement("risk", Pattern.compile(
					"(?im)^\\s*(?:#{1,6}\\s*|[-*+]\\s+)?\\**\\s*(risk|risiko|sri|summary\\s*risk)\\b")),
			new SectionRequirement("costs_structure", Pattern.compile(
					"(?im)^\\s*(?:#{1,6}\\s*|[-*+]\\s+)?\\**\\s*" +
							"(costs\\s*&\\s*structure|costs\\s*and\\s*structure|costs\\s*structure|" +
							"fees\\s*&\\s*structure|fees\\s*and\\s*structure|fees\\s*structure)\\b")),
			new SectionRequirement("exposures", Pattern.compile(
					"(?im)^\\s*(?:#{1,6}\\s*|[-*+]\\s+)?\\**\\s*(exposures?|holdings|top\\s*holdings|portfolio)\\b")),
			new SectionRequirement("valuation_profitability", Pattern.compile(
					"(?im)^\\s*(?:#{1,6}\\s*|[-*+]\\s+)?\\**\\s*" +
							"(valuation\\s*&\\s*profitability|valuation\\s*and\\s*profitability|valuation|profitability)\\b")),
			new SectionRequirement("sources", Pattern.compile(
					"(?im)^\\s*(?:#{1,6}\\s*|[-*+]\\s+)?\\**\\s*(sources?|references?|quellen|citations?|bibliography)\\b"))
	);
	private static final List<String> SECONDARY_DOMAINS = List.of(
			"justetf.com",
			"etf.com",
			"etfdb.com",
			"etfchannel.com",
			"morningstar.com",
			"seekingalpha.com",
			"stockanalysis.com"
	);
	private static final List<String> PRIMARY_HINTS = List.of(
			"factsheet",
			"kid",
			"kiid",
			"priips",
			"prospectus",
			"issuer",
			"exchange",
			"regulator"
	);
	private static final String PROFILE_FUND = "FUND";
	private static final String PROFILE_EQUITY = "EQUITY";
	private static final String PROFILE_REIT = "REIT";
	private static final String PROFILE_UNKNOWN = "UNKNOWN";
	private static final List<String> DEFAULT_FUND_EVIDENCE_KEYS = List.of(
			"benchmark_index",
			"ongoing_charges_pct",
			"sri",
			"price",
			"pe_current",
			"pb_current",
			"pe_ttm_holdings",
			"earnings_yield_ttm_holdings",
			"holdings_coverage_weight_pct",
			"holdings_coverage_count",
			"holdings_asof"
	);
	private static final List<String> DEFAULT_EQUITY_EVIDENCE_KEYS = List.of(
			"price",
			"pe_current",
			"pb_current",
			"dividend_per_share",
			"revenue",
			"net_income",
			"ebitda",
			"eps_history"
	);
	private static final List<String> DEFAULT_REIT_EVIDENCE_KEYS = List.of(
			"price",
			"pe_current",
			"pb_current",
			"dividend_per_share",
			"revenue",
			"net_income",
			"ebitda",
			"eps_history",
			"net_rent",
			"noi",
			"affo",
			"ffo"
	);
	private static final List<String> DEFAULT_UNKNOWN_EVIDENCE_KEYS = List.of(
			"price",
			"pe_current",
			"pb_current"
	);

	public DossierQualityResult evaluateDossier(String isin,
								String contentMd,
								JsonNode citations,
								KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config) {
		List<String> reasons = new ArrayList<>();
		if (contentMd == null || contentMd.isBlank()) {
			reasons.add("empty_content");
			return new DossierQualityResult(false, reasons);
		}
		String trimmed = normalizeSectionWhitespace(contentMd).trim();
		int maxChars = config == null ? 0 : config.dossierMaxChars();
		if (maxChars > 0 && trimmed.length() > maxChars) {
			reasons.add("content_exceeds_max_chars");
		}
		if (isin != null && !isin.isBlank()) {
			if (!hasIsinHeader(trimmed, isin)) {
				reasons.add("missing_isin_header");
			}
		}
		for (SectionRequirement section : REQUIRED_SECTIONS) {
			if (!section.pattern().matcher(trimmed).find()) {
				reasons.add("missing_section:" + section.code());
			}
		}
		List<CitationInfo> parsed = parseCitations(citations);
		int minCitations = config == null ? 0 : config.bulkMinCitations();
		if (parsed.size() < Math.max(1, minCitations)) {
			reasons.add("insufficient_citations");
		}
		boolean fundLike = isFundLikeContent(trimmed);
		boolean requirePrimary = (config == null || config.bulkRequirePrimarySource()) && fundLike;
		if (requirePrimary && !hasPrimarySource(parsed)) {
			reasons.add("missing_primary_source");
		}
		return new DossierQualityResult(reasons.isEmpty(), reasons);
	}

	public boolean isRetryableDossierFailure(List<String> reasons) {
		if (reasons == null || reasons.isEmpty()) {
			return false;
		}
		for (String reason : reasons) {
			if (reason == null) {
				continue;
			}
			if (reason.startsWith("missing_section:")) {
				return true;
			}
			switch (reason) {
				case "missing_isin_header", "insufficient_citations", "missing_primary_source", "empty_content" -> {
					return true;
				}
				default -> {
				}
			}
		}
		return false;
	}

	public EvidenceResult evaluateExtractionEvidence(String dossierContent,
									InstrumentDossierExtractionPayload payload,
									KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config) {
		if (config != null && !config.extractionEvidenceRequired()) {
			return new EvidenceResult(true, List.of());
		}
		if (dossierContent == null || dossierContent.isBlank() || payload == null) {
			return new EvidenceResult(false, List.of("missing_content_or_payload"));
		}
		String normalizedContent = normalizeText(dossierContent);
		List<String> missingEvidence = new ArrayList<>();

		InstrumentCategory category = resolveInstrumentCategory(dossierContent, payload);
		Integer layer = resolveLayer(payload, dossierContent);
		List<String> evidenceKeys = resolveEvidenceKeys(config, category, layer);
		applyEvidenceChecks(evidenceKeys, dossierContent, normalizedContent, payload, missingEvidence);

		return new EvidenceResult(missingEvidence.isEmpty(), List.copyOf(missingEvidence));
	}

	public SimilarityResult evaluateSimilarity(InstrumentDossierExtractionPayload base,
									 InstrumentDossierExtractionPayload alternative,
									 double threshold) {
		if (base == null || alternative == null) {
			return new SimilarityResult(false, 0.0, List.of("missing_payload"));
		}
		double totalWeight = 0.0;
		double score = 0.0;
		List<String> reasons = new ArrayList<>();

		double assetWeight = 0.4;
		String baseAsset = normalizeText(base.assetClass());
		String altAsset = normalizeText(alternative.assetClass());
		if (!baseAsset.isBlank() && !altAsset.isBlank()) {
			totalWeight += assetWeight;
			if (baseAsset.equals(altAsset)) {
				score += assetWeight;
			} else {
				reasons.add("asset_class_mismatch");
			}
		}

		double subWeight = 0.2;
		String baseSub = normalizeText(base.subClass());
		String altSub = normalizeText(alternative.subClass());
		if (!baseSub.isBlank() && !altSub.isBlank()) {
			totalWeight += subWeight;
			if (baseSub.equals(altSub)) {
				score += subWeight;
			} else {
				reasons.add("sub_class_mismatch");
			}
		}

		double typeWeight = 0.2;
		boolean baseFund = isFundLike(base);
		boolean altFund = isFundLike(alternative);
		if (baseFund || altFund) {
			totalWeight += typeWeight;
			if (baseFund == altFund) {
				score += typeWeight;
			} else {
				reasons.add("instrument_type_mismatch");
			}
		}

		double regionWeight = 0.2;
		Set<String> baseRegions = normalizeRegions(base.regions());
		Set<String> altRegions = normalizeRegions(alternative.regions());
		if (!baseRegions.isEmpty() && !altRegions.isEmpty()) {
			totalWeight += regionWeight;
			double overlap = jaccard(baseRegions, altRegions);
			score += regionWeight * overlap;
			if (overlap < 0.4) {
				reasons.add("region_overlap_low");
			}
		}

		double normalizedScore = totalWeight > 0 ? score / totalWeight : 0.0;
		if (totalWeight == 0) {
			reasons.add("insufficient_similarity_data");
		}
		boolean passed = normalizedScore >= threshold;
		if (!passed) {
			reasons.add("similarity_below_threshold");
		}
		return new SimilarityResult(passed, normalizedScore, reasons);
	}

	private void checkTextEvidence(String normalizedContent, String field, String value, List<String> missing) {
		if (value == null || value.isBlank()) {
			return;
		}
		String normalizedValue = normalizeText(value);
		if (!normalizedValue.isBlank() && normalizedContent.contains(normalizedValue)) {
			return;
		}
		missing.add(field);
	}

	private void checkSriEvidence(String content, Integer sri, List<String> missing) {
		if (sri == null) {
			return;
		}
		String lower = content.toLowerCase(Locale.ROOT);
		String token = sri.toString();
		boolean has = (lower.contains("sri") || lower.contains("summary risk")) && lower.contains(token);
		if (!has) {
			missing.add("sri");
		}
	}

	private void checkNumericEvidence(String content,
							String field,
							BigDecimal value,
							List<String> labels,
							boolean allowPercent,
							List<String> missing) {
		if (value == null) {
			return;
		}
		String lower = content.toLowerCase(Locale.ROOT);
		List<String> tokens = buildNumericTokens(value, allowPercent);
		boolean labelFound = false;
		for (String label : labels) {
			if (!lower.contains(label)) {
				continue;
			}
			labelFound = true;
			for (String token : tokens) {
				if (lower.contains(token)) {
					return;
				}
			}
		}
		if (labelFound && hasScaledNumberEvidence(content, labels)) {
			return;
		}
		missing.add(field);
	}

	private boolean hasScaledNumberEvidence(String content, List<String> labels) {
		if (content == null || content.isBlank() || labels == null || labels.isEmpty()) {
			return false;
		}
		Pattern scaledPattern = Pattern.compile("(?i)([-+]?[0-9]+(?:[.,][0-9]+)?)\\s*(b|bn|billion|m|mn|million|k|thousand)\\b");
		for (String line : content.split("\\R")) {
			String lower = line.toLowerCase(Locale.ROOT);
			boolean labelFound = false;
			for (String label : labels) {
				if (lower.contains(label)) {
					labelFound = true;
					break;
				}
			}
			if (!labelFound) {
				continue;
			}
			if (scaledPattern.matcher(line).find()) {
				return true;
			}
		}
		return false;
	}

	private void checkDateEvidence(String content,
								String field,
								String value,
								List<String> labels,
								List<String> missing) {
		if (value == null || value.isBlank()) {
			return;
		}
		String lower = content.toLowerCase(Locale.ROOT);
		List<String> tokens = buildDateTokens(value);
		for (String label : labels) {
			if (!lower.contains(label)) {
				continue;
			}
			for (String token : tokens) {
				if (lower.contains(token)) {
					return;
				}
			}
		}
		missing.add(field);
	}

	private Integer resolveLayer(InstrumentDossierExtractionPayload payload, String dossierContent) {
		Integer layer = payload == null ? null : payload.layer();
		if (layer != null) {
			return layer;
		}
		return extractLayerValue(dossierContent);
	}

	private List<String> resolveEvidenceKeys(KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config,
											InstrumentCategory category,
											Integer layer) {
		KnowledgeBaseConfigService.KnowledgeBaseQualityGateConfigSnapshot gateConfig =
				config == null ? null : config.qualityGateProfiles();
		KnowledgeBaseConfigService.KnowledgeBaseQualityGateProfileSnapshot profile =
				gateConfig == null ? null : gateConfig.activeProfileSnapshot();
		String profileKey = null;
		if (category == InstrumentCategory.REIT
				&& profile != null
				&& profile.evidenceProfiles() != null
				&& profile.evidenceProfiles().containsKey(PROFILE_REIT)) {
			profileKey = PROFILE_REIT;
		}
		if (profileKey == null && profile != null && layer != null && profile.layerProfiles() != null) {
			profileKey = normalizeProfileKey(profile.layerProfiles().get(layer));
		}
		if (profileKey == null) {
			profileKey = resolveProfileKey(category);
		}
		List<String> evidenceKeys = null;
		if (profile != null && profileKey != null && profile.evidenceProfiles() != null) {
			evidenceKeys = profile.evidenceProfiles().get(profileKey);
		}
		if (evidenceKeys == null || evidenceKeys.isEmpty()) {
			return defaultEvidenceKeysForCategory(category);
		}
		return normalizeEvidenceKeys(evidenceKeys);
	}

	private String resolveProfileKey(InstrumentCategory category) {
		return switch (category) {
			case FUND -> PROFILE_FUND;
			case REIT -> PROFILE_REIT;
			case EQUITY -> PROFILE_EQUITY;
			case UNKNOWN -> PROFILE_UNKNOWN;
		};
	}

	private List<String> defaultEvidenceKeysForCategory(InstrumentCategory category) {
		return switch (category) {
			case FUND -> DEFAULT_FUND_EVIDENCE_KEYS;
			case REIT -> DEFAULT_REIT_EVIDENCE_KEYS;
			case EQUITY -> DEFAULT_EQUITY_EVIDENCE_KEYS;
			case UNKNOWN -> DEFAULT_UNKNOWN_EVIDENCE_KEYS;
		};
	}

	private void applyEvidenceChecks(List<String> evidenceKeys,
									String dossierContent,
									String normalizedContent,
									InstrumentDossierExtractionPayload payload,
									List<String> missingEvidence) {
		if (evidenceKeys == null || evidenceKeys.isEmpty() || payload == null) {
			return;
		}
		InstrumentDossierExtractionPayload.EtfPayload etf = payload.etf();
		InstrumentDossierExtractionPayload.FinancialsPayload financials = payload.financials();
		InstrumentDossierExtractionPayload.ValuationPayload valuation = payload.valuation();
		Integer sri = payload.risk() == null || payload.risk().summaryRiskIndicator() == null
				? null
				: payload.risk().summaryRiskIndicator().value();
		for (String key : normalizeEvidenceKeys(evidenceKeys)) {
			switch (key) {
				case "benchmark_index" -> checkTextEvidence(normalizedContent, "benchmark_index",
							etf == null ? null : etf.benchmarkIndex(), missingEvidence);
				case "ongoing_charges_pct" -> checkNumericEvidence(dossierContent, "ongoing_charges_pct",
							etf == null ? null : etf.ongoingChargesPct(),
							List.of("ter", "ongoing charges", "ongoing charge", "total expense ratio",
									"ongoing_charges_pct", "ongoing charges pct"),
							true, missingEvidence);
				case "sri" -> checkSriEvidence(dossierContent, sri, missingEvidence);
				case "price" -> checkNumericEvidence(dossierContent, "price",
							valuation == null ? null : valuation.price(),
							List.of("price", "nav", "market price"), false, missingEvidence);
				case "pe_current" -> checkNumericEvidence(dossierContent, "pe_current",
							valuation == null ? null : valuation.peCurrent(),
							List.of("pe_current", "p/e", "pe", "price/earnings"), false, missingEvidence);
				case "pb_current" -> checkNumericEvidence(dossierContent, "pb_current",
							valuation == null ? null : valuation.pbCurrent(),
							List.of("pb_current", "p/b", "pb", "price/book", "price to book"), false, missingEvidence);
				case "market_cap" -> checkNumericEvidence(dossierContent, "market_cap",
							valuation == null ? null : valuation.marketCap(),
							List.of("market cap", "market capitalization"), false, missingEvidence);
				case "pe_ttm_holdings" -> checkNumericEvidence(dossierContent, "pe_ttm_holdings",
							valuation == null ? null : valuation.peTtmHoldings(),
							List.of("pe_ttm_holdings", "holdings p/e", "holdings pe"), false, missingEvidence);
				case "earnings_yield_ttm_holdings" -> checkNumericEvidence(dossierContent, "earnings_yield_ttm_holdings",
							valuation == null ? null : valuation.earningsYieldTtmHoldings(),
							List.of("earnings_yield_ttm_holdings", "holdings earnings yield", "earnings yield holdings"),
							false, missingEvidence);
				case "holdings_coverage_weight_pct" -> checkNumericEvidence(dossierContent, "holdings_coverage_weight_pct",
							valuation == null ? null : valuation.holdingsCoverageWeightPct(),
							List.of("holdings_coverage_weight_pct", "holdings coverage weight"), true, missingEvidence);
				case "holdings_coverage_count" -> checkNumericEvidence(dossierContent, "holdings_coverage_count",
							valuation == null || valuation.holdingsCoverageCount() == null
									? null
									: BigDecimal.valueOf(valuation.holdingsCoverageCount()),
							List.of("holdings_coverage_count", "holdings coverage count"), false, missingEvidence);
				case "holdings_asof" -> checkDateEvidence(dossierContent, "holdings_asof",
							valuation == null ? null : valuation.holdingsAsOf(),
							List.of("holdings_asof", "holdings asof", "holdings as of"), missingEvidence);
				case "dividend_per_share" -> checkNumericEvidence(dossierContent, "dividend_per_share",
							financials == null ? null : financials.dividendPerShare(),
							List.of("dividend_per_share", "dividend per share"), false, missingEvidence);
				case "revenue" -> checkNumericEvidence(dossierContent, "revenue",
							financials == null ? null : financials.revenue(),
							List.of("revenue"), false, missingEvidence);
				case "net_income" -> checkNumericEvidence(dossierContent, "net_income",
							financials == null ? null : financials.netIncome(),
							List.of("net income", "net_income"), false, missingEvidence);
				case "ebitda" -> checkNumericEvidence(dossierContent, "ebitda",
							valuation == null ? null : valuation.ebitda(),
							List.of("ebitda"), false, missingEvidence);
				case "eps_history" -> checkEpsHistoryEvidence(dossierContent,
							valuation == null ? null : valuation.epsHistory(), missingEvidence);
				case "net_rent" -> checkNumericEvidence(dossierContent, "net_rent",
							valuation == null ? null : valuation.netRent(),
							List.of("net_rent", "net rent"), false, missingEvidence);
				case "noi" -> checkNumericEvidence(dossierContent, "noi",
							valuation == null ? null : valuation.noi(),
							List.of("noi"), false, missingEvidence);
				case "affo" -> checkNumericEvidence(dossierContent, "affo",
							valuation == null ? null : valuation.affo(),
							List.of("affo"), false, missingEvidence);
				case "ffo" -> checkNumericEvidence(dossierContent, "ffo",
							valuation == null ? null : valuation.ffo(),
							List.of("ffo"), false, missingEvidence);
				default -> {
				}
			}
		}
		if (valuation != null && valuation.peCurrent() != null
				&& missingEvidence.contains("pe_current")
				&& canDerivePeCurrent(valuation)) {
			missingEvidence.remove("pe_current");
		}
	}

	private String normalizeProfileKey(String key) {
		if (key == null || key.isBlank()) {
			return null;
		}
		return key.trim().toUpperCase(Locale.ROOT);
	}

	private List<String> normalizeEvidenceKeys(List<String> raw) {
		if (raw == null || raw.isEmpty()) {
			return List.of();
		}
		List<String> normalized = new ArrayList<>();
		for (String key : raw) {
			if (key == null || key.isBlank()) {
				continue;
			}
			String cleaned = key.trim().toLowerCase(Locale.ROOT);
			if (!normalized.contains(cleaned)) {
				normalized.add(cleaned);
			}
		}
		return normalized;
	}

	private void checkEpsHistoryEvidence(String content,
								List<InstrumentDossierExtractionPayload.EpsHistoryPayload> epsHistory,
								List<String> missingEvidence) {
		if (epsHistory == null || epsHistory.isEmpty()) {
			return;
		}
		String lower = content.toLowerCase(Locale.ROOT);
		boolean hasLabel = lower.contains("eps_history") || lower.contains("eps history");
		if (!hasLabel) {
			missingEvidence.add("eps_history");
			return;
		}
		for (InstrumentDossierExtractionPayload.EpsHistoryPayload entry : epsHistory) {
			if (entry == null || entry.year() == null) {
				continue;
			}
			String year = entry.year().toString();
			if (lower.contains(year)) {
				return;
			}
		}
		missingEvidence.add("eps_history");
	}

	private List<String> buildDateTokens(String value) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.isEmpty()) {
			return List.of();
		}
		List<String> tokens = new ArrayList<>();
		tokens.add(trimmed.toLowerCase(Locale.ROOT));
		String normalized = trimmed.replace('/', '-').replace('.', '-');
		if (!normalized.equals(trimmed)) {
			tokens.add(normalized.toLowerCase(Locale.ROOT));
		}
		if (normalized.contains("-")) {
			tokens.add(normalized.replace('-', ' ').toLowerCase(Locale.ROOT));
		}
		return tokens;
	}

	private List<String> buildNumericTokens(BigDecimal value, boolean allowPercent) {
		if (value == null) {
			return List.of();
		}
		java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
		addNumericTokens(tokens, value);
		addScaledTokens(tokens, value, new BigDecimal("1000000000"), List.of("b", "bn", "billion"));
		addScaledTokens(tokens, value, new BigDecimal("1000000"), List.of("m", "mn", "million"));
		addScaledTokens(tokens, value, new BigDecimal("1000"), List.of("k", "thousand"));
		if (allowPercent) {
			List<String> base = new ArrayList<>(tokens);
			for (String token : base) {
				tokens.add(token + "%");
				tokens.add(token + " %");
			}
		}
		return new ArrayList<>(tokens);
	}

	private void addNumericTokens(java.util.LinkedHashSet<String> tokens, BigDecimal value) {
		String raw = value.toPlainString();
		String plain = value.stripTrailingZeros().toPlainString();
		addToken(tokens, raw);
		addToken(tokens, plain);
		if (raw.contains(".")) {
			addToken(tokens, raw.replace('.', ','));
		}
		if (plain.contains(".")) {
			addToken(tokens, plain.replace('.', ','));
		}
	}

	private void addScaledTokens(java.util.LinkedHashSet<String> tokens,
								BigDecimal value,
								BigDecimal divisor,
								List<String> suffixes) {
		if (value.compareTo(divisor) < 0) {
			return;
		}
		BigDecimal scaled = value.divide(divisor, 4, java.math.RoundingMode.HALF_UP);
		List<String> numbers = new ArrayList<>();
		numbers.add(scaled.stripTrailingZeros().toPlainString());
		numbers.add(scaled.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
		numbers.add(scaled.setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
		for (String number : numbers) {
			if (number == null || number.isBlank()) {
				continue;
			}
			List<String> variants = new ArrayList<>();
			variants.add(number);
			if (number.contains(".")) {
				variants.add(number.replace('.', ','));
			}
			for (String variant : variants) {
				for (String suffix : suffixes) {
					addToken(tokens, variant + suffix);
					addToken(tokens, variant + " " + suffix);
				}
			}
		}
	}

	private void addToken(java.util.LinkedHashSet<String> tokens, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		tokens.add(value.toLowerCase(Locale.ROOT));
	}

	private boolean canDerivePeCurrent(InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		if (valuation == null) {
			return false;
		}
		if (valuation.price() == null || valuation.epsHistory() == null || valuation.epsHistory().isEmpty()) {
			return false;
		}
		for (InstrumentDossierExtractionPayload.EpsHistoryPayload entry : valuation.epsHistory()) {
			if (entry != null && entry.eps() != null && entry.eps().signum() > 0) {
				return true;
			}
		}
		return false;
	}

	private InstrumentCategory resolveInstrumentCategory(String dossierContent,
													InstrumentDossierExtractionPayload payload) {
		InstrumentCategory category = resolveInstrumentCategory(payload);
		if (category != InstrumentCategory.UNKNOWN) {
			return category;
		}
		return resolveInstrumentCategoryFromContent(dossierContent);
	}

	private InstrumentCategory resolveInstrumentCategory(InstrumentDossierExtractionPayload payload) {
		if (payload == null) {
			return InstrumentCategory.UNKNOWN;
		}
		if (isFundLike(payload)) {
			return InstrumentCategory.FUND;
		}
		String type = normalizeText(payload.instrumentType());
		String asset = normalizeText(payload.assetClass());
		String sub = normalizeText(payload.subClass());
		if (looksReitLike(type) || looksReitLike(asset) || looksReitLike(sub)) {
			return InstrumentCategory.REIT;
		}
		if (looksEquityLike(type) || looksEquityLike(asset) || looksEquityLike(sub)) {
			return InstrumentCategory.EQUITY;
		}
		InstrumentCategory layerCategory = resolveInstrumentCategoryFromLayer(payload.layer());
		if (layerCategory != InstrumentCategory.UNKNOWN) {
			return layerCategory;
		}
		return InstrumentCategory.UNKNOWN;
	}

	private InstrumentCategory resolveInstrumentCategoryFromContent(String content) {
		String value = extractInstrumentTypeValue(content);
		if (value != null) {
			InstrumentCategory category = categorizeInstrumentType(value);
			if (category != InstrumentCategory.UNKNOWN) {
				return category;
			}
		}
		Integer layer = extractLayerValue(content);
		InstrumentCategory layerCategory = resolveInstrumentCategoryFromLayer(layer);
		return layerCategory == null ? InstrumentCategory.UNKNOWN : layerCategory;
	}

	private InstrumentCategory resolveInstrumentCategoryFromLayer(Integer layer) {
		if (layer == null) {
			return InstrumentCategory.UNKNOWN;
		}
		if (layer >= 1 && layer <= 3) {
			return InstrumentCategory.FUND;
		}
		if (layer == 4) {
			return InstrumentCategory.EQUITY;
		}
		return InstrumentCategory.UNKNOWN;
	}

	private InstrumentCategory categorizeInstrumentType(String value) {
		String normalized = normalizeText(value);
		if (normalized.contains("etf") || normalized.contains("fund") || normalized.contains("etp")) {
			return InstrumentCategory.FUND;
		}
		if (looksReitLike(normalized)) {
			return InstrumentCategory.REIT;
		}
		if (looksEquityLike(normalized)) {
			return InstrumentCategory.EQUITY;
		}
		return InstrumentCategory.UNKNOWN;
	}

	private boolean looksEquityLike(String normalized) {
		if (normalized == null || normalized.isBlank()) {
			return false;
		}
		return normalized.contains("equity")
				|| normalized.contains("stock")
				|| normalized.contains("share")
				|| normalized.contains("common")
				|| normalized.contains("adr")
				|| normalized.contains("gdr");
	}

	private boolean looksReitLike(String normalized) {
		if (normalized == null || normalized.isBlank()) {
			return false;
		}
		return normalized.contains("reit") || normalized.contains("real estate investment trust");
	}

	private boolean isFundLike(InstrumentDossierExtractionPayload payload) {
		if (payload == null) {
			return false;
		}
		if (payload.etf() != null) {
			return true;
		}
		String type = payload.instrumentType();
		if (type == null) {
			return false;
		}
		String lower = type.toLowerCase(Locale.ROOT);
		return lower.contains("etf") || lower.contains("fund") || lower.contains("etp");
	}

	private boolean isFundLikeContent(String content) {
		String value = extractInstrumentTypeValue(content);
		if (value == null) {
			return false;
		}
		String normalized = normalizeText(value);
		return normalized.contains("etf") || normalized.contains("fund") || normalized.contains("etp");
	}

	private String extractInstrumentTypeValue(String content) {
		if (content == null || content.isBlank()) {
			return null;
		}
		List<Pattern> patterns = List.of(
				Pattern.compile("(?im)^\\s*instrument_type\\s*:\\s*(.+)$"),
				Pattern.compile("(?im)^\\s*instrument\\s*type\\s*[:|]\\s*(.+)$"),
				Pattern.compile("(?im)^\\s*\\|\\s*instrument\\s*type\\s*\\|\\s*([^|]+)\\|")
		);
		for (Pattern pattern : patterns) {
			var matcher = pattern.matcher(content);
			if (matcher.find()) {
				return cleanInstrumentTypeValue(matcher.group(1));
			}
		}
		return null;
	}

	private Integer extractLayerValue(String content) {
		if (content == null || content.isBlank()) {
			return null;
		}
		List<Pattern> patterns = List.of(
				Pattern.compile("(?im)^\\s*layer\\s*:\\s*(\\d+)\\b"),
				Pattern.compile("(?im)^\\s*layer\\s*\\|\\s*(\\d+)\\b"),
				Pattern.compile("(?im)^\\s*\\|\\s*layer\\s*\\|\\s*(\\d+)\\s*\\|")
		);
		for (Pattern pattern : patterns) {
			var matcher = pattern.matcher(content);
			if (matcher.find()) {
				Integer parsed = parseLayerValue(matcher.group(1));
				if (parsed != null) {
					return parsed;
				}
			}
		}
		return null;
	}

	private Integer parseLayerValue(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.isBlank()) {
			return null;
		}
		try {
			return Integer.parseInt(trimmed);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private String cleanInstrumentTypeValue(String value) {
		if (value == null) {
			return null;
		}
		String cleaned = value.trim();
		if (cleaned.startsWith("|")) {
			cleaned = cleaned.substring(1).trim();
		}
		if (cleaned.endsWith("|")) {
			cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
		}
		return cleaned.isBlank() ? null : cleaned;
	}

	private Set<String> normalizeRegions(List<InstrumentDossierExtractionPayload.RegionExposurePayload> regions) {
		if (regions == null || regions.isEmpty()) {
			return Collections.emptySet();
		}
		Set<String> normalized = new HashSet<>();
		for (InstrumentDossierExtractionPayload.RegionExposurePayload region : regions) {
			if (region == null || region.name() == null) {
				continue;
			}
			String value = normalizeText(region.name());
			if (!value.isBlank()) {
				normalized.add(value);
			}
		}
		return normalized;
	}

	private double jaccard(Set<String> a, Set<String> b) {
		if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
			return 0.0;
		}
		Set<String> intersection = new HashSet<>(a);
		intersection.retainAll(b);
		Set<String> union = new HashSet<>(a);
		union.addAll(b);
		return union.isEmpty() ? 0.0 : (double) intersection.size() / (double) union.size();
	}

	private List<CitationInfo> parseCitations(JsonNode citations) {
		if (citations == null || !citations.isArray()) {
			return List.of();
		}
		List<CitationInfo> parsed = new ArrayList<>();
		for (JsonNode node : citations) {
			String url = node == null ? null : textOrNull(node, "url");
			String publisher = node == null ? null : textOrNull(node, "publisher");
			String title = node == null ? null : textOrNull(node, "title");
			parsed.add(new CitationInfo(url, publisher, title));
		}
		return parsed;
	}

	private boolean hasPrimarySource(List<CitationInfo> citations) {
		if (citations == null || citations.isEmpty()) {
			return false;
		}
		for (CitationInfo info : citations) {
			if (info == null) {
				continue;
			}
			if (looksPrimary(info)) {
				return true;
			}
		}
		return false;
	}

	private boolean looksPrimary(CitationInfo info) {
		String host = extractHost(info.url());
		if (host != null) {
			String normalized = host.toLowerCase(Locale.ROOT);
			for (String secondary : SECONDARY_DOMAINS) {
				if (normalized.equals(secondary) || normalized.endsWith("." + secondary)) {
					return false;
				}
			}
		}
		String combined = (safe(info.publisher()) + " " + safe(info.title()) + " " + safe(info.url())).toLowerCase(Locale.ROOT);
		for (String hint : PRIMARY_HINTS) {
			if (combined.contains(hint)) {
				return true;
			}
		}
		return host != null;
	}

	private String extractHost(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		try {
			URI uri = URI.create(url.trim());
			String host = uri.getHost();
			return host == null ? null : host.toLowerCase(Locale.ROOT);
		} catch (Exception ex) {
			return null;
		}
	}

	private String textOrNull(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		String text = value.asText();
		return text == null || text.isBlank() ? null : text;
	}

	private String normalizeText(String value) {
		if (value == null) {
			return "";
		}
		String normalized = value.toLowerCase(Locale.ROOT);
		normalized = normalized.replaceAll("[^a-z0-9]+", " ").trim();
		return normalized.replaceAll("\\s+", " ");
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}

	private String normalizeSectionWhitespace(String content) {
		if (content == null) {
			return "";
		}
		String normalized = content
				.replace('\u00A0', ' ')
				.replace('\u2007', ' ')
				.replace('\u202F', ' ');
		return normalized.replaceAll("[\\u2000-\\u200B]", " ");
	}

	private boolean hasIsinHeader(String content, String isin) {
		if (content == null || content.isBlank() || isin == null || isin.isBlank()) {
			return false;
		}
		String normalizedIsin = isin.trim().toUpperCase(Locale.ROOT);
		Pattern header = Pattern.compile("(?im)^\\s*#\\s*.*\\b" + Pattern.quote(normalizedIsin) + "\\b.*$");
		return header.matcher(content).find();
	}

	private enum InstrumentCategory {
		FUND,
		EQUITY,
		REIT,
		UNKNOWN
	}

	public record DossierQualityResult(boolean passed, List<String> reasons) {
	}

	public record EvidenceResult(boolean passed, List<String> missingEvidence) {
	}

	public record SimilarityResult(boolean passed, double score, List<String> reasons) {
	}

	private record CitationInfo(String url, String publisher, String title) {
	}

	private record SectionRequirement(String code, Pattern pattern) {
	}
}
