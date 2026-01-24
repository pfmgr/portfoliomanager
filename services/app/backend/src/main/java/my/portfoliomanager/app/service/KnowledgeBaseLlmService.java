package my.portfoliomanager.app.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Service
public class KnowledgeBaseLlmService implements KnowledgeBaseLlmClient {
	private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseLlmService.class);
	private static final Pattern ISIN_RE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");
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
	private final Random jitter = new Random();

	public KnowledgeBaseLlmService(KnowledgeBaseLlmProvider llmProvider,
								   KnowledgeBaseConfigService configService,
								   ObjectMapper objectMapper) {
		this.llmProvider = llmProvider;
		this.configService = configService;
		this.objectMapper = objectMapper;
		this.extractionSchema = buildExtractionSchema();
		this.dossierResponseSchema = buildDossierResponseSchema(objectMapper);
		this.alternativesResponseSchema = buildAlternativesResponseSchema(objectMapper);
		this.extractionResponseSchema = buildExtractionResponseSchema(objectMapper);
	}

	@Override
	public KnowledgeBaseLlmDossierDraft generateDossier(String isin,
														 String context,
														 List<String> allowedDomains,
														 int maxChars) {
		String normalizedIsin = normalizeIsin(isin);
		String prompt = buildDossierPrompt(normalizedIsin, context, maxChars);
		logger.debug("Sending prompt to LLM: {}",prompt);
		logger.info("Starting websearch for ISIN(s) {} to create dossier", isin);
		KnowledgeBaseLlmResponse response = withRetry(() -> llmProvider.runWebSearch(
				prompt,
				allowedDomains,
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
		if (contentMd.length() > maxChars) {
			throw new KnowledgeBaseLlmOutputException("Dossier exceeds max length", INVALID_OUTPUT);
		}
		String displayName = null;
		JsonNode displayNode = root.get("displayName");
		if (displayNode != null && displayNode.isTextual()) {
			displayName = trimToNull(displayNode.asText());
		}
		JsonNode citations = root.get("citations");
		if (citations == null || citations.isNull() || !citations.isArray()) {
			throw new KnowledgeBaseLlmOutputException("Missing citations", INVALID_OUTPUT);
		}
		if (citations.isEmpty()) {
			throw new KnowledgeBaseLlmOutputException("Citations required", INVALID_OUTPUT);
		}
		return new KnowledgeBaseLlmDossierDraft(contentMd, displayName, citations, response.model());
	}

	@Override
	public KnowledgeBaseLlmExtractionDraft extractMetadata(String dossierText) {
		String prompt = buildExtractionPrompt(dossierText);
		KnowledgeBaseLlmResponse response = withRetry(() -> llmProvider.runJsonPrompt(
				prompt,
				"kb_extraction_response",
				extractionResponseSchema
		));
		JsonNode root = parseJson(response.output());
		JsonNode payload = unwrapPayload(root);
		String payloadJson = payload == null ? "null" : payload.toString();
		List<Error> errors = extractionSchema.validate(payloadJson, InputFormat.JSON);
		if (!errors.isEmpty()) {
			throw new KnowledgeBaseLlmOutputException("Extraction JSON did not match schema", INVALID_OUTPUT);
		}
		return new KnowledgeBaseLlmExtractionDraft(payload, response.model());
	}

	@Override
	public KnowledgeBaseLlmAlternativesDraft findAlternatives(String isin, List<String> allowedDomains) {
		String normalizedIsin = normalizeIsin(isin);
		String prompt = buildAlternativesPrompt(normalizedIsin);
		KnowledgeBaseLlmResponse response = withRetry(() -> llmProvider.runWebSearch(
				prompt,
				allowedDomains,
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
                - Use web research (web_search) to find reliable primary sources (issuer/provider site, PRIIPs KID/KIID, factsheet, index provider, exchange/regulator pages; optionally justETF or similar as a secondary source).
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
                - Expected Layer definition: 1=Global-Core, 2=Core-Plus, 3=Satellite, 4=Single stock.
                - When suggesting a layer, justify it using index breadth, concentration, thematic focus, and region/sector tilt.
                - If possible, include the Synthetic Risk Indicator (SRI) from the PRIIPs KID.
                - Keep contentMd under %d characters.
                - Single Stocks should always be classified as layer 4= Single Stock.
                - To qualify as Layer 1 = Global-Core, an instrument must be an ETF or fund that diversifies across industries and themes worldwide, but not only across individual countries and continents. World wide diversified Core-Umbrella fonds, Core-Multi Asset-ETFs and/or Bond-ETFs are allowed in this layer, too.
                - ETFs/Fonds focussing on instruments from single continents, countries and/or only one country/continent are NOT allowed for Layer 1!
                - To qualify as Layer 2 = Core-Plus, an Instrument must be an ETF or fund that diversifies across industries and themes but tilts into specific regions, continents or countries. Umbrella ETFs, Multi Asset-ETFs and/or Bond-ETFs diversified over specific regions/countries/continents are allowed in this layer, too.
                - If the choice between Layer 1 and 2 is unclear, choose layer 2.
                - Layer 3 = Themes are ETFs and fonds covering specific themes or industries and/or not matching into layer 1 or 2. Also Multi-Asset ETfs and Umbrella fonds are allowed if they cover only specific themes or industries.
                - For single stocks, collect the raw inputs needed for long-term P/E:
                  EBITDA (currency + TTM/FY label), share price (currency + as-of date), and annual EPS history for the last 3-7 fiscal years (include year, period end, EPS value, and whether EPS is adjusted or reported).
                  If EPS history is incomplete or only a single year is available, still report what you have and explain the gap; do not fabricate long-term P/E.
                  If EBITDA currency is not EUR, include EBITDA converted to EUR and the FX rate used (with date).
                  If available, include market cap and shares outstanding so price can be derived.
                - Always capture the current P/E (TTM/forward as stated) with an as-of date, even when long-term metrics are available.
                - If EBITDA is stated, capture it with currency, period type (TTM/FY), and period end.
                - If long-term P/E or multi-year EPS history is unavailable, also capture price-to-book (P/B) with an as-of date.
                - For real estate/REITs, capture net rent, NOI, AFFO, and FFO (label FFO I if specified), including currency, period type (TTM/FY), and period end/as-of date.
                - For ETFs, include holdings-based TTM P/E computed from historical earnings of holdings:
                  E/P_portfolio = sum(w_i * E/P_i), P/E_ttm_holdings = 1 / E/P_portfolio.
                  Report: P/E_ttm_holdings, earnings_yield_ttm_holdings, holdings coverage (count and weight pct), holdings as-of date.
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
				- Use web research (web_search) to find reliable primary sources (issuer/provider site, PRIIPs KID/KIID, factsheet, index provider, exchange/regulator pages).
				- Provide citations: every alternative must include sources backing the rationale.
				- Do not invent data. If something cannot be verified, exclude the alternative.
				- Do not return the original ISIN as an alternative.
				- Return 3 to 6 alternatives when possible.
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
				- For current price-to-book, use pb_current with an as-of date if given.
				- If EBITDA is stated, populate ebitda, ebitda_currency, ebitda_period_type, and ebitda_period_end.
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

	private String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
