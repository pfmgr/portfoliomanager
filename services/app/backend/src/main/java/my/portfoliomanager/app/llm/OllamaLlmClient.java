package my.portfoliomanager.app.llm;

import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.service.KnowledgeBaseConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OllamaLlmClient implements LlmClient, KnowledgeBaseLlmProvider {
	private static final Logger logger = LoggerFactory.getLogger(OllamaLlmClient.class);
	private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofMinutes(5);
	private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5);
	private static final Duration DEFAULT_SEARCH_TIMEOUT = Duration.ofSeconds(15);
	private static final int DEFAULT_MAX_RESULTS = 8;
	private static final int DEFAULT_MAX_SNIPPET_CHARS = 1200;
	private static final Pattern ISIN_RE = Pattern.compile("\\b[A-Z]{2}[A-Z0-9]{9}[0-9]\\b");
	private static final Pattern NAME_HINT_RE = Pattern.compile("(?m)^-\\s*([A-Z0-9]{12})\\s*:\\s*(.+)$");
	private static final String JSON_ONLY_SYSTEM = "Respond in JSON only. Do not wrap in Markdown code fences.";

	private final RestClient llmClient;
	private final RestClient searxngClient;
	private final ObjectMapper objectMapper;
	private final KnowledgeBaseConfigService configService;
	private final String model;
	private final int maxResults;
	private final int maxSnippetChars;

	public OllamaLlmClient(AppProperties properties,
						  ObjectMapper objectMapper,
						  KnowledgeBaseConfigService configService) {
		this.objectMapper = objectMapper;
		this.configService = configService;
		String baseUrl = properties == null || properties.llm() == null || properties.llm().openai() == null
				? null
				: properties.llm().openai().baseUrl();
		if (baseUrl != null && baseUrl.trim().equalsIgnoreCase("https://api.openai.com/v1")) {
			baseUrl = null;
		}
		String modelValue = properties == null || properties.llm() == null || properties.llm().openai() == null
				? null
				: properties.llm().openai().model();
		Integer connectTimeoutSeconds = properties == null || properties.llm() == null || properties.llm().openai() == null
				? null
				: properties.llm().openai().connectTimeoutSeconds();
		Integer readTimeoutSeconds = properties == null || properties.llm() == null || properties.llm().openai() == null
				? null
				: properties.llm().openai().readTimeoutSeconds();
		this.model = resolveModel(modelValue);
		Duration connectTimeout = connectTimeoutSeconds == null
				? DEFAULT_CONNECT_TIMEOUT
				: Duration.ofSeconds(Math.max(10, connectTimeoutSeconds));
		Duration readTimeout = readTimeoutSeconds == null
				? DEFAULT_READ_TIMEOUT
				: Duration.ofSeconds(Math.max(30, readTimeoutSeconds));
		String normalizedBaseUrl = normalizeOllamaBaseUrl(baseUrl);
		this.llmClient = buildRestClient(normalizedBaseUrl, null, connectTimeout, readTimeout, "http://ollama:11434/v1");
		AppProperties.Kb.Search search = properties == null || properties.kb() == null ? null : properties.kb().search();
		String searchBaseUrl = search == null || search.searxng() == null ? null : search.searxng().baseUrl();
		Integer searchTimeoutSeconds = search == null ? null : search.timeoutSeconds();
		Duration searchTimeout = searchTimeoutSeconds == null
				? DEFAULT_SEARCH_TIMEOUT
				: Duration.ofSeconds(Math.max(5, searchTimeoutSeconds));
		this.searxngClient = buildRestClient(searchBaseUrl, null, searchTimeout, searchTimeout, "http://searxng:8080");
		this.maxResults = search == null || search.maxResults() == null
				? DEFAULT_MAX_RESULTS
				: Math.max(1, search.maxResults());
		this.maxSnippetChars = search == null || search.maxSnippetChars() == null
				? DEFAULT_MAX_SNIPPET_CHARS
				: Math.max(200, search.maxSnippetChars());
		logger.info("LLM client enabled (provider=ollama, model={}, search=searxng).", this.model);
	}

	@Override
	public LlmSuggestion suggestReclassification(String context) {
		String prompt = "Provide reclassification suggestion only.";
		String output = callChatCompletions(prompt, context);
		return new LlmSuggestion(output, "ollama(model=" + model + ")");
	}

	@Override
	public LlmSuggestion suggestSavingPlanProposal(String context) {
		String prompt = "Provide a short savingPlan proposal narrative/explanation in plain text only.";
		String output = callChatCompletions(prompt, context);
		return new LlmSuggestion(output, "ollama(model=" + model + ")");
	}

	@Override
	public LlmSuggestion createInstrumentDossierViaWebSearch(String context) {
		List<String> allowedDomains = resolveAllowedDomains(null);
		return createInstrumentDossierViaWebSearch(context, null, null, null, allowedDomains);
	}

	@Override
	public LlmSuggestion createInstrumentDossierViaWebSearch(String context, String schemaName, Map<String, Object> schema) {
		List<String> allowedDomains = resolveAllowedDomains(null);
		return createInstrumentDossierViaWebSearch(context, schemaName, schema, null, allowedDomains);
	}

	@Override
	public LlmSuggestion createInstrumentDossierViaWebSearch(String context, String reasoningEffort) {
		List<String> allowedDomains = resolveAllowedDomains(null);
		return createInstrumentDossierViaWebSearch(context, null, null, reasoningEffort, allowedDomains);
	}

	@Override
	public LlmSuggestion createInstrumentDossierViaWebSearch(String context,
									 String schemaName,
									 Map<String, Object> schema,
									 String reasoningEffort) {
		List<String> allowedDomains = resolveAllowedDomains(null);
		return createInstrumentDossierViaWebSearch(context, schemaName, schema, reasoningEffort, allowedDomains);
	}

	@Override
	public LlmSuggestion extractInstrumentDossierFields(String context) {
		KnowledgeBaseLlmResponse response = runJsonPrompt(context);
		return new LlmSuggestion(response.output(), "ollama(model=" + model + ")");
	}

	@Override
	public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains) {
		return runWebSearch(prompt, allowedDomains, null, null, null);
	}

	@Override
	public KnowledgeBaseLlmResponse runWebSearch(String prompt,
								 List<String> allowedDomains,
								 String reasoningEffort,
								 String schemaName,
								 Map<String, Object> schema) {
		List<String> resolvedDomains = resolveAllowedDomains(allowedDomains);
		String enrichedPrompt = enrichPromptWithSources(prompt, resolvedDomains, reasoningEffort);
		return runJsonPrompt(enrichedPrompt, schemaName, schema);
	}

	@Override
	public KnowledgeBaseLlmResponse runJsonPrompt(String prompt) {
		return runJsonPrompt(prompt, null, null);
	}

	@Override
	public KnowledgeBaseLlmResponse runJsonPrompt(String prompt, String schemaName, Map<String, Object> schema) {
		String system = JSON_ONLY_SYSTEM;
		if (schemaName != null && schema != null) {
			system = system + " Output must conform to the JSON schema '" + schemaName + "'.";
			String schemaJson = serializeSchema(schema);
			if (schemaJson != null && !schemaJson.isBlank()) {
				system = system + " Schema: " + schemaJson;
			}
		}
		String output = callChatCompletions(system, prompt, true);
		String validated = ensureJsonOutput(output, system, prompt);
		return new KnowledgeBaseLlmResponse(validated, model);
	}

	private LlmSuggestion createInstrumentDossierViaWebSearch(String context,
										   String schemaName,
										   Map<String, Object> schema,
										   String reasoningEffort,
										   List<String> allowedDomains) {
		try {
			KnowledgeBaseLlmResponse response = runWebSearch(context, allowedDomains, reasoningEffort, schemaName, schema);
			return new LlmSuggestion(response.output(), response.model());
		} catch (LlmRequestException ex) {
			return new LlmSuggestion("", "ollama(model=" + model + "): " + ex.getMessage());
		}
	}

	private RestClient buildRestClient(String baseUrl,
								 String apiKey,
								 Duration connectTimeout,
								 Duration readTimeout,
								 String defaultBaseUrl) {
		String resolvedBaseUrl = baseUrl == null || baseUrl.isBlank() ? defaultBaseUrl : baseUrl;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeout);
		requestFactory.setReadTimeout(readTimeout);
		RestClient.Builder builder = RestClient.builder()
				.baseUrl(resolvedBaseUrl)
				.requestFactory(requestFactory)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
		if (apiKey != null && !apiKey.isBlank()) {
			builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
		}
		return builder.build();
	}

	private String callChatCompletions(String systemPrompt, String userPrompt) {
		return callChatCompletions(systemPrompt, userPrompt, false);
	}

	private String callChatCompletions(String systemPrompt, String userPrompt, boolean jsonMode) {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("model", model);
		if (jsonMode) {
			request.put("response_format", Map.of("type", "json_object"));
		}
		request.put("messages", List.of(
				Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt),
				Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt)
		));
		Map<?, ?> response;
		try {
			response = llmClient.post().uri("/chat/completions").body(request).retrieve().body(Map.class);
		} catch (RestClientResponseException ex) {
			logResponseError(ex, request);
			throw new LlmRequestException(safeMessage(ex), ex.getStatusCode().value(), isRetryable(ex), ex);
		} catch (ResourceAccessException ex) {
			logRequestError(ex, request);
			throw new LlmRequestException(safeMessage(ex), null, true, ex);
		} catch (Exception ex) {
			logRequestError(ex, request);
			throw new LlmRequestException(safeMessage(ex), null, false, ex);
		}
		String content = extractChatContent(response);
		if (content == null || content.isBlank()) {
			throw new LlmRequestException("No content in LLM response", null, false, null);
		}
		return content;
	}

	private String extractChatContent(Map<?, ?> response) {
		if (response == null) {
			return null;
		}
		Object choices = response.get("choices");
		if (!(choices instanceof List<?> list) || list.isEmpty()) {
			return null;
		}
		Object first = list.getFirst();
		if (!(first instanceof Map<?, ?> map)) {
			return null;
		}
		Object message = map.get("message");
		if (!(message instanceof Map<?, ?> msgMap)) {
			return null;
		}
		Object content = msgMap.get("content");
		return content == null ? null : content.toString();
	}

	private String enrichPromptWithSources(String prompt, List<String> allowedDomains, String reasoningEffort) {
		Map<String, String> nameHints = extractNameHints(prompt);
		List<String> isins = extractIsins(prompt);
		Map<String, List<SearchSource>> sourcesByIsin = new LinkedHashMap<>();
		int totalSources = 0;
		if (isins.isEmpty()) {
			logger.warn("No ISINs detected in prompt; proceeding without search context.");
		} else {
			for (String isin : isins) {
				String query = buildSearchQuery(isin, nameHints.get(isin));
				List<SearchSource> sources = fetchSources(query, allowedDomains, isin);
				sourcesByIsin.put(isin, sources);
				totalSources += sources.size();
			}
		}
		if (!isins.isEmpty() && totalSources == 0) {
			throw new LlmRequestException("No sources found via SearxNG", null, false, null);
		}
		StringBuilder builder = new StringBuilder();
		builder.append("You do not have a live web_search tool. Ignore any instruction to use web_search. ")
				.append("Use only the sources listed below. Do not invent sources. ")
				.append("When providing citations, reuse the source id/title/url/publisher/accessed_at as provided.\n");
		if (reasoningEffort != null && !reasoningEffort.isBlank()) {
			builder.append("Reasoning effort hint: ").append(reasoningEffort.trim()).append("\n");
		}
		builder.append("\n");
		if (!sourcesByIsin.isEmpty()) {
			builder.append("Sources:\n");
			for (Map.Entry<String, List<SearchSource>> entry : sourcesByIsin.entrySet()) {
				builder.append("ISIN ").append(entry.getKey()).append(":\n");
				List<SearchSource> sources = entry.getValue();
				if (sources == null || sources.isEmpty()) {
					builder.append("- No sources found for this ISIN.\n");
					continue;
				}
				for (SearchSource source : sources) {
					builder.append("- id: ").append(source.id()).append("\n")
							.append("  title: ").append(source.title()).append("\n")
							.append("  url: ").append(source.url()).append("\n")
							.append("  publisher: ").append(source.publisher()).append("\n")
							.append("  accessed_at: ").append(source.accessedAt()).append("\n")
							.append("  snippet: ").append(source.snippet()).append("\n");
				}
				builder.append("\n");
			}
		}
		builder.append("Task:\n").append(prompt == null ? "" : prompt);
		return builder.toString();
	}

	private List<SearchSource> fetchSources(String query, List<String> allowedDomains, String isin) {
		Map<?, ?> response = searchSearxng(query);
		Object results = response == null ? null : response.get("results");
		if (!(results instanceof List<?> list) || list.isEmpty()) {
			return List.of();
		}
		Set<String> seen = new LinkedHashSet<>();
		List<SearchSource> sources = new ArrayList<>();
		int index = 1;
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> map)) {
				continue;
			}
			String url = textValue(map.get("url"));
			if (url == null || url.isBlank()) {
				continue;
			}
			String host = hostFromUrl(url);
			if (!isAllowedDomain(host, allowedDomains)) {
				continue;
			}
			if (!seen.add(url)) {
				continue;
			}
			String title = textValue(map.get("title"));
			String snippet = textValue(map.get("content"));
			if (snippet == null || snippet.isBlank()) {
				snippet = textValue(map.get("snippet"));
			}
			snippet = normalizeSnippet(snippet);
			String publisher = host == null ? "unknown" : host;
			String id = buildSourceId(isin, index);
			sources.add(new SearchSource(id,
					trimOrFallback(title, url),
					url,
					publisher,
					LocalDate.now().toString(),
					snippet));
			index++;
			if (sources.size() >= maxResults) {
				break;
			}
		}
		return sources;
	}

	private Map<?, ?> searchSearxng(String query) {
		if (query == null || query.isBlank()) {
			return Map.of();
		}
		try {
			return searxngClient.get()
					.uri(uriBuilder -> uriBuilder
							.path("/search")
							.queryParam("q", query)
							.queryParam("format", "json")
							.queryParam("language", "en")
							.queryParam("safesearch", "1")
							.queryParam("categories", "general")
							.build())
					.header("X-Forwarded-For", "127.0.0.1")
					.header("X-Real-IP", "127.0.0.1")
					.header("User-Agent", "portfoliomanager-kb/1.0")
					.retrieve()
					.body(Map.class);
		} catch (RestClientResponseException ex) {
			logSearchResponseError(ex, query);
			throw new LlmRequestException(safeMessage(ex), ex.getStatusCode().value(), isRetryable(ex), ex);
		} catch (ResourceAccessException ex) {
			logger.warn("SearxNG request failed: {}", safeMessage(ex));
			throw new LlmRequestException(safeMessage(ex), null, true, ex);
		} catch (Exception ex) {
			logger.warn("SearxNG unexpected error: {}", safeMessage(ex));
			throw new LlmRequestException(safeMessage(ex), null, false, ex);
		}
	}

	private List<String> resolveAllowedDomains(List<String> allowedDomains) {
		if (allowedDomains != null && !allowedDomains.isEmpty()) {
			return allowedDomains;
		}
		if (configService == null) {
			return OpenAiLlmClient.allowedWebSearchDomains;
		}
		try {
			List<String> configured = configService.getSnapshot().websearchAllowedDomains();
			if (configured != null && !configured.isEmpty()) {
				return configured;
			}
		} catch (Exception ex) {
			logger.warn("Failed to resolve KB allowed domains: {}", safeMessage(ex));
		}
		return OpenAiLlmClient.allowedWebSearchDomains;
	}

	private List<String> extractIsins(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			return List.of();
		}
		Set<String> isins = new LinkedHashSet<>();
		Matcher matcher = ISIN_RE.matcher(prompt);
		while (matcher.find()) {
			isins.add(matcher.group().trim().toUpperCase(Locale.ROOT));
		}
		return List.copyOf(isins);
	}

	private Map<String, String> extractNameHints(String prompt) {
		Map<String, String> hints = new HashMap<>();
		if (prompt == null || prompt.isBlank()) {
			return hints;
		}
		Matcher matcher = NAME_HINT_RE.matcher(prompt);
		while (matcher.find()) {
			String isin = matcher.group(1);
			String name = matcher.group(2);
			if (isin != null && name != null && !name.isBlank()) {
				hints.put(isin.trim().toUpperCase(Locale.ROOT), name.trim());
			}
		}
		return hints;
	}

	private String buildSearchQuery(String isin, String nameHint) {
		StringBuilder builder = new StringBuilder();
		if (nameHint != null && !nameHint.isBlank()) {
			builder.append(nameHint.trim()).append(' ');
		}
		builder.append(isin).append(" ISIN");
		return builder.toString();
	}

	private String buildSourceId(String isin, int index) {
		String normalized = isin == null || isin.isBlank() ? "src" : isin;
		return "src-" + normalized + "-" + index;
	}

	private String normalizeSnippet(String snippet) {
		if (snippet == null) {
			return "";
		}
		String cleaned = snippet.replaceAll("<[^>]+>", " ")
				.replace("\n", " ")
				.replace("\r", " ")
				.trim();
		if (cleaned.length() <= maxSnippetChars) {
			return cleaned;
		}
		return cleaned.substring(0, maxSnippetChars).trim() + "...";
	}

	private boolean isAllowedDomain(String host, List<String> allowedDomains) {
		if (allowedDomains == null || allowedDomains.isEmpty()) {
			return true;
		}
		if (host == null || host.isBlank()) {
			return false;
		}
		String normalized = host.toLowerCase(Locale.ROOT);
		for (String domain : allowedDomains) {
			if (domain == null || domain.isBlank()) {
				continue;
			}
			String trimmed = domain.trim().toLowerCase(Locale.ROOT);
			if (normalized.equals(trimmed) || normalized.endsWith("." + trimmed)) {
				return true;
			}
		}
		return false;
	}

	private String hostFromUrl(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		try {
			URI uri = URI.create(url);
			String host = uri.getHost();
			if (host == null || host.isBlank()) {
				return null;
			}
			return host.toLowerCase(Locale.ROOT);
		} catch (Exception ex) {
			return null;
		}
	}

	private String textValue(Object value) {
		return value == null ? null : value.toString();
	}

	private String trimOrFallback(String value, String fallback) {
		String trimmed = value == null ? null : value.trim();
		if (trimmed == null || trimmed.isBlank()) {
			return fallback == null ? "" : fallback;
		}
		return trimmed;
	}

	private String serializeSchema(Map<String, Object> schema) {
		if (schema == null || schema.isEmpty() || objectMapper == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(schema);
		} catch (Exception ex) {
			logger.debug("Failed to serialize JSON schema: {}", safeMessage(ex));
			return null;
		}
	}

	private boolean isRetryable(RestClientResponseException ex) {
		if (ex == null || ex.getStatusCode() == null) {
			return false;
		}
		int status = ex.getStatusCode().value();
		return status == 408 || status == 429 || status >= 500;
	}

	private void logResponseError(RestClientResponseException ex, Map<String, Object> request) {
		String modelName = model == null ? "unknown" : model;
		int status = ex.getStatusCode() == null ? 0 : ex.getStatusCode().value();
		logger.error("Ollama chat completion error (model={}, status={})", modelName, status, ex);
	}

	private void logRequestError(Exception ex, Map<String, Object> request) {
		String modelName = model == null ? "unknown" : model;
		logger.error("Ollama chat completion request failed (model={}, error={})", modelName, safeMessage(ex), ex);
	}

	private void logSearchResponseError(RestClientResponseException ex, String query) {
		int status = ex.getStatusCode() == null ? 0 : ex.getStatusCode().value();
		logger.warn("SearxNG search error (status={}, query={})", status, query, ex);
	}

	private String resolveModel(String rawModel) {
		String trimmed = rawModel == null ? "" : rawModel.trim();
		if (trimmed.isBlank()) {
			return "llama3.1:8b";
		}
		if (isLikelyOpenAiModel(trimmed)) {
			return "llama3.1:8b";
		}
		return trimmed;
	}

	private boolean isLikelyOpenAiModel(String modelName) {
		if (modelName == null) {
			return false;
		}
		String lower = modelName.toLowerCase(Locale.ROOT);
		if (lower.startsWith("gpt-")) {
			return true;
		}
		return lower.startsWith("o1")
				|| lower.startsWith("o3")
				|| lower.startsWith("o4")
				|| lower.startsWith("o5");
	}

	private String normalizeOllamaBaseUrl(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return null;
		}
		String normalized = baseUrl.trim();
		if (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		if (normalized.endsWith("/v1")) {
			return normalized;
		}
		return normalized + "/v1";
	}

	private String ensureJsonOutput(String output, String systemPrompt, String userPrompt) {
		if (objectMapper == null) {
			return output;
		}
		String candidate = normalizeJsonOutput(output);
		int attempts = 0;
		while (attempts < 2) {
			if (isValidJson(candidate)) {
				return candidate;
			}
			attempts++;
			String repairSystem = (systemPrompt == null ? "" : systemPrompt)
					+ " Your previous response was invalid JSON. Return only valid JSON.";
			candidate = normalizeJsonOutput(callChatCompletions(repairSystem, userPrompt, true));
		}
		throw new LlmRequestException("LLM output is not valid JSON", null, true, null);
	}

	private String normalizeJsonOutput(String output) {
		if (output == null) {
			return "";
		}
		String trimmed = output.trim();
		if (trimmed.startsWith("```")) {
			int firstNewline = trimmed.indexOf('\n');
			int lastFence = trimmed.lastIndexOf("```");
			if (firstNewline > -1 && lastFence > firstNewline) {
				trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
			}
		}
		if (isValidJson(trimmed)) {
			return trimmed;
		}
		String objectCandidate = extractJsonSubstring(trimmed, '{', '}');
		if (isValidJson(objectCandidate)) {
			return objectCandidate;
		}
		String arrayCandidate = extractJsonSubstring(trimmed, '[', ']');
		return arrayCandidate == null ? trimmed : arrayCandidate;
	}

	private String extractJsonSubstring(String value, char open, char close) {
		if (value == null || value.isBlank()) {
			return value;
		}
		int start = value.indexOf(open);
		int end = value.lastIndexOf(close);
		if (start < 0 || end <= start) {
			return value;
		}
		return value.substring(start, end + 1).trim();
	}

	private boolean isValidJson(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		try {
			objectMapper.readTree(value);
			return true;
		} catch (Exception ex) {
			return false;
		}
	}

	private String safeMessage(Exception ex) {
		if (ex == null) {
			return "Unknown error";
		}
		String message = ex.getMessage();
		if (message == null || message.isBlank()) {
			message = ex.getClass().getSimpleName();
		}
		return message;
	}

	private record SearchSource(
			String id,
			String title,
			String url,
			String publisher,
			String accessedAt,
			String snippet
	) {
		private SearchSource {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(title, "title");
			Objects.requireNonNull(url, "url");
			Objects.requireNonNull(publisher, "publisher");
			Objects.requireNonNull(accessedAt, "accessedAt");
			Objects.requireNonNull(snippet, "snippet");
		}
	}
}
