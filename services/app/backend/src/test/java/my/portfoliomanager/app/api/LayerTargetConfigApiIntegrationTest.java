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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import my.portfoliomanager.app.support.TestDatabaseCleaner;

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
@Import(LayerTargetConfigApiIntegrationTest.TestConfig.class)
class LayerTargetConfigApiIntegrationTest {
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

		jdbcTemplate.update("delete from layer_target_config");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void returnsDefaultConfigWhenMissing() throws Exception {
		mockMvc.perform(get("/api/layer-targets")
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.effectiveLayerTargets").isMap())
				.andExpect(jsonPath("$.acceptableVariancePct").isNumber())
				.andExpect(jsonPath("$.minimumSavingPlanSize").value(15))
				.andExpect(jsonPath("$.minimumRebalancingAmount").value(10))
				.andExpect(jsonPath("$.activeProfileKey").value("BALANCED"))
				.andExpect(jsonPath("$.layerNames").isMap())
				.andExpect(jsonPath("$.customOverridesEnabled").value(false));
	}

	@Test
	void savesAndResetsConfig() throws Exception {
		String payload = """
				{
				  "layerTargets": { "1": 0.4, "2": 0.3, "3": 0.2, "4": 0.1, "5": 0.0 },
				  "acceptableVariancePct": 1.5,
				  "minimumSavingPlanSize": 20,
				  "minimumRebalancingAmount": 12
				}
				""";

		mockMvc.perform(put("/api/layer-targets")
						.with(httpBasic("admin", "admin"))
						.contentType("application/json")
						.content(payload))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.effectiveLayerTargets.1").value(0.4))
				.andExpect(jsonPath("$.acceptableVariancePct").value(1.5))
				.andExpect(jsonPath("$.minimumSavingPlanSize").value(20))
				.andExpect(jsonPath("$.minimumRebalancingAmount").value(12))
				.andExpect(jsonPath("$.activeProfileKey").value("BALANCED"))
				.andExpect(jsonPath("$.customOverridesEnabled").value(true));

		mockMvc.perform(post("/api/layer-targets/reset")
						.with(httpBasic("admin", "admin")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.activeProfileKey").value("BALANCED"))
				.andExpect(jsonPath("$.customOverridesEnabled").value(false));
	}

	@Test
	void applyProfileUpdatesTargetConfig() throws Exception {
		String payload = """
				{
				  "activeProfile": "CLASSIC",
				  "customOverridesEnabled": false
				}
				""";

		mockMvc.perform(put("/api/layer-targets")
						.with(httpBasic("admin", "admin"))
						.contentType("application/json")
						.content(payload))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.activeProfileKey").value("CLASSIC"))
				.andExpect(jsonPath("$.customOverridesEnabled").value(false))
				.andExpect(jsonPath("$.effectiveLayerTargets.1").value(0.8))
				.andExpect(jsonPath("$.effectiveLayerTargets.4").value(0.01));
	}

	@Configuration
	static class TestConfig {
		@Bean
		NoopLlmClient llmClient() {
			return new NoopLlmClient();
		}
	}
}
