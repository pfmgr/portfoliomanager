package my.portfoliomanager.app.config;

import com.sun.net.httpserver.HttpServer;
import my.portfoliomanager.app.llm.LlmActionSupport;
import my.portfoliomanager.app.llm.LlmActionType;
import my.portfoliomanager.app.llm.LlmClient;
import my.portfoliomanager.app.llm.LlmSuggestion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigTest {
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void routesActionsToDedicatedOrFallbackConfigurations() throws IOException {
		AtomicReference<Map<String, Object>> globalResponsesRequest = new AtomicReference<>();
		AtomicReference<Map<String, Object>> globalChatRequest = new AtomicReference<>();
		AtomicReference<Map<String, Object>> websearchResponsesRequest = new AtomicReference<>();

		HttpServer globalServer = createServer(globalResponsesRequest, globalChatRequest, null);
		HttpServer websearchServer = createServer(websearchResponsesRequest, null, null);

		globalServer.start();
		websearchServer.start();

		try {
			String globalBaseUrl = "http://localhost:" + globalServer.getAddress().getPort();
			String websearchBaseUrl = "http://localhost:" + websearchServer.getAddress().getPort();

			AppProperties properties = buildProperties(
					new AppProperties.Llm.OpenAi("global-key", globalBaseUrl, "global-model", 300, 300),
					new AppProperties.Llm.Action("openai", "web-key", websearchBaseUrl, "web-model"),
					null,
					null
			);

			LlmClient client = new LlmConfig().llmClient(properties);
			LlmActionSupport support = (LlmActionSupport) client;

			assertThat(support.isConfiguredFor(LlmActionType.WEBSEARCH)).isTrue();
			assertThat(support.isConfiguredFor(LlmActionType.EXTRACTION)).isTrue();
			assertThat(support.isConfiguredFor(LlmActionType.NARRATIVE)).isTrue();

			client.createInstrumentDossierViaWebSearch("web prompt");
			client.extractInstrumentDossierFields("extract prompt");
			client.suggestSavingPlanProposal("narrative prompt");

			assertThat(websearchResponsesRequest.get()).isNotNull();
			assertThat(websearchResponsesRequest.get().get("model")).isEqualTo("web-model");

			assertThat(globalResponsesRequest.get()).isNotNull();
			assertThat(globalResponsesRequest.get().get("model")).isEqualTo("global-model");

			assertThat(globalChatRequest.get()).isNotNull();
			assertThat(globalChatRequest.get().get("model")).isEqualTo("global-model");
		} finally {
			websearchServer.stop(0);
			globalServer.stop(0);
		}
	}

	@Test
	void disablesActionsWhenApiKeyMissing() throws IOException {
		AtomicReference<Map<String, Object>> websearchResponsesRequest = new AtomicReference<>();
		HttpServer websearchServer = createServer(websearchResponsesRequest, null, null);
		websearchServer.start();

		try {
			String websearchBaseUrl = "http://localhost:" + websearchServer.getAddress().getPort();
			AppProperties properties = buildProperties(
					new AppProperties.Llm.OpenAi(null, "https://api.openai.com/v1", "global-model", 300, 300),
					new AppProperties.Llm.Action("openai", "web-key", websearchBaseUrl, "web-model"),
					null,
					null
			);

			LlmClient client = new LlmConfig().llmClient(properties);
			LlmActionSupport support = (LlmActionSupport) client;

			assertThat(support.isConfiguredFor(LlmActionType.WEBSEARCH)).isTrue();
			assertThat(support.isConfiguredFor(LlmActionType.EXTRACTION)).isFalse();
			assertThat(support.isConfiguredFor(LlmActionType.NARRATIVE)).isFalse();

			LlmSuggestion narrative = client.suggestSavingPlanProposal("prompt");
			assertThat(narrative.rationale()).contains("disabled");

			client.createInstrumentDossierViaWebSearch("web prompt");
			assertThat(websearchResponsesRequest.get()).isNotNull();
			assertThat(websearchResponsesRequest.get().get("model")).isEqualTo("web-model");
		} finally {
			websearchServer.stop(0);
		}
	}

	@Test
	void supportsProviderNonePerActionWithGlobalFallback() {
		AppProperties properties = buildProperties(
				new AppProperties.Llm.OpenAi("global-key", "https://api.openai.com/v1", "global-model", 300, 300),
				null,
				null,
				new AppProperties.Llm.Action("none", null, null, null)
		);

		LlmClient client = new LlmConfig().llmClient(properties);
		LlmActionSupport support = (LlmActionSupport) client;

		assertThat(support.isConfiguredFor(LlmActionType.WEBSEARCH)).isTrue();
		assertThat(support.isConfiguredFor(LlmActionType.EXTRACTION)).isTrue();
		assertThat(support.isConfiguredFor(LlmActionType.NARRATIVE)).isFalse();
	}

	@Test
	void disablesUnsupportedProviderPerAction() {
		AppProperties properties = buildProperties(
				new AppProperties.Llm.OpenAi("global-key", "https://api.openai.com/v1", "global-model", 300, 300),
				new AppProperties.Llm.Action("unsupported", "key", "https://example.com", "model"),
				null,
				null
		);

		LlmClient client = new LlmConfig().llmClient(properties);
		LlmActionSupport support = (LlmActionSupport) client;

		assertThat(support.isConfiguredFor(LlmActionType.WEBSEARCH)).isFalse();
		assertThat(support.isConfiguredFor(LlmActionType.EXTRACTION)).isTrue();
		assertThat(support.isConfiguredFor(LlmActionType.NARRATIVE)).isTrue();
	}

	private HttpServer createServer(AtomicReference<Map<String, Object>> responsesRequest,
									 AtomicReference<Map<String, Object>> chatRequest,
									 AtomicReference<Map<String, Object>> unsupportedRequest) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/responses", exchange -> {
			byte[] requestBytes = exchange.getRequestBody().readAllBytes();
			if (responsesRequest != null) {
				responsesRequest.set(objectMapper.readValue(requestBytes, Map.class));
			}
			byte[] response = "{\"output_text\":\"{}\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(response);
			}
		});
		server.createContext("/chat/completions", exchange -> {
			byte[] requestBytes = exchange.getRequestBody().readAllBytes();
			if (chatRequest != null) {
				chatRequest.set(objectMapper.readValue(requestBytes, Map.class));
			}
			byte[] response = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(response);
			}
		});
		if (unsupportedRequest != null) {
			server.createContext("/", exchange -> {
				byte[] requestBytes = exchange.getRequestBody().readAllBytes();
				unsupportedRequest.set(objectMapper.readValue(requestBytes, Map.class));
				byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, response.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(response);
				}
			});
		}
		return server;
	}

	private AppProperties buildProperties(AppProperties.Llm.OpenAi openAi,
									   AppProperties.Llm.Action websearch,
									   AppProperties.Llm.Action extraction,
									   AppProperties.Llm.Action narrative) {
		AppProperties.Security security = new AppProperties.Security("admin", "admin");
		AppProperties.Jwt jwt = new AppProperties.Jwt("secret", "hash-secret", "issuer", 3600L, 300L, 1000, true);
		AppProperties.Llm llm = new AppProperties.Llm("openai", openAi, false, websearch, extraction, narrative);
		AppProperties.Kb kb = new AppProperties.Kb(true, true);
		return new AppProperties(security, jwt, llm, kb);
	}
}
