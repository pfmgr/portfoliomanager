package my.portfoliomanager.app.service;

import tools.jackson.databind.JsonNode;
import my.portfoliomanager.app.domain.InstrumentDossier;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DossierPreParser {
	private static final Pattern KEY_VALUE = Pattern.compile("^\\s*[-*+]?\\s*([A-Za-z0-9_.\\s/()-]+?)\\s*:\\s*(.+)$");
	private static final Pattern EPS_HISTORY_ENTRY = Pattern.compile("^\\s*[-*+]\\s*(\\d{4})\\s*:\\s*(.+)$");
	private static final Pattern DATE_PATTERN = Pattern.compile("\\b(20\\d{2}-\\d{2}-\\d{2})\\b");
	private static final Pattern CURRENCY_PATTERN = Pattern.compile("\\b([A-Z]{3})\\b");
	private static final Pattern DECIMAL_WITH_OPTIONAL_SCALE_PATTERN = Pattern.compile(
			"(?iu)([-+]?(?:\\d{1,3}(?:[.,\\s]\\d{3})+|\\d+)(?:[.,]\\d+)?)\\s*(?:([A-Z]{3})\\s*)?(b|bn|billion|m|mn|million|k|thousand)?(?!\\p{L})"
	);

	public InstrumentDossierExtractionPayload parse(InstrumentDossier dossier) {
		String content = dossier == null ? null : dossier.getContentMd();
		String name = null;
		String instrumentType = null;
		String assetClass = null;
		String subClass = null;
		Integer layer = null;
		String layerNotes = null;
		BigDecimal ongoingChargesPct = null;
		String benchmarkIndex = null;
		Integer summaryRiskIndicator = null;

		BigDecimal revenue = null;
		String revenueCurrency = null;
		String revenuePeriodEnd = null;
		String revenuePeriodType = null;
		BigDecimal netIncome = null;
		String netIncomeCurrency = null;
		String netIncomePeriodEnd = null;
		String netIncomePeriodType = null;
		BigDecimal dividendPerShare = null;
		String dividendCurrency = null;
		String dividendAsOf = null;
		BigDecimal financialsFxRateToEur = null;

		BigDecimal ebitda = null;
		String ebitdaCurrency = null;
		String ebitdaPeriodEnd = null;
		String ebitdaPeriodType = null;
		BigDecimal enterpriseValue = null;
		BigDecimal netDebt = null;
		BigDecimal marketCap = null;
		BigDecimal sharesOutstanding = null;
		BigDecimal evToEbitda = null;
		BigDecimal netRent = null;
		String netRentCurrency = null;
		String netRentPeriodEnd = null;
		String netRentPeriodType = null;
		BigDecimal noi = null;
		String noiCurrency = null;
		String noiPeriodEnd = null;
		String noiPeriodType = null;
		BigDecimal affo = null;
		String affoCurrency = null;
		String affoPeriodEnd = null;
		String affoPeriodType = null;
		BigDecimal ffo = null;
		String ffoCurrency = null;
		String ffoPeriodEnd = null;
		String ffoPeriodType = null;
		String ffoType = null;
		BigDecimal price = null;
		String priceCurrency = null;
		String priceAsOf = null;
		String epsType = null;
		BigDecimal epsNorm = null;
		Integer epsNormYearsUsed = null;
		Integer epsNormYearsAvailable = null;
		List<InstrumentDossierExtractionPayload.EpsHistoryPayload> epsHistory = new ArrayList<>();
		String epsFloorPolicy = null;
		BigDecimal epsFloorValue = null;
		String epsNormPeriodEnd = null;
		BigDecimal peLongterm = null;
		BigDecimal earningsYieldLongterm = null;
		BigDecimal peCurrent = null;
		String peCurrentAsOf = null;
		BigDecimal pbCurrent = null;
		String pbCurrentAsOf = null;
		BigDecimal peTtmHoldings = null;
		BigDecimal earningsYieldTtmHoldings = null;
		BigDecimal holdingsCoverageWeightPct = null;
		Integer holdingsCoverageCount = null;
		String holdingsAsOf = null;
		String holdingsWeightMethod = null;
		String peMethod = null;
		String peHorizon = null;
		String negEarningsHandling = null;
		BigDecimal valuationFxRateToEur = null;

		if (content != null && !content.isBlank()) {
			boolean inEpsHistory = false;
			for (String line : content.split("\\R")) {
				String trimmed = line == null ? "" : line.trim();
				if (trimmed.startsWith("## ") || trimmed.startsWith("###")) {
					inEpsHistory = false;
				}
				if (inEpsHistory) {
					Matcher epsMatcher = EPS_HISTORY_ENTRY.matcher(line);
					if (epsMatcher.matches()) {
						Integer year = parseInteger(epsMatcher.group(1));
						String raw = epsMatcher.group(2);
						BigDecimal epsValue = parseDecimal(raw);
						if (year != null && epsValue != null) {
							String currency = extractCurrency(raw);
							String periodEnd = extractDate(raw);
							String type = extractPeriodType(raw);
							epsHistory.add(new InstrumentDossierExtractionPayload.EpsHistoryPayload(
									year,
									epsValue,
									trimToNull(type),
									trimToNull(currency),
									trimToNull(periodEnd)
							));
						}
						continue;
					}
					if (!trimmed.startsWith("-")) {
						inEpsHistory = false;
					}
				}

				Matcher matcher = KEY_VALUE.matcher(line);
				if (!matcher.matches()) {
					continue;
				}
				String key = normalizeKey(matcher.group(1));
				String rawValue = matcher.group(2).trim();
				if (rawValue.isBlank()) {
					continue;
				}
				String textValue = normalizeTextValue(rawValue);
				if ("eps_history".equals(key)) {
					if (textValue == null || textValue.equalsIgnoreCase("unknown")) {
						inEpsHistory = true;
					}
					continue;
				}
				if (textValue == null) {
					continue;
				}
				if (ongoingChargesPct == null && (key.equals("ter") || key.startsWith("ter_") || key.contains("ongoing_charges"))) {
					ongoingChargesPct = firstValue(ongoingChargesPct, parseDecimal(textValue));
					continue;
				}

				switch (key) {
					case "name", "display_name", "displayname" -> name = firstValue(name, textValue);
					case "instrument_type", "instrumenttype", "type" -> instrumentType = firstValue(instrumentType, textValue);
					case "asset_class", "assetclass" -> assetClass = firstValue(assetClass, textValue);
					case "sub_class", "subclass", "sub_classification" -> subClass = firstValue(subClass, textValue);
					case "layer" -> layer = firstValue(layer, parseInteger(textValue));
					case "layer_notes", "layernotes" -> layerNotes = firstValue(layerNotes, textValue);
					case "ongoing_charges_pct", "ongoing_charges", "ter" -> ongoingChargesPct = firstValue(ongoingChargesPct, parseDecimal(textValue));
					case "benchmark_index", "benchmark", "index_tracked", "index" -> benchmarkIndex = firstValue(benchmarkIndex, textValue);
					case "summary_risk_indicator", "summary_risk_indicator_value", "sri" -> summaryRiskIndicator = firstValue(summaryRiskIndicator, parseInteger(textValue));
					case "revenue" -> {
						revenue = firstValue(revenue, parseDecimal(textValue));
						revenueCurrency = firstValue(revenueCurrency, extractCurrency(textValue));
						revenuePeriodEnd = firstValue(revenuePeriodEnd, extractDate(textValue));
						revenuePeriodType = firstValue(revenuePeriodType, extractPeriodType(textValue));
					}
					case "net_income" -> {
						netIncome = firstValue(netIncome, parseDecimal(textValue));
						netIncomeCurrency = firstValue(netIncomeCurrency, extractCurrency(textValue));
						netIncomePeriodEnd = firstValue(netIncomePeriodEnd, extractDate(textValue));
						netIncomePeriodType = firstValue(netIncomePeriodType, extractPeriodType(textValue));
					}
					case "dividend_per_share" -> {
						dividendPerShare = firstValue(dividendPerShare, parseDecimal(textValue));
						dividendCurrency = firstValue(dividendCurrency, extractCurrency(textValue));
						dividendAsOf = firstValue(dividendAsOf, extractDate(textValue));
					}
					case "fx_rate_to_eur", "financials_fx_rate_to_eur" -> financialsFxRateToEur = firstValue(financialsFxRateToEur, parseDecimal(textValue));
					case "ebitda" -> {
						ebitda = firstValue(ebitda, parseDecimal(textValue));
						ebitdaCurrency = firstValue(ebitdaCurrency, extractCurrency(textValue));
						ebitdaPeriodEnd = firstValue(ebitdaPeriodEnd, extractDate(textValue));
						ebitdaPeriodType = firstValue(ebitdaPeriodType, extractPeriodType(textValue));
					}
					case "enterprise_value" -> enterpriseValue = firstValue(enterpriseValue, parseDecimal(textValue));
					case "net_debt" -> netDebt = firstValue(netDebt, parseDecimal(textValue));
					case "market_cap" -> marketCap = firstValue(marketCap, parseDecimal(textValue));
					case "shares_outstanding" -> sharesOutstanding = firstValue(sharesOutstanding, parseDecimal(textValue));
					case "ev_to_ebitda" -> evToEbitda = firstValue(evToEbitda, parseDecimal(textValue));
					case "net_rent" -> {
						netRent = firstValue(netRent, parseDecimal(textValue));
						netRentCurrency = firstValue(netRentCurrency, extractCurrency(textValue));
						netRentPeriodEnd = firstValue(netRentPeriodEnd, extractDate(textValue));
						netRentPeriodType = firstValue(netRentPeriodType, extractPeriodType(textValue));
					}
					case "noi" -> {
						noi = firstValue(noi, parseDecimal(textValue));
						noiCurrency = firstValue(noiCurrency, extractCurrency(textValue));
						noiPeriodEnd = firstValue(noiPeriodEnd, extractDate(textValue));
						noiPeriodType = firstValue(noiPeriodType, extractPeriodType(textValue));
					}
					case "affo" -> {
						affo = firstValue(affo, parseDecimal(textValue));
						affoCurrency = firstValue(affoCurrency, extractCurrency(textValue));
						affoPeriodEnd = firstValue(affoPeriodEnd, extractDate(textValue));
						affoPeriodType = firstValue(affoPeriodType, extractPeriodType(textValue));
					}
					case "ffo" -> {
						ffo = firstValue(ffo, parseDecimal(textValue));
						ffoCurrency = firstValue(ffoCurrency, extractCurrency(textValue));
						ffoPeriodEnd = firstValue(ffoPeriodEnd, extractDate(textValue));
						ffoPeriodType = firstValue(ffoPeriodType, extractPeriodType(textValue));
					}
					case "ffo_type" -> ffoType = firstValue(ffoType, textValue);
					case "price" -> {
						price = firstValue(price, parseDecimal(textValue));
						priceCurrency = firstValue(priceCurrency, extractCurrency(textValue));
						priceAsOf = firstValue(priceAsOf, extractDate(textValue));
					}
					case "eps_type" -> epsType = firstValue(epsType, textValue);
					case "eps_norm" -> epsNorm = firstValue(epsNorm, parseDecimal(textValue));
					case "eps_norm_years_used" -> epsNormYearsUsed = firstValue(epsNormYearsUsed, parseInteger(textValue));
					case "eps_norm_years_available" -> epsNormYearsAvailable = firstValue(epsNormYearsAvailable, parseInteger(textValue));
					case "eps_floor_policy" -> epsFloorPolicy = firstValue(epsFloorPolicy, textValue);
					case "eps_floor_value" -> epsFloorValue = firstValue(epsFloorValue, parseDecimal(textValue));
					case "eps_norm_period_end" -> epsNormPeriodEnd = firstValue(epsNormPeriodEnd, extractDate(textValue));
					case "pe_longterm" -> peLongterm = firstValue(peLongterm, parseDecimal(textValue));
					case "earnings_yield_longterm" -> earningsYieldLongterm = firstValue(earningsYieldLongterm, parseDecimal(textValue));
					case "pe_current" -> {
						peCurrent = firstValue(peCurrent, parseDecimal(textValue));
						peCurrentAsOf = firstValue(peCurrentAsOf, extractDate(textValue));
					}
					case "pb_current" -> {
						pbCurrent = firstValue(pbCurrent, parseDecimal(textValue));
						pbCurrentAsOf = firstValue(pbCurrentAsOf, extractDate(textValue));
					}
					case "pe_ttm_holdings" -> peTtmHoldings = firstValue(peTtmHoldings, parseDecimal(textValue));
					case "earnings_yield_ttm_holdings" -> earningsYieldTtmHoldings = firstValue(earningsYieldTtmHoldings, parseDecimal(textValue));
					case "holdings_coverage_weight_pct" -> holdingsCoverageWeightPct = firstValue(holdingsCoverageWeightPct, parseDecimal(textValue));
					case "holdings_coverage_count" -> holdingsCoverageCount = firstValue(holdingsCoverageCount, parseInteger(textValue));
					case "holdings_asof" -> holdingsAsOf = firstValue(holdingsAsOf, extractDate(textValue) != null ? extractDate(textValue) : textValue);
					case "holdings_weight_method" -> holdingsWeightMethod = firstValue(holdingsWeightMethod, textValue);
					case "pe_method" -> peMethod = firstValue(peMethod, textValue);
					case "pe_horizon" -> peHorizon = firstValue(peHorizon, textValue);
					case "neg_earnings_handling" -> negEarningsHandling = firstValue(negEarningsHandling, textValue);
					case "valuation_fx_rate_to_eur" -> valuationFxRateToEur = firstValue(valuationFxRateToEur, parseDecimal(textValue));
					default -> {
					}
				}
			}
		}

		if (layer != null && (layer < 1 || layer > 5)) {
			layer = null;
		}
		if (summaryRiskIndicator != null && (summaryRiskIndicator < 1 || summaryRiskIndicator > 7)) {
			summaryRiskIndicator = null;
		}

		InstrumentDossierExtractionPayload.EtfPayload etfPayload =
				(ongoingChargesPct == null && benchmarkIndex == null)
						? null
						: new InstrumentDossierExtractionPayload.EtfPayload(ongoingChargesPct, benchmarkIndex);
		InstrumentDossierExtractionPayload.RiskPayload riskPayload =
			summaryRiskIndicator == null
					? null
					: new InstrumentDossierExtractionPayload.RiskPayload(
							new InstrumentDossierExtractionPayload.SummaryRiskIndicatorPayload(summaryRiskIndicator),
							null
					);
		InstrumentDossierExtractionPayload.FinancialsPayload financialsPayload =
				(revenue == null && netIncome == null && dividendPerShare == null && financialsFxRateToEur == null)
						? null
						: new InstrumentDossierExtractionPayload.FinancialsPayload(
							revenue,
							revenueCurrency,
							null,
							revenuePeriodEnd,
							revenuePeriodType,
							netIncome,
							netIncomeCurrency,
							null,
							netIncomePeriodEnd,
							netIncomePeriodType,
							dividendPerShare,
							dividendCurrency,
							dividendAsOf,
							financialsFxRateToEur
					);
		InstrumentDossierExtractionPayload.ValuationPayload valuationPayload =
				(isAllNull(
						ebitda, enterpriseValue, netDebt, marketCap, sharesOutstanding, evToEbitda, netRent,
						netRentCurrency, noi, noiCurrency, affo, affoCurrency, ffo, ffoCurrency, price, peCurrent, pbCurrent,
						peLongterm, earningsYieldLongterm, peTtmHoldings, earningsYieldTtmHoldings, holdingsCoverageWeightPct,
						holdingsCoverageCount, holdingsAsOf, holdingsWeightMethod, peMethod, peHorizon, negEarningsHandling,
						epsNorm, epsNormYearsUsed, epsNormYearsAvailable, epsFloorPolicy, epsFloorValue, epsNormPeriodEnd,
						epsHistory.isEmpty() ? null : epsHistory))
						? null
						: new InstrumentDossierExtractionPayload.ValuationPayload(
							ebitda,
							ebitdaCurrency,
							null,
							valuationFxRateToEur,
							ebitdaPeriodEnd,
							ebitdaPeriodType,
							enterpriseValue,
							netDebt,
							marketCap,
							sharesOutstanding,
							evToEbitda,
							netRent,
							netRentCurrency,
							netRentPeriodEnd,
							netRentPeriodType,
							noi,
							noiCurrency,
							noiPeriodEnd,
							noiPeriodType,
							affo,
							affoCurrency,
							affoPeriodEnd,
							affoPeriodType,
							ffo,
							ffoCurrency,
							ffoPeriodEnd,
							ffoPeriodType,
							ffoType,
							price,
							priceCurrency,
							priceAsOf,
							trimToNull(epsType),
							epsNorm,
							epsNormYearsUsed,
							epsNormYearsAvailable,
							epsHistory.isEmpty() ? null : List.copyOf(epsHistory),
							epsFloorPolicy,
							epsFloorValue,
							epsNormPeriodEnd,
							peLongterm,
							earningsYieldLongterm,
							peCurrent,
							peCurrentAsOf,
							pbCurrent,
							pbCurrentAsOf,
							peTtmHoldings,
							earningsYieldTtmHoldings,
							holdingsCoverageWeightPct,
							holdingsCoverageCount,
							holdingsAsOf,
							holdingsWeightMethod,
							peMethod,
							peHorizon,
							negEarningsHandling
						);

		List<InstrumentDossierExtractionPayload.SourcePayload> sources = extractSources(
			dossier == null ? null : dossier.getCitationsJson()
		);
		List<InstrumentDossierExtractionPayload.WarningPayload> warnings = List.of(
				new InstrumentDossierExtractionPayload.WarningPayload("Parser prefill applied.")
		);
		List<InstrumentDossierExtractionPayload.MissingFieldPayload> missingFields = buildMissingFields(
				name,
				instrumentType,
				assetClass,
				subClass,
				layer,
				layerNotes,
				ongoingChargesPct,
				benchmarkIndex,
				summaryRiskIndicator,
				financialsPayload,
				valuationPayload
		);

		return new InstrumentDossierExtractionPayload(
			dossier == null ? null : dossier.getIsin(),
			name,
			instrumentType,
			assetClass,
			subClass,
			null,
			null,
			null,
			null,
			layer,
			layerNotes,
			etfPayload,
			riskPayload,
			null,
			null,
			null,
			financialsPayload,
			valuationPayload,
			sources,
			missingFields,
			warnings
		);
	}

	private String normalizeKey(String raw) {
		String normalized = raw == null ? "" : raw.trim();
		normalized = normalized.replaceFirst("^[\\-*+]+\\s*", "");
		normalized = normalized.toLowerCase(Locale.ROOT).replaceAll("[\\s]+", "_");
		normalized = normalized.replaceAll("[^a-z0-9_.]", "");
		return normalized;
	}

	private String normalizeTextValue(String raw) {
		String value = raw == null ? null : raw.trim();
		if (value == null || value.isBlank()) {
			return null;
		}
		String lower = value.toLowerCase(Locale.ROOT);
		if (lower.equals("unknown") || lower.equals("n/a") || lower.equals("na") || lower.equals("-")) {
			return null;
		}
		return value;
	}

	private BigDecimal parseDecimal(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		Matcher matcher = DECIMAL_WITH_OPTIONAL_SCALE_PATTERN.matcher(raw);
		while (matcher.find()) {
			BigDecimal number = parseLocalizedNumber(matcher.group(1));
			if (number == null) {
				continue;
			}
			BigDecimal scale = detectScale(matcher.group(3));
			if (scale != null) {
				number = number.multiply(scale);
			}
			return number;
		}
		return null;
	}

	private BigDecimal parseLocalizedNumber(String token) {
		if (token == null || token.isBlank()) {
			return null;
		}
		String cleaned = token.trim()
				.replace("\u00A0", "")
				.replace("\u202F", "")
				.replace(" ", "")
				.replace("'", "");
		if (cleaned.isBlank() || "+".equals(cleaned) || "-".equals(cleaned)) {
			return null;
		}

		int commaCount = countChar(cleaned, ',');
		int dotCount = countChar(cleaned, '.');
		String normalized;
		if (commaCount > 0 && dotCount > 0) {
			int lastComma = cleaned.lastIndexOf(',');
			int lastDot = cleaned.lastIndexOf('.');
			char decimalSeparator = lastComma > lastDot ? ',' : '.';
			char groupingSeparator = decimalSeparator == ',' ? '.' : ',';
			normalized = cleaned.replace(String.valueOf(groupingSeparator), "");
			if (decimalSeparator == ',') {
				normalized = normalized.replace(',', '.');
			}
		} else if (commaCount > 0) {
			normalized = normalizeSingleSeparator(cleaned, ',');
		} else if (dotCount > 0) {
			normalized = normalizeSingleSeparator(cleaned, '.');
		} else {
			normalized = cleaned;
		}

		if (normalized == null || !normalized.matches("[-+]?\\d+(?:\\.\\d+)?")) {
			return null;
		}
		try {
			return new BigDecimal(normalized);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private String normalizeSingleSeparator(String raw, char separator) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String sign = "";
		String unsigned = raw;
		if (unsigned.startsWith("+") || unsigned.startsWith("-")) {
			sign = unsigned.substring(0, 1);
			unsigned = unsigned.substring(1);
		}
		if (unsigned.isBlank()) {
			return null;
		}
		int first = unsigned.indexOf(separator);
		if (first < 0) {
			return raw;
		}
		int last = unsigned.lastIndexOf(separator);
		String separatorToken = String.valueOf(separator);
		if (first != last) {
			if (looksLikeGroupedThousands(unsigned, separator)) {
				return sign + unsigned.replace(separatorToken, "");
			}
			String integerPart = unsigned.substring(0, last).replace(separatorToken, "");
			String fractionalPart = unsigned.substring(last + 1);
			if (fractionalPart.isBlank()) {
				return null;
			}
			return sign + integerPart + "." + fractionalPart;
		}

		String integerPart = unsigned.substring(0, first);
		String fractionalPart = unsigned.substring(first + 1);
		if (fractionalPart.isBlank()) {
			return null;
		}
		if (fractionalPart.length() == 3
				&& integerPart.length() >= 1
				&& integerPart.length() <= 3
				&& !"0".equals(integerPart)) {
			return sign + integerPart + fractionalPart;
		}
		return sign + integerPart + "." + fractionalPart;
	}

	private boolean looksLikeGroupedThousands(String raw, char separator) {
		if (raw == null || raw.isBlank()) {
			return false;
		}
		String[] groups = raw.split(Pattern.quote(String.valueOf(separator)), -1);
		if (groups.length < 2) {
			return false;
		}
		if (!groups[0].matches("\\d{1,3}")) {
			return false;
		}
		for (int i = 1; i < groups.length; i++) {
			if (!groups[i].matches("\\d{3}")) {
				return false;
			}
		}
		return true;
	}

	private int countChar(String value, char c) {
		if (value == null || value.isBlank()) {
			return 0;
		}
		int count = 0;
		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) == c) {
				count++;
			}
		}
		return count;
	}

	private BigDecimal detectScale(String suffix) {
		if (suffix == null || suffix.isBlank()) {
			return null;
		}
		return switch (suffix.toLowerCase(Locale.ROOT)) {
			case "b", "bn", "billion" -> new BigDecimal("1000000000");
			case "m", "mn", "million" -> new BigDecimal("1000000");
			case "k", "thousand" -> new BigDecimal("1000");
			default -> null;
		};
	}

	private Integer parseInteger(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		Matcher matcher = Pattern.compile("[-+]?[0-9]+", Pattern.CASE_INSENSITIVE).matcher(raw.replace(",", ""));
		if (!matcher.find()) {
			return null;
		}
		try {
			return Integer.parseInt(matcher.group());
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private String extractCurrency(String raw) {
		if (raw == null) {
			return null;
		}
		Matcher matcher = CURRENCY_PATTERN.matcher(raw);
		if (matcher.find()) {
			String currency = matcher.group(1);
			if (currency == null || currency.isBlank()) {
				return null;
			}
			return currency.toUpperCase(Locale.ROOT);
		}
		return null;
	}

	private String extractDate(String raw) {
		if (raw == null) {
			return null;
		}
		Matcher matcher = DATE_PATTERN.matcher(raw);
		return matcher.find() ? matcher.group(1) : null;
	}

	private String extractPeriodType(String raw) {
		if (raw == null) {
			return null;
		}
		String lower = raw.toLowerCase(Locale.ROOT);
		if (lower.contains("ttm")) {
			return "TTM";
		}
		if (lower.matches(".*\\bfy\\b.*") || lower.contains("fiscal year")) {
			return "FY";
		}
		if (lower.contains("quarter") || lower.matches(".*\\bq[1-4]\\b.*")) {
			return "Q";
		}
		return null;
	}

	private <T> T firstValue(T current, T next) {
		return current == null ? next : current;
	}

	private String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isBlank() ? null : trimmed;
	}

	private boolean isAllNull(Object... values) {
		for (Object value : values) {
			if (value != null) {
				return false;
			}
		}
		return true;
	}

	private List<InstrumentDossierExtractionPayload.SourcePayload> extractSources(JsonNode citations) {
		if (citations == null || !citations.isArray()) {
			return List.of();
		}
		List<InstrumentDossierExtractionPayload.SourcePayload> sources = new ArrayList<>();
		for (JsonNode node : citations) {
			String id = textOrNull(node, "id");
			String title = textOrNull(node, "title");
			String url = textOrNull(node, "url");
			String publisher = textOrNull(node, "publisher");
			String accessedAt = textOrNull(node, "accessed_at");
			if (accessedAt == null) {
				accessedAt = textOrNull(node, "accessedAt");
			}
			sources.add(new InstrumentDossierExtractionPayload.SourcePayload(id, title, url, publisher, accessedAt));
		}
		return sources;
	}

	private String textOrNull(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		String text = value.asText();
		return text == null || text.isBlank() ? null : text;
	}

	private List<InstrumentDossierExtractionPayload.MissingFieldPayload> buildMissingFields(
			String name,
			String instrumentType,
			String assetClass,
			String subClass,
			Integer layer,
			String layerNotes,
			BigDecimal ongoingChargesPct,
			String benchmarkIndex,
			Integer summaryRiskIndicator,
			InstrumentDossierExtractionPayload.FinancialsPayload financials,
			InstrumentDossierExtractionPayload.ValuationPayload valuation
	) {
		List<InstrumentDossierExtractionPayload.MissingFieldPayload> missing = new ArrayList<>();
		addMissing(missing, "name", name);
		addMissing(missing, "instrument_type", instrumentType);
		addMissing(missing, "asset_class", assetClass);
		addMissing(missing, "sub_class", subClass);
		addMissing(missing, "layer", layer);
		addMissing(missing, "layer_notes", layerNotes);
		addMissing(missing, "etf.ongoing_charges_pct", ongoingChargesPct);
		addMissing(missing, "etf.benchmark_index", benchmarkIndex);
		addMissing(missing, "risk.summary_risk_indicator.value", summaryRiskIndicator);
		addMissing(missing, "financials", financials);
		addMissing(missing, "valuation", valuation);
		return missing;
	}

	private void addMissing(List<InstrumentDossierExtractionPayload.MissingFieldPayload> missing, String field, Object value) {
		if (value == null) {
			missing.add(new InstrumentDossierExtractionPayload.MissingFieldPayload(field, "Not found in dossier content."));
		}
	}
}
