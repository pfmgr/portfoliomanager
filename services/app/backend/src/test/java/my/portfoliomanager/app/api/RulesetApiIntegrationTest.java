package my.portfoliomanager.app.api;

import my.portfoliomanager.app.llm.NoopLlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import my.portfoliomanager.app.support.TestDatabaseCleaner;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
@Import(RulesetApiIntegrationTest.TestConfig.class)
class RulesetApiIntegrationTest {
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
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, is_deleted) values ('DE000A', 'Test ETF', 'tr', 5, false)");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (1, 1, 'DE000A', 50.00, 'monthly', true)");
		jdbcTemplate.update("insert into snapshots (snapshot_id, depot_id, as_of_date, source, file_hash) values (10, 1, ?, 'TR_PDF', 'hash')", LocalDate.now());
		jdbcTemplate.update("update depots set active_snapshot_id = 10 where depot_id = 1");
		jdbcTemplate.update("insert into snapshot_positions (snapshot_id, isin, name, value_eur, currency) values (10, 'DE000A', 'Test ETF', 1000.00, 'EUR')");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void simulateReturnsPolicyAdjustedLayer() throws Exception {
		String rulesetJson = """
				{
				  "schema_version": 1,
				  "name": "default",
				  "mode": "layer scoring",
				  "policies": { "layer1_requires_saving_plan": true },
				  "rules": [
				    {
				      "id": "etf-layer",
				      "priority": 10,
				      "score": 90,
				      "match": { "field": "name_norm", "operator": "CONTAINS", "value": "etf" },
				      "actions": { "layer": 1 }
				    }
				  ]
				}
				""";

		String body = "{\"contentJson\": " + quoteJson(rulesetJson) + "}";

		mockMvc.perform(post("/api/rulesets/default/simulate")
						.with(httpBasic("admin", "admin"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].policyAdjusted.layer").value(1))
				.andExpect(jsonPath("$[0].impact.valueEur").value(1000.0));
	}

	@Test
	void policyDowngradesLayerWhenNoSavingPlan() throws Exception {
		jdbcTemplate.update("delete from sparplans");

		String rulesetJson = """
				{
				  "schema_version": 1,
				  "name": "default",
				  "mode": "layer scoring",
				  "policies": { "layer1_requires_saving_plan": true },
				  "rules": [
				    {
				      "id": "etf-layer",
				      "priority": 10,
				      "score": 90,
				      "match": { "field": "name_norm", "operator": "CONTAINS", "value": "etf" },
				      "actions": { "layer": 1 }
				    }
				  ]
				}
				""";

		String body = "{\"contentJson\": " + quoteJson(rulesetJson) + "}";

		mockMvc.perform(post("/api/rulesets/default/simulate")
						.with(httpBasic("admin", "admin"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].policyAdjusted.layer").value(2));
	}

	@Test
	void validateEndpointReportsErrors() throws Exception {
		String body = "{\"contentJson\": \"{\\\"schema_version\\\":99}\"}";

		mockMvc.perform(post("/api/rulesets/default/validate")
						.with(httpBasic("admin", "admin"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.valid").value(false));
	}

	@Test
	void applyEndpointSupportsDryRunAndIsinFilter() throws Exception {
		String rulesetJson = """
				{
				  "schema_version": 1,
				  "name": "default",
				  "mode": "layer scoring",
				  "rules": [
				    {
				      "id": "etf-layer",
				      "priority": 10,
				      "score": 90,
				      "match": { "field": "name_norm", "operator": "CONTAINS", "value": "etf" },
				      "actions": { "layer": 1 }
				    }
				  ]
				}
				""";

		String body = "{\"contentJson\": " + quoteJson(rulesetJson) + ", \"dryRun\": true, \"isins\": [\"DE000A\"]}";

		mockMvc.perform(post("/api/rulesets/default/apply")
						.with(httpBasic("admin", "admin"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].isin").value("DE000A"));
	}

	@Test
	void applyUpdatesExistingInstrumentOverride() throws Exception {
		LocalDate before = LocalDate.of(2000, 1, 1);
		jdbcTemplate.update("""
				insert into instrument_overrides (isin, instrument_type, asset_class, sub_class, layer, layer_last_changed, updated_at)
				values (?, ?, ?, ?, ?, ?, ?)
				""", "DE000A", "OLD_TYPE", "Old Asset", "Old Sub", 5, before, LocalDateTime.now());

		String rulesetJson = """
				{
				  "schema_version": 1,
				  "name": "default",
				  "mode": "layer scoring",
				  "rules": [
				    {
				      "id": "etf-layer",
				      "priority": 10,
				      "score": 90,
				      "match": { "field": "name_norm", "operator": "CONTAINS", "value": "etf" },
				      "actions": {
				        "layer": 1,
				        "instrument_type": "ETF",
				        "asset_class": "Equity",
				        "sub_class": "Index"
				      }
				    }
				  ]
				}
				""";

		String body = "{\"contentJson\": " + quoteJson(rulesetJson) + ", \"dryRun\": false, \"isins\": [\"DE000A\"]}";

		mockMvc.perform(post("/api/rulesets/default/apply")
						.with(httpBasic("admin", "admin"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].isin").value("DE000A"));

		String overrideType = jdbcTemplate.queryForObject(
				"select instrument_type from instrument_overrides where isin = 'DE000A'",
				String.class
		);
		String overrideAsset = jdbcTemplate.queryForObject(
				"select asset_class from instrument_overrides where isin = 'DE000A'",
				String.class
		);
		String overrideSub = jdbcTemplate.queryForObject(
				"select sub_class from instrument_overrides where isin = 'DE000A'",
				String.class
		);
		Integer overrideLayer = jdbcTemplate.queryForObject(
				"select layer from instrument_overrides where isin = 'DE000A'",
				Integer.class
		);
		java.sql.Date layerLastChanged = jdbcTemplate.queryForObject(
				"select layer_last_changed from instrument_overrides where isin = 'DE000A'",
				java.sql.Date.class
		);

		org.assertj.core.api.Assertions.assertThat(overrideType).isEqualTo("ETF");
		org.assertj.core.api.Assertions.assertThat(overrideAsset).isEqualTo("Equity");
		org.assertj.core.api.Assertions.assertThat(overrideSub).isEqualTo("Index");
		org.assertj.core.api.Assertions.assertThat(overrideLayer).isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(layerLastChanged).isNotNull();
		org.assertj.core.api.Assertions.assertThat(layerLastChanged.toLocalDate()).isEqualTo(LocalDate.now());
	}

	@Test
	void simulateWithoutActiveRulesetReturnsBadRequest() throws Exception {
		jdbcTemplate.update("delete from rulesets");

		mockMvc.perform(post("/api/rulesets/default/simulate")
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void getRulesetNotFoundReturnsBadRequest() throws Exception {
		mockMvc.perform(get("/api/rulesets/missing")
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void validateEndpointRequiresContent() throws Exception {
		String body = "{}";

		mockMvc.perform(post("/api/rulesets/default/validate")
						.with(httpBasic("admin", "admin"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors").isArray());
	}

	@Test
	void listRulesetsReturnsItems() throws Exception {
		jdbcTemplate.update("insert into rulesets (id, name, version, content_yaml, active, created_at, updated_at) values (2, 'default', 1, 'schema_version: 1', true, now(), now())");

		mockMvc.perform(get("/api/rulesets")
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("default"));
	}

	@Test
	void getRulesetReturnsLatest() throws Exception {
		jdbcTemplate.update("insert into rulesets (id, name, version, content_yaml, active, created_at, updated_at) values (3, 'default', 2, 'schema_version: 1', false, now(), now())");

		mockMvc.perform(get("/api/rulesets/default")
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(2));
	}

	@Test
	void upsertCreatesNewVersion() throws Exception {
		String rulesetJson = """
				{
				  "schema_version": 1,
				  "name": "default",
				  "mode": "layer scoring",
				  "rules": []
				}
				""";
		String body = "{\"contentJson\": " + quoteJson(rulesetJson) + ", \"activate\": true}";

		mockMvc.perform(put("/api/rulesets/default")
						.with(httpBasic("admin", "admin"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("default"));
	}

	@Test
	void upsertRejectsInvalidYaml() throws Exception {
		String body = "{\"contentJson\": \"{\\\"schema_version\\\":\\\"bad\\\"}\", \"activate\": true}";

		mockMvc.perform(put("/api/rulesets/default")
						.with(httpBasic("admin", "admin"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest());
	}

	private String quoteJson(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	@Configuration
	static class TestConfig {
		@Bean
		NoopLlmClient llmClient() {
			return new NoopLlmClient();
		}
	}
}
