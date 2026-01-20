package my.portfoliomanager.app.api;

import my.portfoliomanager.app.llm.NoopLlmClient;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
@Import(RebalancerApiIntegrationTest.TestConfig.class)
class RebalancerApiIntegrationTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	private MockMvc mockMvc;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
	}

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();

		jdbcTemplate.update("delete from snapshot_positions");
		jdbcTemplate.update("update depots set active_snapshot_id = null");
		jdbcTemplate.update("delete from snapshots");
		jdbcTemplate.update("delete from sparplans_history");
		jdbcTemplate.update("delete from sparplans");
		jdbcTemplate.update("delete from instruments");
		jdbcTemplate.update("delete from rulesets");
		jdbcTemplate.update("delete from depots");

		jdbcTemplate.update("insert into depots (depot_id, depot_code, name, provider) values (1, 'tr', 'Trade Republic', 'TR')");
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, is_deleted) values ('DE000C', 'Test Stock', 'tr', 5, false)");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (1, 1, 'DE000C', 25.00, 'monthly', true)");
		jdbcTemplate.update("insert into snapshots (snapshot_id, depot_id, as_of_date, source, file_hash) values (12, 1, ?, 'TR_PDF', 'hash')", LocalDate.now());
		jdbcTemplate.update("update depots set active_snapshot_id = 12 where depot_id = 1");
		jdbcTemplate.update("insert into snapshot_positions (snapshot_id, isin, name, value_eur, currency) values (12, 'DE000C', 'Test Stock', 500.00, 'EUR')");

		String yaml = "schema_version: 1\n"
				+ "name: default\n"
				+ "mode: layer scoring\n"
				+ "rules:\n"
				+ "  - id: stock-layer\n"
				+ "    priority: 10\n"
				+ "    score: 80\n"
				+ "    match:\n"
				+ "      field: name_norm\n"
				+ "      operator: CONTAINS\n"
				+ "      value: stock\n"
				+ "    actions:\n"
				+ "      layer: 1\n";

		jdbcTemplate.update("insert into rulesets (id, name, version, content_yaml, active, created_at, updated_at) values (1, 'default', 1, ?, true, ?, ?)",
			yaml, LocalDateTime.now(), LocalDateTime.now());
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void summaryEndpointReturnsAllocations() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/rebalancer/run")
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.job_id").isString())
				.andReturn();

		String jobId = JsonHelper.read(result, "$.job_id").toString();
		MvcResult doneResult = awaitJob(jobId);
		String json = doneResult.getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(JsonHelper.read(json, "$.status")).isEqualTo("DONE");
		org.assertj.core.api.Assertions.assertThat(JsonHelper.read(json, "$.result.summary.savingPlanSummary.totalActiveAmountEur"))
				.isEqualTo(25.0);
	}

	@Test
	void summaryIncludesInstrumentProposalsWhenKbComplete() throws Exception {
		jdbcTemplate.update("""
				insert into knowledge_base_extractions (isin, status, extracted_json, updated_at)
				values ('DE000C', 'COMPLETE', cast(? as jsonb), ?)
				""", "{\"isin\":\"DE000C\"}", LocalDateTime.now());

		MvcResult result = mockMvc.perform(post("/api/rebalancer/run")
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk())
				.andReturn();
		String jobId = JsonHelper.read(result, "$.job_id").toString();
		awaitJob(jobId);
		mockMvc.perform(get("/api/rebalancer/run/" + jobId)
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.summary.savingPlanProposal.layerBudgets").exists())
				.andExpect(jsonPath("$.result.summary.savingPlanProposal.gating.kbComplete").value(true))
				.andExpect(jsonPath("$.result.summary.savingPlanProposal.instrumentProposals").isArray())
				.andExpect(jsonPath("$.result.summary.savingPlanProposal.instrumentProposals[0].isin").value("DE000C"));
	}

	@Test
	void rebalancerRunsPersistAndLoad() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/rebalancer/run")
						.contentType("application/json")
						.content("{\"saveRun\":true}")
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk())
				.andReturn();
		String jobId = JsonHelper.read(result, "$.job_id").toString();
		MvcResult doneResult = awaitJob(jobId);
		mockMvc.perform(get("/api/rebalancer/run/" + jobId)
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.saved_run.runId").isNumber())
				.andExpect(jsonPath("$.result.saved_run.summary.savingPlanProposal").exists());

		mockMvc.perform(get("/api/rebalancer/runs")
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].runId").isNumber());
	}

	@Test
	void reclassificationsEndpointReturnsResults() throws Exception {
		mockMvc.perform(get("/api/rebalancer/reclassifications?minConfidence=0.0&onlyDifferent=false")
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].isin").value("DE000C"));
	}

	@Configuration
	static class TestConfig {
		@Bean
		NoopLlmClient llmClient() {
			return new NoopLlmClient();
		}
	}

	private static final class JsonHelper {
		private static Object read(MvcResult result, String path) throws Exception {
			return read(result.getResponse().getContentAsString(), path);
		}

		private static Object read(String json, String path) {
			return com.jayway.jsonpath.JsonPath.read(json, path);
		}
	}

	private MvcResult awaitJob(String jobId) throws Exception {
		for (int i = 0; i < 10; i++) {
			MvcResult result = mockMvc.perform(get("/api/rebalancer/run/" + jobId)
							.with(httpBasic("admin", "admin")))
					.andExpect(status().isOk())
					.andReturn();
			String status = JsonHelper.read(result, "$.status").toString();
			if ("DONE".equals(status) || "FAILED".equals(status)) {
				return result;
			}
			Thread.sleep(200L);
		}
		return mockMvc.perform(get("/api/rebalancer/run/" + jobId)
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk())
				.andReturn();
	}
}
