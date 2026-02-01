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
	private static final List<Pattern> REQUIRED_SECTIONS = List.of(
			Pattern.compile("(?im)^##\\s*quick profile\\b"),
			Pattern.compile("(?im)^##\\s*classification\\b"),
			Pattern.compile("(?im)^##\\s*risk\\b"),
			Pattern.compile("(?im)^##\\s*costs\\s*&\\s*structure\\b"),
			Pattern.compile("(?im)^##\\s*exposures\\b"),
			Pattern.compile("(?im)^##\\s*valuation\\s*&\\s*profitability\\b"),
			Pattern.compile("(?im)^##\\s*sources\\b")
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

	public DossierQualityResult evaluateDossier(String isin,
								String contentMd,
								JsonNode citations,
								KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config) {
		List<String> reasons = new ArrayList<>();
		if (contentMd == null || contentMd.isBlank()) {
			reasons.add("empty_content");
			return new DossierQualityResult(false, reasons);
		}
		String trimmed = contentMd.trim();
		int maxChars = config == null ? 0 : config.dossierMaxChars();
		if (maxChars > 0 && trimmed.length() > maxChars) {
			reasons.add("content_exceeds_max_chars");
		}
		if (isin != null && !isin.isBlank()) {
			Pattern header = Pattern.compile("(?im)^#\\s*" + Pattern.quote(isin.trim()) + "\\b");
			if (!header.matcher(trimmed).find()) {
				reasons.add("missing_isin_header");
			}
		}
		for (Pattern section : REQUIRED_SECTIONS) {
			if (!section.matcher(trimmed).find()) {
				reasons.add("missing_section:" + section.pattern());
			}
		}
		List<CitationInfo> parsed = parseCitations(citations);
		int minCitations = config == null ? 0 : config.bulkMinCitations();
		if (parsed.size() < Math.max(1, minCitations)) {
			reasons.add("insufficient_citations");
		}
		boolean requirePrimary = config == null || config.bulkRequirePrimarySource();
		if (requirePrimary && !hasPrimarySource(parsed)) {
			reasons.add("missing_primary_source");
		}
		return new DossierQualityResult(reasons.isEmpty(), reasons);
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

		boolean fundLike = isFundLike(payload);
		if (fundLike) {
		checkTextEvidence(normalizedContent, "benchmark_index", payload.etf() == null ? null : payload.etf().benchmarkIndex(), missingEvidence);
		checkNumericEvidence(dossierContent, "ongoing_charges_pct", payload.etf() == null ? null : payload.etf().ongoingChargesPct(),
				List.of("ter", "ongoing charges", "ongoing charge", "total expense ratio", "ongoing_charges_pct", "ongoing charges pct"),
				true, missingEvidence);
			Integer sri = payload.risk() == null || payload.risk().summaryRiskIndicator() == null
					? null
					: payload.risk().summaryRiskIndicator().value();
			checkSriEvidence(dossierContent, sri, missingEvidence);
		}

		InstrumentDossierExtractionPayload.ValuationPayload valuation = payload.valuation();
		checkNumericEvidence(dossierContent, "price", valuation == null ? null : valuation.price(),
				List.of("price", "nav", "market price"), false, missingEvidence);
		checkNumericEvidence(dossierContent, "pe_current", valuation == null ? null : valuation.peCurrent(),
				List.of("p/e", "pe", "price/earnings"), false, missingEvidence);
		if (valuation != null && valuation.peCurrent() != null
				&& !missingEvidence.isEmpty()
				&& missingEvidence.contains("pe_current")
				&& canDerivePeCurrent(valuation)) {
			missingEvidence.remove("pe_current");
		}
		checkNumericEvidence(dossierContent, "pb_current", valuation == null ? null : valuation.pbCurrent(),
				List.of("p/b", "pb", "price/book", "price to book"), false, missingEvidence);
		if (!fundLike) {
			checkNumericEvidence(dossierContent, "market_cap", valuation == null ? null : valuation.marketCap(),
					List.of("market cap", "market capitalization"), false, missingEvidence);
		}

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

	private List<String> buildNumericTokens(BigDecimal value, boolean allowPercent) {
		if (value == null) {
			return List.of();
		}
		String plain = value.stripTrailingZeros().toPlainString();
		List<String> tokens = new ArrayList<>();
		tokens.add(plain.toLowerCase(Locale.ROOT));
		if (plain.contains(".")) {
			tokens.add(plain.replace('.', ',').toLowerCase(Locale.ROOT));
		}
		if (allowPercent) {
			for (String token : List.copyOf(tokens)) {
				tokens.add(token + "%");
				tokens.add(token + " %");
			}
		}
		return tokens;
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

	public record DossierQualityResult(boolean passed, List<String> reasons) {
	}

	public record EvidenceResult(boolean passed, List<String> missingEvidence) {
	}

	public record SimilarityResult(boolean passed, double score, List<String> reasons) {
	}

	private record CitationInfo(String url, String publisher, String title) {
	}
}
