package my.portfoliomanager.app.api;

import my.portfoliomanager.app.llm.NoopLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthApiIntegrationTest.TestConfig.class)
class AuthApiIntegrationTest {
	private static final String JWT_SECRET = "0123456789abcdef0123456789abcdef";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

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
	void logoutEndpointReturnsUnauthorizedChallenge() throws Exception {
		mockMvc.perform(get("/auth/logout"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", containsString("Basic realm=\"Portfolio Admin (Logged out)\"")));
	}

	@Configuration
	static class TestConfig {
		@Bean
		NoopLlmClient llmClient() {
			return new NoopLlmClient();
		}
	}
}
