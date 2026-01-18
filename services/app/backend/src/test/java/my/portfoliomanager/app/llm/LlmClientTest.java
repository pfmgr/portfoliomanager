package my.portfoliomanager.app.llm;

import com.sun.net.httpserver.HttpExchange;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LlmClientTest {
	@Test
	void noopClientReturnsDisabledMessage() {
		NoopLlmClient client = new NoopLlmClient();
		LlmSuggestion suggestion = client.suggestReclassification("context");
		assertThat(suggestion.rationale()).contains("disabled");
	}

	@Test
	void openAiClientParsesResponse() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/chat/completions", new JsonHandler());
		server.start();
		int port = server.getAddress().getPort();

		try {
			OpenAiLlmClient client = new OpenAiLlmClient("http://localhost:" + port, "test-key", "gpt-test");
			LlmSuggestion suggestion = client.suggestReclassification("context");
			assertThat(suggestion.suggestion()).contains("Use layer 2");
			assertThat(suggestion.rationale()).isEqualTo("openai");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void openAiClientHandlesEmptyChoices() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/chat/completions", new EmptyChoicesHandler());
		server.start();
		int port = server.getAddress().getPort();

		try {
			OpenAiLlmClient client = new OpenAiLlmClient("http://localhost:" + port, "test-key", "gpt-test");
			LlmSuggestion suggestion = client.suggestReclassification("context");
			assertThat(suggestion.rationale()).contains("choices");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void openAiClientHandlesMissingChoices() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/chat/completions", new MissingChoicesHandler());
		server.start();
		int port = server.getAddress().getPort();

		try {
			OpenAiLlmClient client = new OpenAiLlmClient("http://localhost:" + port, "test-key", "gpt-test");
			LlmSuggestion suggestion = client.suggestReclassification("context");
			assertThat(suggestion.rationale()).contains("response");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void openAiClientHandlesInvalidChoiceShape() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/chat/completions", new InvalidChoiceHandler());
		server.start();
		int port = server.getAddress().getPort();

		try {
			OpenAiLlmClient client = new OpenAiLlmClient("http://localhost:" + port, "test-key", "gpt-test");
			LlmSuggestion suggestion = client.suggestReclassification("context");
			assertThat(suggestion.rationale()).contains("Invalid");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void openAiClientWebsearchAddsJsonSchemaFormat() throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/responses", exchange -> {
			byte[] bytes = exchange.getRequestBody().readAllBytes();
			Map<String, Object> body = mapper.readValue(bytes, Map.class);
			captured.set(body);
			String response = "{\"output_text\":\"{\\\"items\\\": []}\"}";
			byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, responseBytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(responseBytes);
			}
		});
		server.start();
		int port = server.getAddress().getPort();

		try {
			OpenAiLlmClient client = new OpenAiLlmClient("http://localhost:" + port, "test-key", "gpt-test");
			Map<String, Object> schema = Map.of(
					"type", "object",
					"properties", Map.of("items", Map.of("type", "array"))
			);
			LlmSuggestion suggestion = client.createInstrumentDossierViaWebSearch("prompt", "kb_schema", schema);
			assertThat(suggestion.rationale()).isEqualTo("gpt-test");
		} finally {
			server.stop(0);
		}

		Map<String, Object> request = captured.get();
		assertThat(request).isNotNull();
		assertThat(request.get("text")).isInstanceOf(Map.class);
		Map<String, Object> text = (Map<String, Object>) request.get("text");
		assertThat(text.get("format")).isInstanceOf(Map.class);
		Map<String, Object> format = (Map<String, Object>) text.get("format");
		assertThat(format.get("type")).isEqualTo("json_schema");
		assertThat(format.get("name")).isEqualTo("kb_schema");
		assertThat(format.get("strict")).isEqualTo(Boolean.TRUE);
		assertThat(format.get("schema")).isInstanceOf(Map.class);
	}

	@Test
	void openAiClientJsonPromptAddsJsonSchemaFormat() throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/responses", exchange -> {
			byte[] bytes = exchange.getRequestBody().readAllBytes();
			Map<String, Object> body = mapper.readValue(bytes, Map.class);
			captured.set(body);
			String response = "{\"output_text\":\"{}\"}";
			byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, responseBytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(responseBytes);
			}
		});
		server.start();
		int port = server.getAddress().getPort();

		try {
			OpenAiLlmClient client = new OpenAiLlmClient("http://localhost:" + port, "test-key", "gpt-test");
			Map<String, Object> schema = Map.of(
					"type", "object",
					"properties", Map.of("isin", Map.of("type", "string"))
			);
			KnowledgeBaseLlmResponse response = client.runJsonPrompt("prompt", "kb_extraction", schema);
			assertThat(response.output()).isEqualTo("{}");
		} finally {
			server.stop(0);
		}

		Map<String, Object> request = captured.get();
		assertThat(request).isNotNull();
		assertThat(request.get("text")).isInstanceOf(Map.class);
		Map<String, Object> text = (Map<String, Object>) request.get("text");
		assertThat(text.get("format")).isInstanceOf(Map.class);
		Map<String, Object> format = (Map<String, Object>) text.get("format");
		assertThat(format.get("type")).isEqualTo("json_schema");
		assertThat(format.get("name")).isEqualTo("kb_extraction");
		assertThat(format.get("strict")).isEqualTo(Boolean.TRUE);
		assertThat(format.get("schema")).isInstanceOf(Map.class);
	}

	private static class JsonHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			String response = "{\"choices\":[{\"message\":{\"content\":\"Use layer 2\"}}]}";
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		}
	}

	private static class EmptyChoicesHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			String response = "{\"choices\":[]}";
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		}
	}

	private static class MissingChoicesHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			String response = "{}";
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		}
	}

	private static class InvalidChoiceHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			String response = "{\"choices\":[\"oops\"]}";
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		}
	}
}
