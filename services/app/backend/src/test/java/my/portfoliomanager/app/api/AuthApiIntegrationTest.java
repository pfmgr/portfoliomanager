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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.service.AuthTokenService;

import java.time.Instant;
import java.util.UUID;

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

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private AuthTokenService authTokenService;

	@Autowired
	private AppProperties properties;

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
		mockMvc.perform(post("/auth/logout"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void tokenWithoutDbEntryIsRejected() throws Exception {
		String jti = UUID.randomUUID().toString();
		Instant now = Instant.now();
		String token = buildJwt(jti, now, now.plusSeconds(300));

		mockMvc.perform(get("/api/rulesets")
					.header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void expiredTokenIsRejectedEvenWhenStored() throws Exception {
		String jti = UUID.randomUUID().toString();
		Instant now = Instant.now();
		Instant issuedAt = now.minusSeconds(7200);
		Instant expiresAt = now.minusSeconds(3600);
		String token = buildJwt(jti, issuedAt, expiresAt);
		authTokenService.storeToken(jti, "admin", issuedAt, expiresAt);

		mockMvc.perform(get("/api/rulesets")
					.header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void logoutRevokesToken() throws Exception {
		String jti = UUID.randomUUID().toString();
		Instant now = Instant.now();
		Instant expiresAt = now.plusSeconds(600);
		String token = buildJwt(jti, now, expiresAt);
		authTokenService.storeToken(jti, "admin", now, expiresAt);

		mockMvc.perform(post("/auth/logout")
					.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/rulesets")
					.header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void logoutAcceptsTokenEvenWhenNotStored() throws Exception {
		String jti = UUID.randomUUID().toString();
		Instant now = Instant.now();
		Instant expiresAt = now.plusSeconds(600);
		String token = buildJwt(jti, now, expiresAt);

		mockMvc.perform(post("/auth/logout")
					.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent());
	}

	@Configuration
	static class TestConfig {
		@Bean
		NoopLlmClient llmClient() {
			return new NoopLlmClient();
		}
	}

	private String buildJwt(String jti, Instant issuedAt, Instant expiresAt) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.jwt().issuer())
				.subject("admin")
				.id(jti)
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.claim("roles", "ADMIN")
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}
}
