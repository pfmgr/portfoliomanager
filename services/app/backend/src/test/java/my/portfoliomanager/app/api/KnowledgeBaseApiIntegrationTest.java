package my.portfoliomanager.app.api;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import my.portfoliomanager.app.dto.KnowledgeBaseBulkResearchRequestDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionStatus;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmAlternativeItem;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmAlternativesDraft;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmClient;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmDossierDraft;
import my.portfoliomanager.app.llm.LlmClient;
import my.portfoliomanager.app.llm.LlmSuggestion;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
@Import(KnowledgeBaseApiIntegrationTest.TestConfig.class)
class KnowledgeBaseApiIntegrationTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	private MockMvc mockMvc;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
		registry.add("app.kb.enabled", () -> "true");
		registry.add("app.kb.llm-enabled", () -> "false");
	}

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();
	}

	@Test
	void endpointsRequireBasicAuth() throws Exception {
		mockMvc.perform(get("/api/kb/dossiers"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/api/kb/dossiers")
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk());
	}

	@Test
	void configEndpoints_readAndUpdate() throws Exception {
		mockMvc.perform(get("/api/kb/config")
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.refresh_interval_days").isNumber());

		String updatePayload = "{\"enabled\":true,\"auto_approve\":true}";
		mockMvc.perform(put("/api/kb/config")
						.with(httpBasic("admin", "admin"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(updatePayload))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.auto_approve").value(true));
	}

	@Test
	void bulkResearch_returnsItems() throws Exception {
		KnowledgeBaseBulkResearchRequestDto request = new KnowledgeBaseBulkResearchRequestDto(
				List.of("DE0000000001"),
				false,
				false
		);
		MvcResult result = mockMvc.perform(post("/api/kb/dossiers/bulk-research")
						.with(httpBasic("admin", "admin"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andReturn();

		KnowledgeBaseLlmActionDto action = objectMapper.readValue(
				result.getResponse().getContentAsString(),
				KnowledgeBaseLlmActionDto.class
		);
		KnowledgeBaseLlmActionDto completed = awaitAction(action.actionId());
		assertThat(completed.bulkResearchResult()).isNotNull();
		assertThat(completed.bulkResearchResult().items()).isNotEmpty();
		assertThat(completed.bulkResearchResult().items().get(0).isin()).isEqualTo("DE0000000001");
	}

	@Test
	void alternatives_returnsItems() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/kb/alternatives/DE0000000001")
						.with(httpBasic("admin", "admin"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"autoApprove\":false}"))
				.andExpect(status().isOk())
				.andReturn();
		KnowledgeBaseLlmActionDto action = objectMapper.readValue(
				result.getResponse().getContentAsString(),
				KnowledgeBaseLlmActionDto.class
		);
		KnowledgeBaseLlmActionDto completed = awaitAction(action.actionId());
		assertThat(completed.alternativesResult()).isNotNull();
		assertThat(completed.alternativesResult().alternatives()).isNotEmpty();
		assertThat(completed.alternativesResult().alternatives().get(0).isin()).isEqualTo("DE0000000002");
	}

	@Test
	void refreshBatch_dryRunProcessesScope() throws Exception {
		String payload = "{\"limit\":2,\"batchSize\":1,\"dryRun\":true,\"scope\":{\"isins\":[\"DE0000000001\",\"DE0000000002\"]}}";
		MvcResult result = mockMvc.perform(post("/api/kb/refresh/batch")
						.with(httpBasic("admin", "admin"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(payload))
				.andExpect(status().isOk())
				.andReturn();
		KnowledgeBaseLlmActionDto action = objectMapper.readValue(
				result.getResponse().getContentAsString(),
				KnowledgeBaseLlmActionDto.class
		);
		KnowledgeBaseLlmActionDto completed = awaitAction(action.actionId());
		assertThat(completed.refreshBatchResult()).isNotNull();
		assertThat(completed.refreshBatchResult().processed()).isEqualTo(2);
		assertThat(completed.refreshBatchResult().dryRun()).isTrue();
	}

	@Test
	void runsEndpoint_returnsItems() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/kb/dossiers/bulk-research")
						.with(httpBasic("admin", "admin"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"isins\":[\"DE0000000001\"],\"autoApprove\":false}"))
				.andExpect(status().isOk())
				.andReturn();
		KnowledgeBaseLlmActionDto action = objectMapper.readValue(
				result.getResponse().getContentAsString(),
				KnowledgeBaseLlmActionDto.class
		);
		awaitAction(action.actionId());

		String response = mockMvc.perform(get("/api/kb/runs")
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		assertThat(response).contains("\"items\"");
	}

	private KnowledgeBaseLlmActionDto awaitAction(String actionId) throws Exception {
		for (int attempt = 0; attempt < 200; attempt++) {
			MvcResult result = mockMvc.perform(get("/api/kb/llm-actions/" + actionId)
							.with(httpBasic("admin", "admin")))
					.andExpect(status().isOk())
					.andReturn();
			KnowledgeBaseLlmActionDto action = objectMapper.readValue(
					result.getResponse().getContentAsString(),
					KnowledgeBaseLlmActionDto.class
			);
			if (action.status() != KnowledgeBaseLlmActionStatus.RUNNING) {
				return action;
			}
			Thread.sleep(50L);
		}
		throw new AssertionError("Timed out waiting for LLM action " + actionId);
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Configuration
	static class TestConfig {
		@Bean
		@org.springframework.context.annotation.Primary
		KnowledgeBaseLlmClient knowledgeBaseLlmClient(ObjectMapper objectMapper) {
			return new KnowledgeBaseLlmClient() {
				@Override
				public KnowledgeBaseLlmDossierDraft generateDossier(String isin,
													 String context,
													 List<String> allowedDomains,
													 int maxChars) {
					ArrayNode citations = objectMapper.createArrayNode();
					citations.add(objectMapper.createObjectNode()
							.put("id", "1")
							.put("title", "Test")
							.put("url", "https://example.com")
							.put("publisher", "Example")
							.put("accessed_at", "2024-01-01"));
					String content = "name: Test Instrument\n" +
							"instrument_type: ETF\n" +
							"asset_class: Equity\n" +
							"layer: 2";
					return new KnowledgeBaseLlmDossierDraft(content, "Test Instrument", citations, "test-model");
				}

				@Override
				public my.portfoliomanager.app.llm.KnowledgeBaseLlmExtractionDraft extractMetadata(String dossierText) {
					throw new UnsupportedOperationException();
				}

				@Override
				public KnowledgeBaseLlmAlternativesDraft findAlternatives(String isin, List<String> allowedDomains) {
					ArrayNode citations = objectMapper.createArrayNode();
					citations.add(objectMapper.createObjectNode()
							.put("id", "1")
							.put("title", "Alt")
							.put("url", "https://example.com/alt")
							.put("publisher", "Example")
							.put("accessed_at", "2024-01-01"));
					return new KnowledgeBaseLlmAlternativesDraft(List.of(
							new KnowledgeBaseLlmAlternativeItem("DE0000000002", "Similar exposure", citations)
					));
				}
			};
		}

		@Bean
		@org.springframework.context.annotation.Primary
		LlmClient llmClient() {
			return new LlmClient() {
				@Override
				public LlmSuggestion suggestReclassification(String context) {
					return new LlmSuggestion("", "test");
				}

				@Override
				public LlmSuggestion suggestSavingPlanProposal(String context) {
					return new LlmSuggestion("", "test");
				}
			};
		}
	}
}
