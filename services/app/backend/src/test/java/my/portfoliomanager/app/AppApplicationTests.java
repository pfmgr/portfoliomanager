package my.portfoliomanager.app;

import my.portfoliomanager.app.llm.NoopLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

@SpringBootTest
@ActiveProfiles("test")
@Import(AppApplicationTests.TestConfig.class)
class AppApplicationTests {

	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
	}

	@Test
	void contextLoads() {
	}

	@Configuration
	static class TestConfig {
		@Bean
		NoopLlmClient llmClient() {
			return new NoopLlmClient();
		}
	}
}
