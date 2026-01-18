package my.portfoliomanager.app.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import my.portfoliomanager.app.support.TestDatabaseCleaner;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssessorApiIntegrationTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

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
		jdbcTemplate.update("delete from snapshot_positions");
		jdbcTemplate.update("update depots set active_snapshot_id = null");
		jdbcTemplate.update("delete from snapshots");
		jdbcTemplate.update("delete from sparplans_history");
		jdbcTemplate.update("delete from sparplans");
		jdbcTemplate.update("delete from instruments");
		jdbcTemplate.update("delete from depots");

		jdbcTemplate.update("insert into depots (depot_id, depot_code, name, provider) values (1, 'tr', 'Trade Republic', 'TR')");
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, is_deleted) values ('AAA111', 'Core ETF', 'tr', 1, false)");
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, is_deleted) values ('BBB222', 'Bond ETF', 'tr', 2, false)");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (1, 1, 'AAA111', 80.00, 'monthly', true)");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (2, 1, 'BBB222', 20.00, 'monthly', true)");
		jdbcTemplate.update("insert into snapshots (snapshot_id, depot_id, as_of_date, source, file_hash) values (21, 1, ?, 'TR_PDF', 'hash')", LocalDate.now());
		jdbcTemplate.update("update depots set active_snapshot_id = 21 where depot_id = 1");
		jdbcTemplate.update("insert into snapshot_positions (snapshot_id, isin, name, value_eur, currency) values (21, 'AAA111', 'Core ETF', 8000.00, 'EUR')");
		jdbcTemplate.update("insert into snapshot_positions (snapshot_id, isin, name, value_eur, currency) values (21, 'BBB222', 'Bond ETF', 2000.00, 'EUR')");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void runEndpointReturnsAssessment() throws Exception {
		String startPayload = mockMvc.perform(post("/api/assessor/run")
						.with(httpBasic("admin", "admin"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "profile": "BALANCED",
								  "oneTimeAmountEur": 100
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.job_id").exists())
				.andReturn()
				.getResponse()
				.getContentAsString();

		JsonNode startNode = objectMapper.readTree(startPayload);
		String jobId = startNode.path("job_id").asText();

		for (int attempt = 0; attempt < 30; attempt++) {
			String jobPayload = mockMvc.perform(get("/api/assessor/run/{jobId}", jobId)
							.with(httpBasic("admin", "admin")))
					.andExpect(status().isOk())
					.andReturn()
					.getResponse()
					.getContentAsString();

			JsonNode jobNode = objectMapper.readTree(jobPayload);
			String status = jobNode.path("status").asText();
			if ("DONE".equals(status)) {
				JsonNode resultNode = jobNode.path("result");
				assertThat(resultNode.path("selected_profile").asText()).isEqualTo("BALANCED");
				assertThat(resultNode.path("current_monthly_total").asDouble()).isEqualTo(100.0);
				assertThat(resultNode.path("current_layer_distribution").path("1").asDouble()).isEqualTo(80.0);
				assertThat(resultNode.path("saving_plan_suggestions").isArray()).isTrue();
				assertThat(resultNode.path("diagnostics").path("within_tolerance").asBoolean()).isFalse();
				assertThat(resultNode.path("one_time_allocation").path("layer_buckets").isMissingNode()).isFalse();
				return;
			}
			if ("FAILED".equals(status)) {
				fail("Assessor job failed: " + jobNode.path("error").asText());
			}
			Thread.sleep(100);
		}
		fail("Assessor job did not complete in time.");
	}
}
