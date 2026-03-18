package my.portfoliomanager.app.llm;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class OpenAiLlmClient implements LlmClient, KnowledgeBaseLlmProvider {
    private static final Logger logger = LoggerFactory.getLogger(OpenAiLlmClient.class);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5);
    private static final String MODEL_KEY = "model";
    private static final String MESSAGES_KEY = "messages";
    private static final String ROLE_KEY = "role";
    private static final String CONTENT_KEY = "content";
    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String RESPONSE_ORIGIN_PREFIX = "openai(model=";
    private static final String REASONING_KEY = "reasoning";
    private static final String EFFORT_KEY = "effort";
    private static final String UNKNOWN_VALUE = "unknown";
    public static final List<String> allowedWebSearchDomains = List.of("justetf.com", "ishares.com", "vanguard.com", "ssga.com",
            "spdrs.com", "amundietf.com", "wisdomtree.eu", "invesco.com", "vaneck.com",
            "xtrackers.com", "blackrock.com", "statestreet.com", "lyxoretf.com", "openfigi.com",
            "sec.gov", "companieshouse.gov.uk", "bundesanzeiger.de", "nasdaq.com", "nyse.com",
            "londonstockexchange.com", "euronext.com", "boerse-frankfurt.de", "deutsche-boerse.com",
            "six-group.com", "borsaitaliana.it", "asx.com.au", "hkex.com.hk", "tse.or.jp",
            "tmx.com", "nseindia.com", "bseindia.com", "sgx.com", "fondsweb.com", "deka.de",
            "deka-etf.de", "boerse-hamburg.de", "marketscreener.com", "statista.com", "finbox.com");
    private final RestClient restClient;
    private final String model;

    public OpenAiLlmClient(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public OpenAiLlmClient(String baseUrl, String apiKey, String model, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(ensureMinimumTimeout(connectTimeout, DEFAULT_CONNECT_TIMEOUT));
        requestFactory.setReadTimeout(ensureMinimumTimeout(readTimeout, DEFAULT_READ_TIMEOUT));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.model = model;
    }

    @Override
    public LlmSuggestion suggestReclassification(String context) {
        Map<String, Object> request = Map.of(MODEL_KEY, model, MESSAGES_KEY,
                List.of(Map.of(ROLE_KEY, ROLE_SYSTEM, CONTENT_KEY, "Provide reclassification suggestion only."),
                        Map.of(ROLE_KEY, ROLE_USER, CONTENT_KEY, context)));
        return getSuggestionFromChatCompletionsAPI(request);
    }

    private @NonNull LlmSuggestion getSuggestionFromChatCompletionsAPI(Map<String, Object> request) {
        Map<?, ?> response = restClient.post().uri("/chat/completions").body(request).retrieve().body(Map.class);
        if (response == null || response.get("choices") == null) {
            return new LlmSuggestion("", "No response");
        }
        Object choices = response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return new LlmSuggestion("", "No choices");
        }
        Object first = list.getFirst();
        if (!(first instanceof Map<?, ?> map)) {
            return new LlmSuggestion("", "Invalid response");
        }
        Object message = map.get("message");
        if (!(message instanceof Map<?, ?> msgMap)) {
            return new LlmSuggestion("", "Invalid message");
        }
        Object content = msgMap.get(CONTENT_KEY);
        return new LlmSuggestion(content == null ? "" : content.toString(), "openai");
    }

    @Override
    public LlmSuggestion suggestSavingPlanProposal(String context) {
        Map<String, Object> request = Map.of(MODEL_KEY, model, MESSAGES_KEY,
                List.of(Map.of(ROLE_KEY, ROLE_SYSTEM, CONTENT_KEY,
                                "Provide a short savingPlan proposal narrative/explanation in plain text only."),
                        Map.of(ROLE_KEY, ROLE_USER, CONTENT_KEY, context)));
        return getSuggestionFromChatCompletionsAPI(request);
    }

    @Override
    public LlmSuggestion createInstrumentDossierViaWebSearch(String context) {
        try {
            KnowledgeBaseLlmResponse response = runWebSearch(context, allowedWebSearchDomains, null);
            return new LlmSuggestion(response.output(), response.model());
        } catch (LlmRequestException ex) {
            return llmErrorSuggestion(ex);
        }
    }

    @Override
    public LlmSuggestion createInstrumentDossierViaWebSearch(String context, String schemaName, Map<String, Object> schema) {
        try {
            KnowledgeBaseLlmResponse response = runWebSearch(context, allowedWebSearchDomains, null, schemaName, schema);
            return new LlmSuggestion(response.output(), response.model());
        } catch (LlmRequestException ex) {
            return llmErrorSuggestion(ex);
        }
    }

    @Override
    public LlmSuggestion createInstrumentDossierViaWebSearch(String context, String reasoningEffort) {
        try {
            KnowledgeBaseLlmResponse response = runWebSearch(context, allowedWebSearchDomains, reasoningEffort);
            return new LlmSuggestion(response.output(), response.model());
        } catch (LlmRequestException ex) {
            return llmErrorSuggestion(ex);
        }
    }

    @Override
    public LlmSuggestion createInstrumentDossierViaWebSearch(String context,
                                                             String schemaName,
                                                             Map<String, Object> schema,
                                                             String reasoningEffort) {
        try {
            KnowledgeBaseLlmResponse response = runWebSearch(context, allowedWebSearchDomains, reasoningEffort, schemaName, schema);
            return new LlmSuggestion(response.output(), response.model());
        } catch (LlmRequestException ex) {
            return llmErrorSuggestion(ex);
        }
    }

    @Override
    public LlmSuggestion extractInstrumentDossierFields(String context) {
        try {
            KnowledgeBaseLlmResponse response = runJsonPrompt(context);
            return new LlmSuggestion(response.output(), response.model());
        } catch (LlmRequestException ex) {
            return llmErrorSuggestion(ex);
        }
    }

    @Override
    public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains) {
        return runWebSearch(prompt, allowedDomains, null, null);
    }

    @Override
    public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains, String schemaName, Map<String, Object> schema) {
        Map<String, Object> request = buildWebSearchRequest(prompt, allowedDomains, schemaName, schema, null);
        return callResponsesApi(request);
    }

    @Override
    public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains, String reasoningEffort) {
        return runWebSearch(prompt, allowedDomains, reasoningEffort, null, null);
    }

    @Override
    public KnowledgeBaseLlmResponse runWebSearch(String prompt,
                                                 List<String> allowedDomains,
                                                 String reasoningEffort,
                                                 String schemaName,
                                                 Map<String, Object> schema) {
        Map<String, Object> request = buildWebSearchRequest(prompt, allowedDomains, schemaName, schema, reasoningEffort);
        return callResponsesApi(request);
    }

    @Override
    public KnowledgeBaseLlmResponse runJsonPrompt(String prompt) {
        return runJsonPrompt(prompt, null, null);
    }

    @Override
    public KnowledgeBaseLlmResponse runJsonPrompt(String prompt, String schemaName, Map<String, Object> schema) {
        Map<String, Object> request = buildJsonPromptRequest(prompt, schemaName, schema);
        return callResponsesApi(request);
    }

    private Map<String, Object> buildJsonPromptRequest(String prompt,
                                                       String schemaName,
                                                       Map<String, Object> schema) {
        Map<String, Object> request = new HashMap<>();
        request.put(MODEL_KEY, model);
        request.put("input", List.of(
                Map.of(ROLE_KEY, ROLE_SYSTEM, CONTENT_KEY, "Respond in JSON only. Do not wrap in Markdown code fences."),
                Map.of(ROLE_KEY, ROLE_USER, CONTENT_KEY, prompt)
        ));
        request.put(REASONING_KEY, Map.of(EFFORT_KEY, "low"));
        if (schemaName != null && schema != null) {
            request.put("text", Map.of("format", Map.of(
                    "type", "json_schema",
                    "name", schemaName,
                    "schema", schema,
                    "strict", true
            )));
        }
        return request;
    }

    private Map<String, Object> buildWebSearchRequest(String prompt,
                                                      List<String> allowedDomains,
                                                      String schemaName,
                                                      Map<String, Object> schema,
                                                      String reasoningEffort) {
        List<String> domains = allowedDomains == null || allowedDomains.isEmpty() ? allowedWebSearchDomains : allowedDomains;
        String effort = normalizeReasoningEffort(reasoningEffort);
        Map<String, Object> request = new HashMap<>();
        request.put(MODEL_KEY, model);
        request.put("input", List.of(
                Map.of(ROLE_KEY, ROLE_SYSTEM, CONTENT_KEY, "Respond in JSON only. Do not wrap in Markdown code fences."),
                Map.of(ROLE_KEY, ROLE_USER, CONTENT_KEY, prompt)
        ));
        request.put("tools", List.of(Map.of("type", "web_search", "filters", Map.of("allowed_domains", domains))));
        request.put(REASONING_KEY, Map.of(EFFORT_KEY, effort));
        if (schemaName != null && schema != null) {
            request.put("text", Map.of("format", Map.of(
                    "type", "json_schema",
                    "name", schemaName,
                    "schema", schema,
                    "strict", true
            )));
        }
        return request;
    }

    private String normalizeReasoningEffort(String effort) {
        if (effort == null || effort.isBlank()) {
            return "low";
        }
        String normalized = effort.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "low", "medium", "high" -> normalized;
            default -> "low";
        };
    }

    private KnowledgeBaseLlmResponse callResponsesApi(Map<String, Object> request) {
        Map<?, ?> response;
        try {
            response = restClient.post().uri("/responses").body(request).retrieve().body(Map.class);
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
        String text = extractOutputText(response);
        if (text == null || text.isBlank()) {
            throw new LlmRequestException("No output_text", null, false, null);
        }
        return new KnowledgeBaseLlmResponse(text, model);
    }

    private String extractOutputText(Map<?, ?> response) {
        if (response == null) {
            return null;
        }
        Object outputText = response.get("output_text");
        if (outputText instanceof String text && !text.isBlank()) {
            return text;
        }
        Object output = response.get("output");
        if (!(output instanceof List<?> outputList) || outputList.isEmpty()) {
            return null;
        }
        StringBuilder combined = new StringBuilder();
        for (Object outputItem : outputList) {
            appendOutputText(combined, outputItem);
        }
        return combined.toString();
    }

    private void appendOutputText(StringBuilder combined, Object outputItem) {
        if (!(outputItem instanceof Map<?, ?> outputMap)) {
            return;
        }
        Object content = outputMap.get(CONTENT_KEY);
        if (!(content instanceof List<?> contentList)) {
            return;
        }
        for (Object contentItem : contentList) {
            appendContentText(combined, contentItem);
        }
    }

    private void appendContentText(StringBuilder combined, Object contentItem) {
        if (!(contentItem instanceof Map<?, ?> contentMap)) {
            return;
        }
        if (!isOutputTextType(contentMap.get("type"))) {
            return;
        }
        Object text = contentMap.get("text");
        if (text == null) {
            return;
        }
        if (!combined.isEmpty()) {
            combined.append("\n");
        }
        combined.append(text);
    }

    private boolean isOutputTextType(Object type) {
        return type == null || "output_text".equals(type.toString());
    }

    private boolean isRetryable(RestClientResponseException ex) {
        if (ex == null || ex.getStatusCode() == null) {
            return false;
        }
        int status = ex.getStatusCode().value();
        return status == 408 || status == 429 || status >= 500;
    }

    private Duration ensureMinimumTimeout(Duration value, Duration minimum) {
        if (value == null) {
            return minimum;
        }
        if (value.compareTo(minimum) < 0) {
            return minimum;
        }
        return value;
    }

    private void logResponseError(RestClientResponseException ex, Map<String, Object> request) {
        String contentType = ex.getResponseHeaders() == null || ex.getResponseHeaders().getContentType() == null
                ? UNKNOWN_VALUE
                : ex.getResponseHeaders().getContentType().toString();
        int bodyLength = ex.getResponseBodyAsByteArray() == null ? 0 : ex.getResponseBodyAsByteArray().length;
        String effort = extractReasoningEffort(request);
        int status = ex.getStatusCode() == null ? 0 : ex.getStatusCode().value();
        logger.error("OpenAI responses API error (model={}, effort={}, status={}, contentType={}, bodyLength={})",
                model, effort, status, contentType, bodyLength, ex);
    }

    private void logRequestError(Exception ex, Map<String, Object> request) {
        String effort = extractReasoningEffort(request);
        logger.error("OpenAI responses API request failed (model={}, effort={}, error={})",
                model, effort, safeMessage(ex), ex);
    }

    private String extractReasoningEffort(Map<String, Object> request) {
        if (request == null) {
            return UNKNOWN_VALUE;
        }
        Object reasoning = request.get(REASONING_KEY);
        if (!(reasoning instanceof Map<?, ?> map)) {
            return UNKNOWN_VALUE;
        }
        Object effort = map.get(EFFORT_KEY);
        return effort == null ? UNKNOWN_VALUE : effort.toString();
    }

    private LlmSuggestion llmErrorSuggestion(LlmRequestException ex) {
        return new LlmSuggestion("", RESPONSE_ORIGIN_PREFIX + model + "): " + ex.getMessage());
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
}
