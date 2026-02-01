package my.portfoliomanager.app.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmAlternativeItem;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmAlternativesDraft;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmClient;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmDossierDraft;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmExtractionDraft;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmOutputException;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmProvider;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmResponse;
import my.portfoliomanager.app.llm.LlmRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KnowledgeBaseLlmService implements KnowledgeBaseLlmClient {
	private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseLlmService.class);
	private static final Pattern ISIN_RE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");
	private static final Pattern RESEARCH_DATE_RE = Pattern.compile("(?i)research date\\s*[:\\-]?\\s*(\\d{4}-\\d{2}-\\d{2})");
	private static final Pattern DATA_ASOF_RE = Pattern.compile("(?i)data\\s+as\\s+of\\s*[:\\-]?\\s*(\\d{4}-\\d{2}-\\d{2})");
	private static final Pattern VALUATION_LINE_RE = Pattern.compile("^\\s*-\\s*([^:]+):\\s*(.*)$");
	private static final Pattern TRAILING_CITATION_RE = Pattern.compile("\\s*\\(\\[[^\\]]+\\]\\([^\\)]+\\)\\)\\s*$");
	private static final Pattern RANGE_RE = Pattern.compile(
			"^\\s*([-+]?\\d[\\d.,]*)\\s*(?:-|–|—|to)\\s*([-+]?\\d[\\d.,]*)(\\s*[A-Za-z%€$\\.]+(?:\\s*[A-Za-z%€$\\.]+)?)?(\\s*\\([^)]*\\))?.*$",
			Pattern.CASE_INSENSITIVE
	);
	private static final Pattern LEADING_NUMBER_RE = Pattern.compile("^([-+]?\\d[\\d.,]*)(\\s*[A-Za-z%€$\\.]+(?:\\s*[A-Za-z%€$\\.]+)?)?(\\s*\\([^)]*\\))?.*$");
	private static final Pattern ASOF_DATE_RE = Pattern.compile("(?i)as of\\s*(\\d{4}-\\d{2}-\\d{2})");
	private static final Pattern TRAILING_PAREN_RE = Pattern.compile("^(.*?)(\\s*\\(([^)]*)\\))$");
	private static final Pattern ISO_DATE_RE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
	private static final Pattern PERIOD_HINT_RE = Pattern.compile("(?i)\\b(fy|ttm|ltm|period end)\\b");
	private static final Pattern YEAR_RE = Pattern.compile("\\b\\d{4}\\b");
	private static final List<String> THEMATIC_KEYWORDS = List.of(
			"theme",
			"thematic",
			"sector",
			"industry",
			"defense",
			"defence",
			"energy",
			"lithium",
			"battery",
			"batteries",
			"clean",
			"renewable",
			"semiconductor",
			"robot",
			"ai",
			"artificial intelligence",
			"cyber",
			"cloud",
			"biotech",
			"healthcare",
			"pharma",
			"water",
			"gold",
			"silver",
			"oil",
			"gas",
			"uranium",
			"mining",
			"metals",
			"commodity",
			"commodities"
	);
	private static final List<String> VALUATION_KEY_ORDER = List.of(
			"price",
			"pe_current",
			"pb_current",
			"dividend_per_share",
			"revenue",
			"net_income",
			"ebitda",
			"enterprise_value",
			"net_debt",
			"ev_to_ebitda",
			"market_cap",
			"shares_outstanding",
			"eps_history",
			"eps_norm",
			"pe_longterm",
			"earnings_yield_longterm",
			"pe_ttm_holdings",
			"earnings_yield_ttm_holdings",
			"holdings_coverage_weight_pct",
			"holdings_coverage_count",
			"holdings_asof",
			"holdings_weight_method",
			"pe_method",
			"pe_horizon",
			"neg_earnings_handling",
			"net_rent",
			"noi",
			"affo",
			"ffo"
	);
	private static final Set<String> VALUATION_KEY_SET = Set.copyOf(VALUATION_KEY_ORDER);
	private static final String INVALID_OUTPUT = "invalid_output";
	private static final String EXTRACTION_SCHEMA_JSON = """
			{
			  "$schema": "https://json-schema.org/draft/2020-12/schema",
			  "type": "object",
			  "additionalProperties": false,
		  "required": ["isin","name","instrument_type","asset_class","sub_class","layer","layer_notes","etf","risk","regions","top_holdings","financials","valuation","missing_fields","warnings"],
			  "properties": {
			    "isin": { "type": ["string","null"] },
			    "name": { "type": ["string","null"] },
			    "instrument_type": { "type": ["string","null"] },
			    "asset_class": { "type": ["string","null"] },
			    "sub_class": { "type": ["string","null"] },
			    "layer": { "type": ["integer","null"] },
			    "layer_notes": { "type": ["string","null"] },
			    "etf": {
			      "anyOf": [
			        { "type": "null" },
			        {
			          "type": "object",
			          "additionalProperties": false,
			          "required": ["ongoing_charges_pct","benchmark_index"],
			          "properties": {
			            "ongoing_charges_pct": { "type": ["number","null"] },
			            "benchmark_index": { "type": ["string","null"] }
			          }
			        }
			      ]
			    },
			    "risk": {
			      "anyOf": [
			        { "type": "null" },
			        {
			          "type": "object",
			          "additionalProperties": false,
			          "required": ["summary_risk_indicator"],
			          "properties": {
			            "summary_risk_indicator": {
			              "anyOf": [
			                { "type": "null" },
			                {
			                  "type": "object",
			                  "additionalProperties": false,
			                  "required": ["value"],
			                  "properties": {
			                    "value": { "type": ["integer","null"] }
			                  }
			                }
			              ]
			            }
			          }
			        }
			      ]
			    },
			    "regions": {
			      "type": ["array","null"],
			      "items": {
			        "type": "object",
			        "additionalProperties": false,
			        "required": ["name","weight_pct"],
			        "properties": {
			          "name": { "type": ["string","null"] },
			          "weight_pct": { "type": ["number","null"] }
			        }
			      }
			    },
		    "top_holdings": {
		      "type": ["array","null"],
		      "items": {
		        "type": "object",
		        "additionalProperties": false,
		        "required": ["name","weight_pct"],
		        "properties": {
		          "name": { "type": ["string","null"] },
		          "weight_pct": { "type": ["number","null"] }
		        }
		      }
		    },
		    "financials": {
		      "anyOf": [
		        { "type": "null" },
		        {
		          "type": "object",
		          "additionalProperties": false,
		          "required": [
		            "revenue",
		            "revenue_currency",
		            "revenue_eur",
		            "revenue_period_end",
		            "revenue_period_type",
		            "net_income",
		            "net_income_currency",
		            "net_income_eur",
		            "net_income_period_end",
		            "net_income_period_type",
		            "dividend_per_share",
		            "dividend_currency",
		            "dividend_asof",
		            "fx_rate_to_eur"
		          ],
		          "properties": {
		            "revenue": { "type": ["number","null"] },
		            "revenue_currency": { "type": ["string","null"] },
		            "revenue_eur": { "type": ["number","null"] },
		            "revenue_period_end": { "type": ["string","null"], "format": "date" },
		            "revenue_period_type": { "type": ["string","null"] },
		            "net_income": { "type": ["number","null"] },
		            "net_income_currency": { "type": ["string","null"] },
		            "net_income_eur": { "type": ["number","null"] },
		            "net_income_period_end": { "type": ["string","null"], "format": "date" },
		            "net_income_period_type": { "type": ["string","null"] },
		            "dividend_per_share": { "type": ["number","null"] },
		            "dividend_currency": { "type": ["string","null"] },
		            "dividend_asof": { "type": ["string","null"], "format": "date" },
		            "fx_rate_to_eur": { "type": ["number","null"] }
		          }
		        }
		      ]
		    },
		    "valuation": {
		      "anyOf": [
		        { "type": "null" },
		        {
		          "type": "object",
		          "additionalProperties": false,
		          "required": [
		            "ebitda",
		            "ebitda_currency",
		            "ebitda_eur",
		            "fx_rate_to_eur",
		            "ebitda_period_end",
		            "ebitda_period_type",
		            "enterprise_value",
		            "net_debt",
		            "market_cap",
		            "shares_outstanding",
		            "ev_to_ebitda",
		            "net_rent",
		            "net_rent_currency",
		            "net_rent_period_end",
		            "net_rent_period_type",
		            "noi",
		            "noi_currency",
		            "noi_period_end",
		            "noi_period_type",
		            "affo",
		            "affo_currency",
		            "affo_period_end",
		            "affo_period_type",
		            "ffo",
		            "ffo_currency",
		            "ffo_period_end",
		            "ffo_period_type",
		            "ffo_type",
		            "price",
		            "price_currency",
		            "price_asof",
		            "eps_type",
		            "eps_norm",
		            "eps_norm_years_used",
		            "eps_norm_years_available",
		            "eps_history",
		            "eps_floor_policy",
		            "eps_floor_value",
		            "eps_norm_period_end",
		            "pe_longterm",
		            "earnings_yield_longterm",
		            "pe_current",
		            "pe_current_asof",
		            "pb_current",
		            "pb_current_asof",
		            "pe_ttm_holdings",
		            "earnings_yield_ttm_holdings",
		            "holdings_coverage_weight_pct",
		            "holdings_coverage_count",
		            "holdings_asof",
		            "holdings_weight_method",
		            "pe_method",
		            "pe_horizon",
		            "neg_earnings_handling"
		          ],
		          "properties": {
		            "ebitda": { "type": ["number","null"] },
		            "ebitda_currency": { "type": ["string","null"] },
		            "ebitda_eur": { "type": ["number","null"] },
		            "fx_rate_to_eur": { "type": ["number","null"] },
		            "ebitda_period_end": { "type": ["string","null"], "format": "date" },
		            "ebitda_period_type": { "type": ["string","null"] },
		            "enterprise_value": { "type": ["number","null"] },
		            "net_debt": { "type": ["number","null"] },
		            "market_cap": { "type": ["number","null"] },
		            "shares_outstanding": { "type": ["number","null"] },
		            "ev_to_ebitda": { "type": ["number","null"] },
		            "net_rent": { "type": ["number","null"] },
		            "net_rent_currency": { "type": ["string","null"] },
		            "net_rent_period_end": { "type": ["string","null"], "format": "date" },
		            "net_rent_period_type": { "type": ["string","null"] },
		            "noi": { "type": ["number","null"] },
		            "noi_currency": { "type": ["string","null"] },
		            "noi_period_end": { "type": ["string","null"], "format": "date" },
		            "noi_period_type": { "type": ["string","null"] },
		            "affo": { "type": ["number","null"] },
		            "affo_currency": { "type": ["string","null"] },
		            "affo_period_end": { "type": ["string","null"], "format": "date" },
		            "affo_period_type": { "type": ["string","null"] },
		            "ffo": { "type": ["number","null"] },
		            "ffo_currency": { "type": ["string","null"] },
		            "ffo_period_end": { "type": ["string","null"], "format": "date" },
		            "ffo_period_type": { "type": ["string","null"] },
		            "ffo_type": { "type": ["string","null"] },
		            "price": { "type": ["number","null"] },
		            "price_currency": { "type": ["string","null"] },
		            "price_asof": { "type": ["string","null"], "format": "date" },
		            "eps_type": { "type": ["string","null"] },
		            "eps_norm": { "type": ["number","null"] },
		            "eps_norm_years_used": { "type": ["integer","null"] },
		            "eps_norm_years_available": { "type": ["integer","null"] },
		            "eps_history": {
		              "type": ["array","null"],
		              "items": {
		                "type": "object",
		                "additionalProperties": false,
		                "required": ["year","eps","eps_type","eps_currency","period_end"],
		                "properties": {
		                  "year": { "type": ["integer","null"] },
		                  "eps": { "type": ["number","null"] },
		                  "eps_type": { "type": ["string","null"] },
		                  "eps_currency": { "type": ["string","null"] },
		                  "period_end": { "type": ["string","null"], "format": "date" }
		                }
		              }
		            },
		            "eps_floor_policy": { "type": ["string","null"] },
		            "eps_floor_value": { "type": ["number","null"] },
		            "eps_norm_period_end": { "type": ["string","null"], "format": "date" },
		            "pe_longterm": { "type": ["number","null"] },
		            "earnings_yield_longterm": { "type": ["number","null"] },
		            "pe_current": { "type": ["number","null"] },
		            "pe_current_asof": { "type": ["string","null"], "format": "date" },
		            "pb_current": { "type": ["number","null"] },
		            "pb_current_asof": { "type": ["string","null"], "format": "date" },
		            "pe_ttm_holdings": { "type": ["number","null"] },
		            "earnings_yield_ttm_holdings": { "type": ["number","null"] },
		            "holdings_coverage_weight_pct": { "type": ["number","null"] },
		            "holdings_coverage_count": { "type": ["integer","null"] },
		            "holdings_asof": { "type": ["string","null"], "format": "date" },
		            "holdings_weight_method": { "type": ["string","null"] },
		            "pe_method": { "type": ["string","null"] },
		            "pe_horizon": { "type": ["string","null"] },
		            "neg_earnings_handling": { "type": ["string","null"] }
		          }
		        }
		      ]
		    },
		    "missing_fields": {
		      "type": ["array","null"],
		      "items": {
			        "type": "object",
			        "additionalProperties": false,
			        "required": ["field","reason"],
			        "properties": {
			          "field": { "type": "string" },
			          "reason": { "type": "string" }
			        }
			      }
			    },
			    "warnings": {
			      "type": ["array","null"],
			      "items": {
			        "type": "object",
			        "additionalProperties": false,
			        "required": ["message"],
			        "properties": {
			          "message": { "type": "string" }
			        }
			      }
			    }
			  }
			}
			""";

	private final KnowledgeBaseLlmProvider llmProvider;
	private final KnowledgeBaseConfigService configService;
	private final ObjectMapper objectMapper;
	private final Schema extractionSchema;
	private final Map<String, Object> dossierResponseSchema;
	private final Map<String, Object> alternativesResponseSchema;
	private final Map<String, Object> extractionResponseSchema;
	private final LlmPromptPolicy llmPromptPolicy;
	private final Random jitter = new Random();

	public KnowledgeBaseLlmService(KnowledgeBaseLlmProvider llmProvider,
							   KnowledgeBaseConfigService configService,
							   ObjectMapper objectMapper,
							   LlmPromptPolicy llmPromptPolicy) {
		this.llmProvider = llmProvider;
		this.configService = configService;
		this.objectMapper = objectMapper;
		this.extractionSchema = buildExtractionSchema();
		this.dossierResponseSchema = buildDossierResponseSchema(objectMapper);
		this.alternativesResponseSchema = buildAlternativesResponseSchema(objectMapper);
		this.extractionResponseSchema = buildExtractionResponseSchema(objectMapper);
		this.llmPromptPolicy = llmPromptPolicy;
	}

	@Override
	public KnowledgeBaseLlmDossierDraft generateDossier(String isin,
														 String context,
														 List<String> allowedDomains,
														 int maxChars) {
		String normalizedIsin = normalizeIsin(isin);
		String prompt = buildDossierPrompt(normalizedIsin, context, maxChars);
		String validatedPrompt = enforcePromptPolicy(prompt, LlmPromptPurpose.KB_DOSSIER_WEBSEARCH);
		logger.debug("Sending dossier websearch prompt (chars={}).", validatedPrompt.length());
		logger.info("Starting websearch for ISIN(s) {} to create dossier", isin);
		String reasoningEffort = configService.getSnapshot().websearchReasoningEffort();
		KnowledgeBaseLlmResponse response = withRetry(() -> llmProvider.runWebSearch(
				validatedPrompt,
				allowedDomains,
				reasoningEffort,
				"kb_dossier_websearch",
				dossierResponseSchema
		));
		logger.info("Received websearch response for ISIN {} from LLM provider",isin);
		JsonNode root = parseJson(response.output());
		JsonNode contentNode = root.get("contentMd");
		if (contentNode == null || contentNode.isNull() || !contentNode.isTextual()) {
			throw new KnowledgeBaseLlmOutputException("Missing contentMd", INVALID_OUTPUT);
		}
		String contentMd = contentNode.asText().trim();
		if (contentMd.isBlank()) {
			throw new KnowledgeBaseLlmOutputException("Empty contentMd", INVALID_OUTPUT);
		}
		contentMd = stripNullChars(contentMd);
		contentMd = normalizeDossierContent(contentMd);
		if (contentMd.length() > maxChars) {
			throw new KnowledgeBaseLlmOutputException("Dossier exceeds max length", INVALID_OUTPUT);
		}
		String displayName = null;
		JsonNode displayNode = root.get("displayName");
		if (displayNode != null && displayNode.isTextual()) {
			displayName = trimToNull(stripNullChars(displayNode.asText()));
		}
		JsonNode citations = root.get("citations");
		if (citations == null || citations.isNull() || !citations.isArray()) {
			throw new KnowledgeBaseLlmOutputException("Missing citations", INVALID_OUTPUT);
		}
		if (citations.isEmpty()) {
			throw new KnowledgeBaseLlmOutputException("Citations required", INVALID_OUTPUT);
		}
		JsonNode sanitizedCitations = sanitizeJsonNode(citations);
		return new KnowledgeBaseLlmDossierDraft(contentMd, displayName, sanitizedCitations, response.model());
	}

	@Override
	public KnowledgeBaseLlmExtractionDraft extractMetadata(String dossierText) {
		String prompt = buildExtractionPrompt(dossierText);
		String validatedPrompt = enforcePromptPolicy(prompt, LlmPromptPurpose.KB_DOSSIER_EXTRACTION);
		KnowledgeBaseLlmResponse response = withRetry(() -> llmProvider.runJsonPrompt(
				validatedPrompt,
				"kb_extraction_response",
				extractionResponseSchema
		));
		JsonNode root = parseJson(response.output());
		JsonNode payload = unwrapPayload(root);
		JsonNode sanitizedPayload = sanitizeJsonNode(payload);
		String payloadJson = sanitizedPayload == null ? "null" : sanitizedPayload.toString();
		List<Error> errors = extractionSchema.validate(payloadJson, InputFormat.JSON);
		if (!errors.isEmpty()) {
			throw new KnowledgeBaseLlmOutputException("Extraction JSON did not match schema", INVALID_OUTPUT);
		}
		JsonNode normalized = postProcessExtraction(sanitizedPayload, dossierText);
		JsonNode sanitized = sanitizeJsonNode(normalized);
		return new KnowledgeBaseLlmExtractionDraft(sanitized, response.model());
	}

	private String normalizeDossierContent(String contentMd) {
		if (contentMd == null) {
			return null;
		}
		int headerIndex = contentMd.indexOf("## Valuation & profitability");
		if (headerIndex < 0) {
			return insertMissingValuationSection(contentMd);
		}
		int headerEnd = contentMd.indexOf('\n', headerIndex);
		if (headerEnd < 0) {
			return contentMd;
		}
		int nextHeader = contentMd.indexOf("\n## ", headerEnd + 1);
		String before = contentMd.substring(0, headerEnd + 1);
		String sectionBody = nextHeader < 0
				? contentMd.substring(headerEnd + 1)
				: contentMd.substring(headerEnd + 1, nextHeader);
		String after = nextHeader < 0 ? "" : contentMd.substring(nextHeader);
		boolean clearHoldings = shouldClearHoldingsFromContent(contentMd);
		String normalizedBody = buildNormalizedValuation(sectionBody, clearHoldings);
		if (!normalizedBody.endsWith("\n")) {
			normalizedBody += "\n";
		}
		return before + normalizedBody + after;
	}

	private String enforcePromptPolicy(String prompt, LlmPromptPurpose purpose) {
		if (llmPromptPolicy == null) {
			return prompt;
		}
		String validated = llmPromptPolicy.validatePrompt(prompt, purpose);
		if (validated == null) {
			throw new IllegalStateException("LLM prompt rejected by policy: " + purpose);
		}
		return validated;
	}

	private String insertMissingValuationSection(String contentMd) {
		String section = "## Valuation & profitability\n" + buildNormalizedValuation("", false);
		String marker = "\n## Redundancy hints";
		int insertAt = contentMd.indexOf(marker);
		if (insertAt < 0) {
			marker = "\n## Sources";
			insertAt = contentMd.indexOf(marker);
		}
		if (insertAt < 0) {
			StringBuilder builder = new StringBuilder(contentMd);
			if (!contentMd.endsWith("\n")) {
				builder.append("\n");
			}
			return builder.append("\n").append(section).append("\n").toString();
		}
		String before = contentMd.substring(0, insertAt);
		String after = contentMd.substring(insertAt);
		return before + "\n\n" + section + after;
	}

	private String buildNormalizedValuation(String sectionBody, boolean clearHoldings) {
		Map<String, String> values = new HashMap<>();
		List<String> epsLines = new ArrayList<>();
		String[] lines = sectionBody == null ? new String[0] : sectionBody.split("\\R");
		for (String line : lines) {
			Matcher matcher = VALUATION_LINE_RE.matcher(line);
			if (!matcher.matches()) {
				continue;
			}
			String rawKey = matcher.group(1).trim();
			String rawValue = trimToNull(matcher.group(2));
			String key = rawKey.toLowerCase(Locale.ROOT);
			if (rawKey.matches("\\d{4}")) {
				String normalized = stripDisallowedParen(normalizeValue(rawValue), true, false);
				epsLines.add("  - " + rawKey + ": " + normalized);
				continue;
			}
			if (!VALUATION_KEY_SET.contains(key)) {
				continue;
			}
			String normalized = normalizeValueForKey(key, rawValue);
			values.put(key, normalized);
		}
		if (clearHoldings) {
			values.put("pe_ttm_holdings", "unknown");
			values.put("earnings_yield_ttm_holdings", "unknown");
			values.put("holdings_coverage_weight_pct", "unknown");
			values.put("holdings_coverage_count", "unknown");
			values.put("holdings_asof", "unknown");
			values.put("holdings_weight_method", "unknown");
		}
		String epsNorm = values.get("eps_norm");
		String peLong = values.get("pe_longterm");
		String peHorizon = values.get("pe_horizon");
		if ("normalized".equals(peHorizon) && (isUnknown(epsNorm) || isUnknown(peLong))) {
			values.put("pe_horizon", "ttm");
		}
		StringBuilder builder = new StringBuilder();
		for (String key : VALUATION_KEY_ORDER) {
			if ("eps_history".equals(key)) {
				if (!epsLines.isEmpty()) {
					builder.append("- eps_history:\n");
					for (String epsLine : epsLines) {
						builder.append(epsLine).append("\n");
					}
				} else {
					builder.append("- eps_history: unknown\n");
				}
				continue;
			}
			String value = values.getOrDefault(key, "unknown");
			builder.append("- ").append(key).append(": ").append(value).append("\n");
		}
		return builder.toString().trim();
	}

	private String normalizeValueForKey(String key, String value) {
		if ("holdings_asof".equals(key)) {
			return normalizeHoldingsAsOf(value);
		}
		String normalized = normalizeValue(value);
		if ("unknown".equals(normalized)) {
			return normalized;
		}
		normalized = stripDisallowedParen(normalized, allowsPeriodHints(key), requiresPeriodEndDate(key));
		if ("holdings_weight_method".equals(key)) {
			return normalizeEnumValue(normalized, List.of("provider_weighted_avg", "provider_aggregate"));
		}
		if ("pe_method".equals(key)) {
			return normalizeEnumValue(normalized, List.of("ttm", "forward", "provider_weighted_avg", "provider_aggregate"));
		}
		if ("pe_horizon".equals(key)) {
			return normalizeEnumValue(normalized, List.of("ttm", "normalized"));
		}
		if ("neg_earnings_handling".equals(key)) {
			return normalizeEnumValue(normalized, List.of("exclude", "set_null", "aggregate_allows_negative"));
		}
		if (normalized.contains("|") && normalized.toLowerCase(Locale.ROOT).contains("unknown")) {
			return "unknown";
		}
		return normalized;
	}

	private String normalizeEnumValue(String value, List<String> allowed) {
		String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
		for (String option : allowed) {
			if (lower.contains(option)) {
				return option;
			}
		}
		return "unknown";
	}

	private String normalizeValue(String value) {
		String trimmed = trimToNull(value);
		if (trimmed == null) {
			return "unknown";
		}
		String normalized = TRAILING_CITATION_RE.matcher(trimmed).replaceAll("").trim();
		normalized = normalized.replace("→", "->");
		int arrow = normalized.indexOf("->");
		if (arrow >= 0) {
			normalized = normalized.substring(arrow + 2).trim();
		}
		String lower = normalized.toLowerCase(Locale.ROOT);
		if (lower.contains("unknown") || lower.contains("not disclosed") || lower.contains("not provided") || lower.contains("n/a")) {
			return "unknown";
		}
		Matcher rangeMatcher = RANGE_RE.matcher(normalized);
		if (rangeMatcher.matches()) {
			BigDecimal start = parseRangeNumber(rangeMatcher.group(1));
			BigDecimal end = parseRangeNumber(rangeMatcher.group(2));
			if (start != null && end != null) {
				BigDecimal midpoint = start.add(end)
						.divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
				return buildNormalizedNumber(formatRangeNumber(midpoint),
						trimToNull(rangeMatcher.group(3)),
						trimToNull(rangeMatcher.group(4)));
			}
		}
		if (!normalized.isEmpty() && (Character.isDigit(normalized.charAt(0)) || normalized.charAt(0) == '-')) {
			Matcher matcher = LEADING_NUMBER_RE.matcher(normalized);
			if (matcher.matches()) {
				return buildNormalizedNumber(matcher.group(1),
						trimToNull(matcher.group(2)),
						trimToNull(matcher.group(3)));
			}
		}
		return normalized;
	}

	private BigDecimal parseRangeNumber(String raw) {
		String trimmed = trimToNull(raw);
		if (trimmed == null) {
			return null;
		}
		String normalized = trimmed;
		if (normalized.contains(",") && normalized.contains(".")) {
			normalized = normalized.replace(",", "");
		} else if (normalized.contains(",")) {
			normalized = normalized.replace(",", ".");
		}
		try {
			return new BigDecimal(normalized);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private String formatRangeNumber(BigDecimal value) {
		if (value == null) {
			return null;
		}
		return value.stripTrailingZeros().toPlainString();
	}

	private String buildNormalizedNumber(String number, String unit, String paren) {
		if (number == null) {
			return "unknown";
		}
		StringBuilder builder = new StringBuilder(number);
		if (unit != null) {
			builder.append(" ").append(unit);
		}
		if (paren != null) {
			Matcher asOf = ASOF_DATE_RE.matcher(paren);
			if (asOf.find()) {
				builder.append(" (as of ").append(asOf.group(1)).append(")");
			} else {
				builder.append(" ").append(paren);
			}
		}
		return builder.toString().trim();
	}

	private String normalizeHoldingsAsOf(String value) {
		String date = extractIsoDate(value);
		if (date != null) {
			return date;
		}
		Matcher yearMatcher = YEAR_RE.matcher(value == null ? "" : value);
		if (yearMatcher.find()) {
			return yearMatcher.group() + "-01-01";
		}
		return "unknown";
	}

	private String extractIsoDate(String value) {
		if (value == null) {
			return null;
		}
		Matcher matcher = ISO_DATE_RE.matcher(value);
		String lastMatch = null;
		while (matcher.find()) {
			lastMatch = matcher.group();
		}
		return lastMatch;
	}

	private boolean shouldClearHoldingsFromContent(String contentMd) {
		String type = extractInstrumentType(contentMd);
		if (type == null) {
			return false;
		}
		String lower = type.toLowerCase(Locale.ROOT);
		if (lower.contains("etf") || lower.contains("fund") || lower.contains("ucits") || lower.contains("index")) {
			return false;
		}
		return lower.contains("equity")
				|| lower.contains("stock")
				|| lower.contains("share")
				|| lower.contains("reit")
				|| lower.contains("common stock")
				|| lower.contains("ordinary share");
	}

	private String extractInstrumentType(String contentMd) {
		if (contentMd == null) {
			return null;
		}
		Pattern typePattern = Pattern.compile("(?im)^-\\s*Instrument type:\\s*(.+)$");
		Matcher matcher = typePattern.matcher(contentMd);
		if (matcher.find()) {
			return matcher.group(1).trim();
		}
		return null;
	}

	private String stripDisallowedParen(String normalized, boolean allowPeriodHints, boolean requirePeriodEnd) {
		if (normalized == null) {
			return null;
		}
		Matcher matcher = TRAILING_PAREN_RE.matcher(normalized);
		if (!matcher.matches()) {
			return normalized;
		}
		String base = trimToNull(matcher.group(1));
		String content = trimToNull(matcher.group(3));
		if (content == null) {
			return normalized;
		}
		String lower = content.toLowerCase(Locale.ROOT);
		String isoDate = extractIsoDate(content);
		if (lower.contains("as of") && isoDate == null) {
			return base == null ? normalized : base;
		}
		if (isoDate != null) {
			if (allowPeriodHints) {
				String periodHint = resolvePeriodHint(lower);
				if (periodHint != null) {
					return base + " (" + periodHint + ", period end " + isoDate + ")";
				}
				return base + " (period end " + isoDate + ")";
			}
			return base + " (as of " + isoDate + ")";
		}
		if (allowPeriodHints && PERIOD_HINT_RE.matcher(lower).find()) {
			if (requirePeriodEnd) {
				return base == null ? normalized : base;
			}
			String periodHint = resolvePeriodHint(lower);
			if (periodHint != null) {
				return base + " (" + periodHint + ")";
			}
			return normalized;
		}
		return base == null ? normalized : base;
	}

	private boolean allowsPeriodHints(String key) {
		return switch (key) {
			case "revenue",
					"net_income",
					"ebitda",
					"net_rent",
					"noi",
					"affo",
					"ffo",
					"eps_norm" -> true;
			default -> false;
		};
	}

	private boolean requiresPeriodEndDate(String key) {
		return switch (key) {
			case "revenue",
					"net_income",
					"ebitda",
					"net_rent",
					"noi",
					"affo",
					"ffo",
					"eps_norm" -> true;
			default -> false;
		};
	}

	private String resolvePeriodHint(String lower) {
		if (lower == null) {
			return null;
		}
		if (lower.contains("ttm")) {
			return "TTM";
		}
		if (lower.contains("ltm")) {
			return "LTM";
		}
		if (lower.contains("fy")) {
			return "FY";
		}
		return null;
	}

	private boolean isUnknown(String value) {
		return value == null || "unknown".equalsIgnoreCase(value.trim());
	}

	private JsonNode postProcessExtraction(JsonNode payload, String dossierText) {
		if (payload == null || !payload.isObject()) {
			return payload;
		}
		ObjectNode root = ((ObjectNode) payload).deepCopy();
		String researchDate = extractResearchDate(dossierText);
		if (researchDate == null) {
			researchDate = LocalDate.now().toString();
		}
		forceThemeLayerIfNeeded(root);
		boolean singleStock = isSingleStock(root);
		ObjectNode valuation = objectNode(root, "valuation");
		if (valuation != null) {
			applyAsOfFallback(valuation, "price", "price_asof", researchDate);
			applyAsOfFallback(valuation, "pe_current", "pe_current_asof", researchDate);
			applyAsOfFallback(valuation, "pb_current", "pb_current_asof", researchDate);
			if ("normalized".equalsIgnoreCase(textOrNull(valuation, "pe_horizon"))
					&& (isNullOrBlank(valuation.get("eps_norm")) || isNullOrBlank(valuation.get("pe_longterm")))) {
				valuation.put("pe_horizon", "ttm");
			}
			normalizeEpsHistory(valuation, researchDate);
			computePeCurrentIfMissing(valuation, researchDate);
			if (singleStock) {
				if (valuation.get("pe_current") != null && !valuation.get("pe_current").isNull()) {
					valuation.put("pe_method", "ttm");
					valuation.put("pe_horizon", "ttm");
				}
				clearSingleStockHoldingsFields(valuation);
			}
			applyPeriodEndFallback(valuation, "ebitda", "ebitda_period_end", researchDate);
			applyPeriodEndFallback(valuation, "net_rent", "net_rent_period_end", researchDate);
			applyPeriodEndFallback(valuation, "noi", "noi_period_end", researchDate);
			applyPeriodEndFallback(valuation, "affo", "affo_period_end", researchDate);
			applyPeriodEndFallback(valuation, "ffo", "ffo_period_end", researchDate);
			if (allFieldsNull(valuation)) {
				root.set("valuation", objectMapper.nullNode());
			}
		}
		ObjectNode financials = objectNode(root, "financials");
		if (financials != null) {
			applyPeriodEndFallback(financials, "revenue", "revenue_period_end", researchDate);
			applyPeriodEndFallback(financials, "net_income", "net_income_period_end", researchDate);
			if (allFieldsNull(financials)) {
				root.set("financials", objectMapper.nullNode());
			}
		}
		return root;
	}

	private void forceThemeLayerIfNeeded(ObjectNode root) {
		String instrumentType = textOrNull(root, "instrument_type");
		if (instrumentType == null) {
			return;
		}
		String type = instrumentType.toLowerCase(Locale.ROOT);
		if (!(type.contains("etf") || type.contains("fund") || type.contains("etp"))) {
			return;
		}
		String combined = String.join(" ",
				Objects.toString(textOrNull(root, "name"), ""),
				Objects.toString(textOrNull(root, "sub_class"), ""),
				Objects.toString(textOrNull(root, "layer_notes"), "")
		).toLowerCase(Locale.ROOT);
		if (!containsThematicKeyword(combined)) {
			return;
		}
		Integer originalLayer = null;
		JsonNode layerNode = root.get("layer");
		if (layerNode != null && layerNode.isInt()) {
			originalLayer = layerNode.asInt();
		}
		boolean changed = originalLayer == null || originalLayer != 3;
		if (changed) {
			root.put("layer", 3);
		}
		String layerNotes = textOrNull(root, "layer_notes");
		if (layerNotes == null || shouldOverwriteThemeNotes(layerNotes)) {
			root.put("layer_notes", "Thematic ETF");
		}
		if (changed) {
			root.put("layer_notes", appendPostprocessorHint(textOrNull(root, "layer_notes")));
		}
	}

	private boolean containsThematicKeyword(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		for (String keyword : THEMATIC_KEYWORDS) {
			if (value.contains(keyword)) {
				return true;
			}
		}
		return false;
	}

	private boolean shouldOverwriteThemeNotes(String value) {
		if (value == null) {
			return true;
		}
		String normalized = value.toLowerCase(Locale.ROOT);
		return normalized.contains("layer 1")
				|| normalized.contains("layer 2")
				|| normalized.contains("core-plus")
				|| normalized.contains("core plus")
				|| normalized.contains("core");
	}

	private String appendPostprocessorHint(String notes) {
		String hint = "Layer overridden by extraction postprocessor";
		if (notes == null || notes.isBlank()) {
			return hint;
		}
		if (notes.contains(hint)) {
			return notes;
		}
		return notes + " (" + hint + ")";
	}

	private void applyAsOfFallback(ObjectNode node, String valueField, String dateField, String researchDate) {
		if (node == null) {
			return;
		}
		JsonNode value = node.get(valueField);
		if (value == null || value.isNull()) {
			return;
		}
		if (isNullOrBlank(node.get(dateField))) {
			node.put(dateField, researchDate);
		}
	}

	private void applyPeriodEndFallback(ObjectNode node, String valueField, String dateField, String researchDate) {
		if (node == null) {
			return;
		}
		JsonNode value = node.get(valueField);
		if (value == null || value.isNull()) {
			return;
		}
		if (isNullOrBlank(node.get(dateField))) {
			node.put(dateField, researchDate);
		}
	}

	private void normalizeEpsHistory(ObjectNode valuation, String researchDate) {
		ArrayNode epsHistory = arrayNode(valuation, "eps_history");
		if (epsHistory == null) {
			return;
		}
		ArrayNode cleaned = objectMapper.createArrayNode();
		for (JsonNode entry : epsHistory) {
			if (!entry.isObject()) {
				continue;
			}
			ObjectNode epsNode = (ObjectNode) entry;
			JsonNode epsValue = epsNode.get("eps");
			if (epsValue == null || epsValue.isNull()) {
				continue;
			}
			if (isNullOrBlank(epsNode.get("period_end"))) {
				epsNode.put("period_end", researchDate);
			}
			cleaned.add(epsNode);
		}
		valuation.set("eps_history", cleaned);
	}

	private void computePeCurrentIfMissing(ObjectNode valuation, String researchDate) {
		if (valuation == null) {
			return;
		}
		JsonNode peCurrent = valuation.get("pe_current");
		if (peCurrent != null && !peCurrent.isNull()) {
			return;
		}
		JsonNode priceNode = valuation.get("price");
		if (priceNode == null || priceNode.isNull() || !priceNode.isNumber()) {
			return;
		}
		ArrayNode epsHistory = arrayNode(valuation, "eps_history");
		if (epsHistory == null || epsHistory.isEmpty()) {
			return;
		}
		EpsSample sample = pickLatestEps(epsHistory);
		if (sample == null || sample.eps <= 0) {
			return;
		}
		String priceCurrency = textOrNull(valuation, "price_currency");
		if (priceCurrency != null && sample.currency != null && !priceCurrency.equalsIgnoreCase(sample.currency)) {
			Double fxRate = numberOrNull(valuation, "fx_rate_to_eur");
			if (!"EUR".equalsIgnoreCase(priceCurrency) || fxRate == null) {
				return;
			}
			sample = new EpsSample(sample.year, sample.eps * fxRate, "EUR");
		}
		double peValue = priceNode.asDouble() / sample.eps;
		valuation.put("pe_current", peValue);
		if (isNullOrBlank(valuation.get("pe_current_asof"))) {
			valuation.put("pe_current_asof", researchDate);
		}
	}

	private EpsSample pickLatestEps(ArrayNode epsHistory) {
		EpsSample selected = null;
		for (JsonNode entry : epsHistory) {
			if (!entry.isObject()) {
				continue;
			}
			JsonNode epsNode = entry.get("eps");
			JsonNode yearNode = entry.get("year");
			if (epsNode == null || epsNode.isNull() || !epsNode.isNumber() || yearNode == null || yearNode.isNull()) {
				continue;
			}
			int year = yearNode.asInt();
			double eps = epsNode.asDouble();
			String currency = textOrNull(entry, "eps_currency");
			if (selected == null || year > selected.year) {
				selected = new EpsSample(year, eps, currency);
			}
		}
		return selected;
	}

	private void clearSingleStockHoldingsFields(ObjectNode valuation) {
		setNull(valuation, "pe_ttm_holdings");
		setNull(valuation, "earnings_yield_ttm_holdings");
		setNull(valuation, "holdings_coverage_weight_pct");
		setNull(valuation, "holdings_coverage_count");
		setNull(valuation, "holdings_asof");
		setNull(valuation, "holdings_weight_method");
	}

	private boolean isSingleStock(ObjectNode root) {
		if (root == null) {
			return false;
		}
		JsonNode layerNode = root.get("layer");
		if (layerNode != null && layerNode.isInt() && layerNode.asInt() == 4) {
			return true;
		}
		String instrumentType = textOrNull(root, "instrument_type");
		if (instrumentType == null) {
			return false;
		}
		String type = instrumentType.toLowerCase(Locale.ROOT);
		if (type.contains("etf") || type.contains("fund") || type.contains("etp")) {
			return false;
		}
		return type.contains("stock") || type.contains("equity") || type.contains("share") || type.contains("reit");
	}

	private boolean allFieldsNull(ObjectNode node) {
		if (node == null) {
			return true;
		}
		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			JsonNode value = entry.getValue();
			if (!isNullOrBlank(value)) {
				return false;
			}
		}
		return true;
	}

	private boolean isNullOrBlank(JsonNode value) {
		if (value == null || value.isNull()) {
			return true;
		}
		if (value.isTextual()) {
			return trimToNull(value.asText()) == null;
		}
		return false;
	}

	private ObjectNode objectNode(JsonNode node, String field) {
		if (node == null || field == null) {
			return null;
		}
		JsonNode value = node.get(field);
		if (value != null && value.isObject()) {
			return (ObjectNode) value;
		}
		return null;
	}

	private ArrayNode arrayNode(JsonNode node, String field) {
		if (node == null || field == null) {
			return null;
		}
		JsonNode value = node.get(field);
		if (value != null && value.isArray()) {
			return (ArrayNode) value;
		}
		return null;
	}

	private void setNull(ObjectNode node, String field) {
		if (node == null || field == null) {
			return;
		}
		node.set(field, objectMapper.nullNode());
	}

	private Double numberOrNull(JsonNode node, String field) {
		if (node == null || field == null) {
			return null;
		}
		JsonNode value = node.get(field);
		return value != null && value.isNumber() ? value.asDouble() : null;
	}

	private String extractResearchDate(String dossierText) {
		if (dossierText == null) {
			return null;
		}
		Matcher matcher = RESEARCH_DATE_RE.matcher(dossierText);
		if (matcher.find()) {
			return matcher.group(1);
		}
		matcher = DATA_ASOF_RE.matcher(dossierText);
		if (matcher.find()) {
			return matcher.group(1);
		}
		return null;
	}

	private static final class EpsSample {
		private final int year;
		private final double eps;
		private final String currency;

		private EpsSample(int year, double eps, String currency) {
			this.year = year;
			this.eps = eps;
			this.currency = currency;
		}
	}

	@Override
	public KnowledgeBaseLlmAlternativesDraft findAlternatives(String isin, List<String> allowedDomains) {
		String normalizedIsin = normalizeIsin(isin);
		String prompt = buildAlternativesPrompt(normalizedIsin);
		String validatedPrompt = enforcePromptPolicy(prompt, LlmPromptPurpose.KB_ALTERNATIVES_WEBSEARCH);
		String reasoningEffort = configService.getSnapshot().websearchReasoningEffort();
		KnowledgeBaseLlmResponse response = withRetry(() -> llmProvider.runWebSearch(
				validatedPrompt,
				allowedDomains,
				reasoningEffort,
				"kb_alternatives_websearch",
				alternativesResponseSchema
		));
		JsonNode root = parseJson(response.output());
		JsonNode itemsNode = root.isArray() ? root : root.get("items");
		if (itemsNode == null || !itemsNode.isArray()) {
			throw new KnowledgeBaseLlmOutputException("Missing alternatives list", INVALID_OUTPUT);
		}
		List<KnowledgeBaseLlmAlternativeItem> items = new ArrayList<>();
		Set<String> seen = new java.util.HashSet<>();
		for (JsonNode item : itemsNode) {
			String altIsin = textOrNull(item, "isin");
			if (altIsin == null) {
				continue;
			}
			altIsin = altIsin.toUpperCase(Locale.ROOT);
			if (!ISIN_RE.matcher(altIsin).matches()) {
				continue;
			}
			if (altIsin.equals(normalizedIsin)) {
				continue;
			}
			if (!seen.add(altIsin)) {
				continue;
			}
			String rationale = textOrNull(item, "rationale");
			JsonNode citations = item.get("citations");
			if (rationale == null || citations == null || !citations.isArray() || citations.isEmpty()) {
				continue;
			}
			items.add(new KnowledgeBaseLlmAlternativeItem(altIsin, rationale, citations));
		}
		if (items.isEmpty()) {
			throw new KnowledgeBaseLlmOutputException("No valid alternatives returned", INVALID_OUTPUT);
		}
		return new KnowledgeBaseLlmAlternativesDraft(items);
	}

	private KnowledgeBaseLlmResponse withRetry(Supplier<KnowledgeBaseLlmResponse> call) {
		KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
		int maxRetries = Math.max(1, config.maxRetriesPerInstrument());
		int baseBackoff = Math.max(1, config.baseBackoffSeconds());
		int maxBackoff = Math.max(baseBackoff, config.maxBackoffSeconds());
		LlmRequestException lastRetryable = null;
		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				return call.get();
			} catch (LlmRequestException ex) {

				if (!ex.isRetryable()) {
					throw ex;
				}
				lastRetryable = ex;
				if (attempt == maxRetries) {
					logger.error("Max retries reached, stopping");
					break;
				}
				int sleepSeconds = Math.min(maxBackoff, baseBackoff * (1 << (attempt - 1)));
				double jitterFactor = 0.5 + jitter.nextDouble();
				long sleepMillis = Math.max(500L, Math.round(sleepSeconds * 1000.0 * jitterFactor));
				try {
					Thread.sleep(sleepMillis);
				} catch (InterruptedException iex) {
					Thread.currentThread().interrupt();
					throw ex;
				}
			}
		}
		logger.error("Last Error on call retryable: {}, status code: {}",lastRetryable.isRetryable(),lastRetryable.getStatusCode(),lastRetryable);
        throw lastRetryable;
    }

	private String buildDossierPrompt(String isin, String context, int maxChars) {
		String today = LocalDate.now().toString();
		String trimmedContext = context == null ? "" : context.trim();
		StringBuilder prompt = new StringBuilder();
		prompt.append("""
				You are a research assistant for financial instruments (securities). For the provided ISIN, create a dossier in English.
				
				Requirements:
				- Use web research (web_search) and prefer reliable primary sources (issuer/provider site, PRIIPs KID/KIID, factsheet, index provider, exchange/regulator pages).
				- For ETFs/funds, primary sources are required when available. For single stocks/REITs, reputable market-data sources (exchange, regulator, finance portals) are acceptable when issuer pages do not list key metrics.
				- Secondary sources (e.g., justETF/ETF.com) are acceptable when primary sources are unavailable for the instrument type.
				- Do not fail solely because primary sources are unavailable; if the instrument type cannot be confirmed, proceed with secondary sources and mark instrument_type as unknown.
				- For single stocks/REITs, market-data sources are acceptable and required for valuation metrics (price, P/E, P/B, market cap, EPS history) when issuer pages do not list them. Do not omit valuation metrics just because they are not on issuer pages.
				- Provide citations: every key claim (e.g., TER/fees, replication method, index tracked, domicile, distribution policy, SRI) must be backed by a source.
				- Do not invent data. If something cannot be verified, write "unknown" and briefly explain why.
				- Include the research date (%s) and, if available, the “data as of” date for key metrics (factsheet date, holdings date).
				- Only add verified information to the dossier.
				- No financial advice; informational only.
				
				
				Output format:
				Return a single JSON object with:
				- contentMd: string (Markdown dossier)
				- displayName: string (instrument name)
				- citations: JSON array of {id,title,url,publisher,accessed_at} with valid URLs
				
				
				The Markdown dossier must follow:
				# <ISIN> — <Name>
                ## Quick profile (table)
                ## Classification (instrument type, asset class, subclass, suggested layer per Core/Satellite)
                ## Risk (SRI and notes)
                ## Costs & structure (TER, replication, domicile, distribution, currency if relevant)
                ## Exposures (regions, sectors, top holdings/top-10, benchmark/index)
                ## Valuation & profitability (see requirements below)
                ## Redundancy hints (qualitative; do not claim precise correlations without data)
                ## Sources (numbered list)
                
                Additional requirements:
                - Expected Layer definition: 1=Global-Core, 2=Core-Plus, 3=Themes, 4=Single stock.
                - When suggesting a layer, justify it using index breadth, concentration, thematic focus, and region/sector tilt.
                - For exposures, provide region and top-holding weights (percent) and include as-of dates for exposures/holdings when available; if holdings lists are long, provide top-10 weights.
                - In the Exposures section, do not include valuation/template fields (e.g., holdings_weight_method, pe_method, pe_horizon); keep it to regions, sectors, holdings, and benchmark only.
                - If possible, include the Synthetic Risk Indicator (SRI) from the PRIIPs KID.
                - Keep contentMd under %d characters.
                - Single Stocks should always be classified as layer 4= Single Stock. REITs are single stocks unless explicitly a fund/ETF; classify REIT equities as layer 4.
                - To qualify as Layer 1 = Global-Core, an instrument must be an ETF or fund that diversifies across industries and themes worldwide, but not only across individual countries and continents. World wide diversified Core-Umbrella fonds, Core-Multi Asset-ETFs and/or Bond-ETFs are allowed in this layer, too.
                - ETFs/Fonds focussing on instruments from single continents, countries and/or only one country/continent are NOT allowed for Layer 1!
                - To qualify as Layer 2 = Core-Plus, an Instrument must be an ETF or fund that diversifies across industries and themes but tilts into specific regions, continents or countries. Umbrella ETFs, Multi Asset-ETFs and/or Bond-ETFs diversified over specific regions/countries/continents are allowed in this layer, too.
                - If the choice between Layer 1 and 2 is unclear, choose layer 2.
                - Layer 3 = Themes are ETFs and fonds covering specific themes or industries and/or not matching into layer 1 or 2. Also Multi-Asset ETfs and Umbrella fonds are allowed if they cover only specific themes or industries.
                - If the ETF is thematic/sector/industry/commodity focused (e.g., defense, energy, lithium/batteries, clean tech, semiconductors, robotics/AI, healthcare subsectors, commodities), it must be Layer 3. Do not classify such ETFs as Layer 2 even if they are globally diversified.
                - If there is any doubt between Layer 2 and Layer 3 for a thematic ETF, choose Layer 3.
                - Practical check: if the instrument name, benchmark, or description contains a theme/sector/industry keyword (Defense, Energy, Battery, Lithium, Semiconductor, Robotics, AI, Clean, Water, Gold, Oil, Commodity, Cloud, Cyber, Biotech, Healthcare subsector), force Layer 3.
                - For single stocks, collect the raw inputs needed for long-term P/E:
                  EBITDA (currency + TTM/FY label), share price (currency + as-of date), and annual EPS history for the last 3-7 fiscal years (include year, period end, EPS value, and whether EPS is adjusted or reported).
                  If EPS history is incomplete or only a single year is available, still report what you have and explain the gap; do not fabricate long-term P/E.
                  If EBITDA currency is not EUR, include EBITDA converted to EUR and the FX rate used (with date).
                  If available, include market cap and shares outstanding so price can be derived.
                  If enterprise value, net debt, or EV/EBITDA are stated, capture them with currency and as-of/period end.
                - In the Valuation & profitability section, use explicit key/value bullets with numeric values and as-of dates (e.g., "price: 254.20 EUR (as of 2026-01-16)"). Do not defer to sources or say "see source" when a number exists; list the number(s). Avoid ranges and vague qualifiers. If multiple values exist across sources, pick the latest as-of date and report a single numeric value. If only a range is available, compute the midpoint value and note the range in parentheses.
                - The Valuation & profitability section must be a key/value list with one metric per bullet. Use the exact keys (when applicable): price, pe_current, pb_current, dividend_per_share, revenue, net_income, ebitda, enterprise_value, net_debt, ev_to_ebitda, market_cap, shares_outstanding, eps_history (list years), eps_norm, pe_longterm, earnings_yield_longterm, pe_ttm_holdings, earnings_yield_ttm_holdings, holdings_coverage_weight_pct, holdings_coverage_count, holdings_asof, holdings_weight_method, pe_method, pe_horizon, neg_earnings_handling, net_rent, noi, affo, ffo (and ffo_type). If a metric is unavailable, write "unknown" for that key (do not omit the key).
                - The Valuation & profitability section must contain ONLY the template block below. Do not add extra sentences, notes, or metrics in that section.
                - After the last valuation bullet (ffo), immediately start the next section header; do not add notes, explanations, or a second template.
                - For unknown values, write exactly "unknown" with no explanation.
                - Never add parenthetical qualifiers to "unknown" values.
                - Use numeric values as stated in sources (B/M abbreviations are acceptable if that is how the source reports them).
                - If you cite a source for a valuation metric, include its numeric value in the template (do not cite without a number).
                - For enum fields (holdings_weight_method, pe_method, pe_horizon, neg_earnings_handling), use exactly one allowed value or "unknown".
                - Never output pipe-separated option lists in values (e.g., "ttm|normalized"); choose a single value or "unknown".
                - Single stocks (including REIT equities) must provide numeric values for price, pe_current, pb_current, and market_cap when available from market-data sources; do not leave these as unknown if a market-data page is cited.
                - ETFs must provide numeric values for price (NAV/market price), pe_current, pb_current, and holdings_asof when available from issuer sources.
                - REITs: if company materials list net_rent, NOI, AFFO, or FFO, include those numeric values.
                - Single stock data source priority: local listing market data first (e.g., Boerse/StockAnalysis ETR, finance.yahoo.com local listing), then companiesmarketcap.com and justetf.com stock profile, then macrotrends.net for EPS history. Use at least one of these and extract numeric values for price, pe_current, pb_current, market_cap, and eps_history. Do not leave them unknown if present on the cited page.
                - For single stocks/REITs, set pe_method to ttm or forward (not provider_*), set pe_horizon to ttm, and set pe_ttm_holdings, earnings_yield_ttm_holdings, holdings_coverage_weight_pct, holdings_coverage_count, holdings_asof, and holdings_weight_method to unknown.
                - Use pe_horizon=normalized only when eps_norm and pe_longterm are explicitly provided; otherwise set pe_horizon to ttm.
                - For ISINs with DE/FR/IE/GB prefixes, use the local listing and local currency (EUR/GBP) for price, P/E, P/B, and market cap. If local listings are missing or lack the metric, you may use ADR/OTC USD listings as fallback, but convert to EUR and include the FX rate and date. If both local and ADR data are unavailable, set the metric to unknown.
                - EPS history can come from ADR/OTC sources when local listings lack EPS history; keep eps_currency as reported or convert to EUR with the FX rate and date noted in the dossier.
                - Use this exact template for the Valuation & profitability section (copy and fill; keep order):
                  - price: <number> <currency> (as of YYYY-MM-DD)
                  - pe_current: <number> (as of YYYY-MM-DD, ttm|forward if stated)
                  - pb_current: <number> (as of YYYY-MM-DD)
                  - dividend_per_share: <number> <currency> (as of YYYY-MM-DD)
                  - revenue: <number> <currency> (FY/TTM, period end YYYY-MM-DD)
                  - net_income: <number> <currency> (FY/TTM, period end YYYY-MM-DD)
                  - ebitda: <number> <currency> (TTM/FY, period end YYYY-MM-DD)
                  - enterprise_value: <number> <currency> (as of YYYY-MM-DD)
                  - net_debt: <number> <currency> (as of YYYY-MM-DD)
                  - ev_to_ebitda: <number> (as of YYYY-MM-DD)
                  - market_cap: <number> <currency> (as of YYYY-MM-DD)
                  - shares_outstanding: <number> (as of YYYY-MM-DD)
                  - eps_history:
                    - 2024: <number> <currency> (period end YYYY-MM-DD, adjusted|reported)
                    - 2023: <number> <currency> (period end YYYY-MM-DD, adjusted|reported)
                  - eps_norm: <number> <currency> (period end YYYY-MM-DD, years used N)
                  - pe_longterm: <number> (as of YYYY-MM-DD)
                  - earnings_yield_longterm: <number> (decimal, as of YYYY-MM-DD)
                  - pe_ttm_holdings: <number>
                  - earnings_yield_ttm_holdings: <number>
                  - holdings_coverage_weight_pct: <number>
                  - holdings_coverage_count: <number>
                  - holdings_asof: YYYY-MM-DD
                  - holdings_weight_method: <provider_weighted_avg|provider_aggregate|unknown>
                  - pe_method: <ttm|forward|provider_weighted_avg|provider_aggregate|unknown>
                  - pe_horizon: <ttm|normalized|unknown>
                  - neg_earnings_handling: <exclude|set_null|aggregate_allows_negative|unknown>
                  - net_rent: <number> <currency> (TTM/FY, period end YYYY-MM-DD)
                  - noi: <number> <currency> (TTM/FY, period end YYYY-MM-DD)
                  - affo: <number> <currency> (TTM/FY, period end YYYY-MM-DD)
                  - ffo: <number> <currency> (TTM/FY, period end YYYY-MM-DD, ffo_type=FFO I|FFO II if stated)
                - Use ISO dates (YYYY-MM-DD). Never include numeric ranges in the valuation list; if a source provides a range, compute the midpoint and report a single numeric value, optionally noting the range in parentheses.
                - Do not label a metric "unknown" if you provide a numeric value.
                - Only use the keys listed above; do not introduce new keys.
                - Only use dividend_per_share for absolute per-share cash amounts (not yields/percentages). If only yield is available, set dividend_per_share to unknown.
                - If a valuation metric lacks an explicit as-of/period-end date in sources, use the dossier research date as the as-of date.
                - For ETFs, treat NAV or market price per share as the price when provided in issuer/factsheet sources.
                - For EPS history, list each year with the numeric EPS value and period end (e.g., "eps_2024: 9.56 EUR (FY, 2024-09-30, adjusted)").
                - If issuer materials do not provide valuation metrics, use market-data sources (e.g., finance.yahoo.com, stockanalysis.com, macrotrends.net, morningstar.com) to extract the numeric values and dates.
                - For single stocks, always include at least one market-data source (Yahoo Finance, StockAnalysis, Macrotrends, Morningstar) for price/P-E/EPS and list the numeric values with dates.
                - For ETFs, always use the issuer/factsheet pages for P/E, P/B, yield, and as-of dates; provide explicit numbers (no ranges).
                - Always capture the current P/E (TTM/forward as stated) with an as-of date, even when long-term metrics are available.
                - If EBITDA is stated, capture it with currency, period type (TTM/FY), and period end.
                - If long-term P/E or multi-year EPS history is unavailable, also capture price-to-book (P/B) with an as-of date.
                - For real estate/REITs, capture net rent, NOI, AFFO, and FFO (label FFO I if specified), including currency, period type (TTM/FY), and period end/as-of date.
                - For ETFs, include holdings-based TTM P/E computed from historical earnings of holdings:
                  E/P_portfolio = sum(w_i * E/P_i), P/E_ttm_holdings = 1 / E/P_portfolio.
                  Report: P/E_ttm_holdings, earnings_yield_ttm_holdings, holdings coverage (count and weight pct), holdings as-of date, holdings weight method (provider_weighted_avg vs provider_aggregate).
                - For both, include method flags: pe_method {ttm, forward, provider_weighted_avg, provider_aggregate}, pe_horizon {ttm, normalized}, neg_earnings_handling {exclude, set_null, aggregate_allows_negative}.
                
                Create dossier for:
                - ISIN: %s
                """.formatted(today, maxChars, isin));
		if (!trimmedContext.isBlank()) {
			prompt.append("\nAdditional context:\n").append(trimmedContext);
		}
		return prompt.toString();
	}

	private String buildAlternativesPrompt(String isin) {
		String today = LocalDate.now().toString();
		return """
				You are a research assistant for financial instruments (securities). For the provided ISIN, identify suitable alternative instruments (ETFs or equities) with similar exposure or purpose.

				Requirements:
				- Use web research (web_search) and prefer primary sources (issuer/provider site, PRIIPs KID/KIID, factsheet, index provider, exchange/regulator pages).
				- If primary sources are not available, you may use reliable secondary sources (e.g., justETF, ETF.com, exchange/market-data portals) but still provide citations.
				- Provide citations: every alternative must include sources backing the rationale.
				- Do not invent data. If something cannot be verified, exclude the alternative.
				- Do not return the original ISIN as an alternative.
				- Return 3 to 6 alternatives when possible; if fewer are available, return the best available alternatives instead of an empty list.
				- Include the research date (%s).

				Output format:
				Return a single JSON object:
				{
				  "items": [
				    {
				      "isin": "DE0000000000",
				      "rationale": "Short rationale explaining similarity.",
				      "citations": [ { "id": "...", "title": "...", "url": "...", "publisher": "...", "accessed_at": "YYYY-MM-DD" } ]
				    }
				  ]
				}

				ISIN:
				- %s
				""".formatted(today, isin);
	}

	private String buildExtractionPrompt(String dossierText) {
		String contentMd = dossierText == null ? "" : dossierText;
		return """
				You are a strict information extraction engine.

				Task:
				Extract the required fields from the provided instrument dossier and return a single JSON object.

				Rules:
				- Output JSON only. No Markdown, no code fences, no explanation, no extra keys.
				- Include every key listed below, even when the value is null. Do not omit required keys.
				- Do not guess. Only use information explicitly present in the dossier.
				- Use null for unknown/missing values (not the string "unknown").
				- Keep string values short and literal (e.g., "ETF", "Equity", "Developed Markets", ...).
				- layer must be an integer 1..5 (5 = Unclassified).
				- risk.summary_risk_indicator.value must be an integer 1..7.
				- If the dossier states a risk between two numbers choose the higher value.
				- etf.ongoing_charges_pct is a number in percent units (e.g., 0.20 for 0.20%%). Strip "%%" if present.
				- If both ETF fields are null, set "etf" to null. If the risk indicator is null, set "risk" to null.
				- regions is an array of { name, weight_pct } with weights in percent (0..100). Use null if unavailable.
				- top_holdings is an array of { name, weight_pct } with weights in percent (0..100). Use null if unavailable.
				- If a numeric value is given as a range (e.g., 19-20), use the midpoint and add a warning message (e.g., "Used midpoint for range: pe_current 19-20").
				- If a range spans multiple dates, use the latest as-of date for *_asof and mention that in warnings.
				- valuation.pe_method must be one of: ttm, forward, provider_weighted_avg, provider_aggregate.
				- valuation.pe_horizon must be one of: ttm, normalized.
				- valuation.neg_earnings_handling must be one of: exclude, set_null, aggregate_allows_negative.
				- earnings_yield fields are decimals (e.g., 0.05 for 5%%).
				- For EBITDA, include ebitda_currency, and if currency is not EUR, include ebitda_eur plus fx_rate_to_eur (same as-of date if given).
				- For net rent, NOI, AFFO, and FFO, include currency and period metadata (period type + end date).
				- For current P/E, use pe_current (TTM/forward as stated) with an as-of date if given.
				- If pe_current is present but no explicit as-of date is stated, use the dossier research date or data date as pe_current_asof.
				- If price is present but no explicit as-of date is stated, use the dossier research date or data date as price_asof.
				- If pb_current is present but no explicit as-of date is stated, use the dossier research date or data date as pb_current_asof.
				- If instrument_type indicates a single stock (equity/REIT) and etf is null: set pe_method to "ttm" when pe_current is present unless explicitly stated otherwise; set pe_horizon to "ttm" when pe_current is present unless explicitly stated otherwise; set pe_ttm_holdings, earnings_yield_ttm_holdings, holdings_coverage_weight_pct, holdings_coverage_count, holdings_asof, and holdings_weight_method to null.
				- If pe_method or pe_horizon is written as multiple options (e.g., "ttm|normalized"), choose "ttm".
				- For current price-to-book, use pb_current with an as-of date if given.
				- If EBITDA is stated, populate ebitda, ebitda_currency, ebitda_period_type, and ebitda_period_end.
				- If EBITDA/revenue/net income/eps_history entries lack a period end date, use the dossier research date as the period end date.
				- If EBITDA/revenue/net income/eps_history entries lack a period end date, use the dossier research date as the period end date.
				- If revenue or net income are stated, populate financials.revenue/net_income with currency, period type (TTM/FY), period end, and EUR conversion + fx_rate_to_eur when not in EUR.
				- If a dividend per share is stated, populate financials.dividend_per_share, dividend_currency, and dividend_asof.
				- valuation.eps_history is an array of yearly EPS entries (3-7 years if available). Include year, eps, eps_type, eps_currency, period_end.
				- If EPS history and price are available, you may compute eps_norm and pe_longterm; otherwise leave them null.
				- If financials is present but all fields are null, set financials to null.
				- If valuation is present but all fields are null, set valuation to null.
				- If a field is missing, keep it null and add an entry to missing_fields.
				- Do not include sources or citations in the output.

				Return JSON with exactly these keys:
				- isin
				- name
				- instrument_type
				- asset_class
				- sub_class
				- layer
				- layer_notes
				- etf: { ongoing_charges_pct, benchmark_index } | null
				- risk: { summary_risk_indicator: { value } } | null
				- regions
				- top_holdings
				- financials: {
				    revenue, revenue_currency, revenue_eur, revenue_period_end, revenue_period_type,
				    net_income, net_income_currency, net_income_eur, net_income_period_end, net_income_period_type,
				    dividend_per_share, dividend_currency, dividend_asof, fx_rate_to_eur
				  } | null
				- valuation: {
				    ebitda, ebitda_currency, ebitda_eur, fx_rate_to_eur,
				    ebitda_period_end, ebitda_period_type,
				    enterprise_value, net_debt, market_cap, shares_outstanding, ev_to_ebitda,
				    net_rent, net_rent_currency, net_rent_period_end, net_rent_period_type,
				    noi, noi_currency, noi_period_end, noi_period_type,
				    affo, affo_currency, affo_period_end, affo_period_type,
				    ffo, ffo_currency, ffo_period_end, ffo_period_type, ffo_type,
				    price, price_currency, price_asof,
				    eps_type, eps_norm, eps_norm_years_used, eps_norm_years_available, eps_history,
				    eps_floor_policy, eps_floor_value, eps_norm_period_end,
				    pe_longterm, earnings_yield_longterm, pe_current, pe_current_asof, pb_current, pb_current_asof,
				    pe_ttm_holdings, earnings_yield_ttm_holdings,
				    holdings_coverage_weight_pct, holdings_coverage_count, holdings_asof, holdings_weight_method,
				    pe_method, pe_horizon, neg_earnings_handling
				  } | null
				- missing_fields: [{ field, reason }]
				- warnings: [{ message }]

				---BEGIN DOSSIER MARKDOWN---
				%s
				---END DOSSIER MARKDOWN---
				""".formatted(contentMd);
	}

	private JsonNode parseJson(String raw) {
		try {
			return objectMapper.readTree(raw);
		} catch (Exception ex) {
			logger.error("Could not parse raw response {}",raw,ex);
			throw new KnowledgeBaseLlmOutputException("LLM output is not valid JSON", INVALID_OUTPUT);
		}
	}

	private JsonNode unwrapPayload(JsonNode root) {
		if (root == null) {
			return null;
		}
		if (root.isObject()) {
			JsonNode payload = root.get("payload");
			if (payload != null && payload.isObject()) {
				return payload;
			}
			JsonNode result = root.get("result");
			if (result != null && result.isObject()) {
				return result;
			}
		}
		return root;
	}

	private Schema buildExtractionSchema() {
		try {
			SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
			return registry.getSchema(EXTRACTION_SCHEMA_JSON, InputFormat.JSON);
		} catch (Exception ex) {
			logger.error("Failed to load extraction JSON schema", ex);
			throw new IllegalStateException("Failed to load extraction schema");
		}
	}

	private Map<String, Object> buildExtractionResponseSchema(ObjectMapper mapper) {
		return readSchema(mapper, EXTRACTION_SCHEMA_JSON, "KB extraction response");
	}

	private Map<String, Object> buildDossierResponseSchema(ObjectMapper mapper) {
		String schema = """
				{
				  "type": "object",
				  "additionalProperties": false,
				  "required": ["contentMd","displayName","citations"],
				  "properties": {
				    "contentMd": { "type": "string" },
				    "displayName": { "type": ["string","null"] },
				    "citations": {
				      "type": "array",
				      "minItems": 1,
				      "items": {
				        "type": "object",
				        "additionalProperties": false,
				        "required": ["id","title","url","publisher","accessed_at"],
				        "properties": {
				          "id": { "type": "string" },
				          "title": { "type": "string" },
				          "url": { "type": "string" },
				          "publisher": { "type": "string" },
				          "accessed_at": { "type": "string", "format": "date" }
				        }
				      }
				    }
				  }
				}
				""";
		return readSchema(mapper, schema, "KB dossier response");
	}

	private Map<String, Object> buildAlternativesResponseSchema(ObjectMapper mapper) {
		String schema = """
				{
				  "type": "object",
				  "additionalProperties": false,
				  "required": ["items"],
				  "properties": {
				    "items": {
				      "type": "array",
				      "minItems": 1,
				      "items": {
				        "type": "object",
				        "additionalProperties": false,
				        "required": ["isin","rationale","citations"],
				        "properties": {
				          "isin": { "type": "string" },
				          "rationale": { "type": "string" },
				          "citations": {
				            "type": "array",
				            "minItems": 1,
				            "items": {
				              "type": "object",
				              "additionalProperties": false,
				              "required": ["id","title","url","publisher","accessed_at"],
				              "properties": {
				                "id": { "type": "string" },
				                "title": { "type": "string" },
				                "url": { "type": "string" },
				                "publisher": { "type": "string" },
				                "accessed_at": { "type": "string", "format": "date" }
				              }
				            }
				          }
				        }
				      }
				    }
				  }
				}
				""";
		return readSchema(mapper, schema, "KB alternatives response");
	}

	private Map<String, Object> readSchema(ObjectMapper mapper, String schema, String label) {
		try {
			return mapper.readValue(schema, new TypeReference<Map<String, Object>>() {});
		} catch (Exception ex) {
			logger.error("Failed to load {} JSON schema", label, ex);
			throw new IllegalStateException("Failed to load " + label + " schema");
		}
	}

	private String normalizeIsin(String isin) {
		String value = isin == null ? "" : isin.trim().toUpperCase(Locale.ROOT);
		if (!ISIN_RE.matcher(value).matches()) {
			throw new KnowledgeBaseLlmOutputException("Invalid ISIN: " + value, INVALID_OUTPUT);
		}
		return value;
	}

	private String textOrNull(JsonNode node, String field) {
		if (node == null || field == null) {
			return null;
		}
		JsonNode value = node.get(field);
		if (value == null || value.isNull() || !value.isValueNode()) {
			return null;
		}
		return trimToNull(value.asText());
	}

	private JsonNode sanitizeJsonNode(JsonNode node) {
		if (node == null) {
			return null;
		}
		if (node.isObject()) {
			ObjectNode sanitized = objectMapper.createObjectNode();
			ObjectNode obj = (ObjectNode) node;
			for (Map.Entry<String, JsonNode> entry : obj.properties()) {
				JsonNode value = sanitizeJsonNode(entry.getValue());
				sanitized.set(entry.getKey(), value == null ? objectMapper.nullNode() : value);
			}
			return sanitized;
		}
		if (node.isArray()) {
			ArrayNode sanitized = objectMapper.createArrayNode();
			for (JsonNode item : node) {
				JsonNode value = sanitizeJsonNode(item);
				sanitized.add(value == null ? objectMapper.nullNode() : value);
			}
			return sanitized;
		}
		if (node.isTextual()) {
			return objectMapper.getNodeFactory().textNode(stripNullChars(node.asText()));
		}
		return node;
	}

	private String stripNullChars(String value) {
		if (value == null) {
			return null;
		}
		return value.replace("\u0000", "");
	}

	private String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
