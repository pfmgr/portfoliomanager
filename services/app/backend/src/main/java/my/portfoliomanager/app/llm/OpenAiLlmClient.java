package my.portfoliomanager.app.llm;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenAiLlmClient implements LlmClient, KnowledgeBaseLlmProvider {
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5);
    public static final List<String> allowedWebSearchDomains = List.of("justetf.com", "ishares.com", "vanguard.com", "ssga.com",
            "spdrs.com", "amundietf.com", "wisdomtree.eu", "invesco.com", "vaneck.com",
            "xtrackers.com", "blackrock.com", "stateStreet.com", "lyxoretf.com", "openfigi.com",
            "sec.gov", "companieshouse.gov.uk", "bundesanzeiger.de", "nasdaq.com", "nyse.com",
            "londonstockexchange.com", "euronext.com", "boerse-frankfurt.de", "deutsche-boerse.com",
            "six-group.com", "borsaitaliana.it", "asx.com.au", "hkex.com.hk", "tse.or.jp",
            "tmx.com", "nseindia.com", "bseindia.com", "sgx.com", "fondsweb.com", "deka.de","deka-etf.de","boerse-hamburg.de");
    private final RestClient restClient;
    private final String model;

    public OpenAiLlmClient(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public OpenAiLlmClient(String baseUrl, String apiKey, String model, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout);
        requestFactory.setReadTimeout(readTimeout == null ? DEFAULT_READ_TIMEOUT : readTimeout);
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
        Map<String, Object> request = Map.of("model", model, "messages",
                List.of(Map.of("role", "system", "content", "Provide reclassification suggestion only."),
                        Map.of("role", "user", "content", context)));
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
        Object content = msgMap.get("content");
        return new LlmSuggestion(content == null ? "" : content.toString(), "openai");
    }

    @Override
    public LlmSuggestion suggestSavingPlanProposal(String context) {
        Map<String, Object> request = Map.of("model", model, "messages",
                List.of(Map.of("role", "system", "content",
                                "Provide a short savingPlan proposal narrative/explanation in plain text only."),
                        Map.of("role", "user", "content", context)));
        return getSuggestionFromChatCompletionsAPI(request);
    }

    @Override
    public LlmSuggestion createInstrumentDossierViaWebSearch(String context) {
        try {
            KnowledgeBaseLlmResponse response = runWebSearch(context, allowedWebSearchDomains);
            return new LlmSuggestion(response.output(), response.model());
        } catch (LlmRequestException ex) {
            return new LlmSuggestion("", "openai(model=" + model + "): " + ex.getMessage());
        }
    }

    @Override
    public LlmSuggestion createInstrumentDossierViaWebSearch(String context, String schemaName, Map<String, Object> schema) {
        try {
            KnowledgeBaseLlmResponse response = runWebSearch(context, allowedWebSearchDomains, schemaName, schema);
            return new LlmSuggestion(response.output(), response.model());
        } catch (LlmRequestException ex) {
            return new LlmSuggestion("", "openai(model=" + model + "): " + ex.getMessage());
        }
    }

    @Override
    public LlmSuggestion extractInstrumentDossierFields(String context) {
        try {
            KnowledgeBaseLlmResponse response = runJsonPrompt(context);
            return new LlmSuggestion(response.output(), response.model());
        } catch (LlmRequestException ex) {
            return new LlmSuggestion("", "openai(model=" + model + "): " + ex.getMessage());
        }
    }

    @Override
    public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains) {
        return runWebSearch(prompt, allowedDomains, null, null);
    }

    @Override
    public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains, String schemaName, Map<String, Object> schema) {
        Map<String, Object> request = buildWebSearchRequest(prompt, allowedDomains, schemaName, schema);
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
        request.put("model", model);
        request.put("input", List.of(
                Map.of("role", "system", "content", "Respond in JSON only. Do not wrap in Markdown code fences."),
                Map.of("role", "user", "content", prompt)
        ));
        request.put("reasoning", Map.of("effort", "low"));
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
                                                      Map<String, Object> schema) {
        List<String> domains = allowedDomains == null || allowedDomains.isEmpty() ? allowedWebSearchDomains : allowedDomains;
        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("input", List.of(
                Map.of("role", "system", "content", "Respond in JSON only. Do not wrap in Markdown code fences."),
                Map.of("role", "user", "content", prompt)
        ));
        request.put("tools", List.of(Map.of("type", "web_search", "filters", Map.of("allowed_domains", domains))));
        request.put("reasoning", Map.of("effort", "low"));
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

    private KnowledgeBaseLlmResponse callResponsesApi(Map<String, Object> request) {
        Map<?, ?> response;
        try {
            response = restClient.post().uri("/responses").body(request).retrieve().body(Map.class);
        } catch (RestClientResponseException ex) {
            throw new LlmRequestException(safeMessage(ex), ex.getStatusCode().value(), isRetryable(ex), ex);
        } catch (ResourceAccessException ex) {
            throw new LlmRequestException(safeMessage(ex), null, true, ex);
        } catch (Exception ex) {
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
            if (!(outputItem instanceof Map<?, ?> outputMap)) {
                continue;
            }
            Object content = outputMap.get("content");
            if (!(content instanceof List<?> contentList)) {
                continue;
            }
            for (Object contentItem : contentList) {
                if (!(contentItem instanceof Map<?, ?> contentMap)) {
                    continue;
                }
                Object type = contentMap.get("type");
                if (type != null && !"output_text".equals(type.toString())) {
                    continue;
                }
                Object text = contentMap.get("text");
                if (text == null) {
                    continue;
                }
                if (!combined.isEmpty()) {
                    combined.append("\n");
                }
                combined.append(text);
            }
        }
        return combined.toString();
    }

    private boolean isRetryable(RestClientResponseException ex) {
        if (ex == null || ex.getStatusCode() == null) {
            return false;
        }
        int status = ex.getStatusCode().value();
        return status == 408 || status == 429 || status >= 500;
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
