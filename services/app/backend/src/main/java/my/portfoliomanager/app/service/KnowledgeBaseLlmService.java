package my.portfoliomanager.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
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
			  "required": ["isin","name","instrument_type","asset_class","sub_class","layer","layer_notes","etf","risk","regions","top_holdings","missing_fields","warnings"],
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
	private final JsonSchema extractionSchema;
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
		this.extractionSchema = buildExtractionSchema(objectMapper);
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
		Set<ValidationMessage> errors = extractionSchema.validate(payload);
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
				- If a field is missing, keep it null and add an entry to missing_fields.

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

	private JsonSchema buildExtractionSchema(ObjectMapper mapper) {
		try {
			JsonNode schemaNode = mapper.readTree(EXTRACTION_SCHEMA_JSON);
			return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
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
