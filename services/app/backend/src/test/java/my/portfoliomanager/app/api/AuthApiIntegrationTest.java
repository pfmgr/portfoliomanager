package my.portfoliomanager.app.api;

import my.portfoliomanager.app.llm.NoopLlmClient;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
@Import(AuthApiIntegrationTest.TestConfig.class)
class AuthApiIntegrationTest {
	private static final String JWT_SECRET = "0123456789abcdef0123456789abcdef";

	private MockMvc mockMvc;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
	}

	@Test
	void tokenEndpointReturnsJwt() throws Exception {
		String body = "{\"username\":\"admin\",\"password\":\"admin\"}";

		mockMvc.perform(post("/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andExpect(jsonPath("$.tokenType").value("Bearer"));
	}

	@Test
	void tokenEndpointRejectsInvalidCredentials() throws Exception {
		String body = "{\"username\":\"admin\",\"password\":\"bad\"}";

		mockMvc.perform(post("/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest());
	}

	@Test
	void jwtTokenAllowsAccessToProtectedApi() throws Exception {
		String body = "{\"username\":\"admin\",\"password\":\"admin\"}";

		String tokenResponse = mockMvc.perform(post("/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		String token = JsonPath.read(tokenResponse, "$.token");

		mockMvc.perform(get("/api/rulesets")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
	}

	@org.junit.jupiter.api.AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void protectedApiRequiresJwtToken() throws Exception {
		mockMvc.perform(get("/api/rulesets"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void logoutEndpointReturnsUnauthorized() throws Exception {
		mockMvc.perform(get("/auth/logout"))
				.andExpect(status().isUnauthorized());
	}

	@Configuration
	static class TestConfig {
		@Bean
		NoopLlmClient llmClient() {
			return new NoopLlmClient();
		}
	}
}
