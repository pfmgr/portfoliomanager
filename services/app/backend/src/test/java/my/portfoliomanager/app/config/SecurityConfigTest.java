package my.portfoliomanager.app.config;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {
	@Test
	void userDetailsServiceRejectsBlankAdminPass() {
		SecurityConfig config = new SecurityConfig(buildProperties(" ", "0123456789abcdef0123456789abcdef", "abcdef0123456789abcdef0123456789"));

		assertThatThrownBy(() -> config.userDetailsService(new BCryptPasswordEncoder()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void jwtSecretKeyRejectsBlankSecret() {
		SecurityConfig config = new SecurityConfig(buildProperties("admin", " ", "abcdef0123456789abcdef0123456789"));

		assertThatThrownBy(config::jwtSecretKey)
				.isInstanceOf(IllegalStateException.class);
	}

 	@Test
	void jwtJtiHashKeyRejectsBlankSecret() {
		SecurityConfig config = new SecurityConfig(buildProperties("admin", "0123456789abcdef0123456789abcdef", " "));

		assertThatThrownBy(config::jwtJtiHashKey)
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void jwtSecretKeyRejectsTooShortSecret() {
		SecurityConfig config = new SecurityConfig(buildProperties("admin", "short-secret", "abcdef0123456789abcdef0123456789"));

		assertThatThrownBy(config::jwtSecretKey)
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void jwtJtiHashKeyRejectsTooShortSecret() {
		SecurityConfig config = new SecurityConfig(buildProperties("admin", "0123456789abcdef0123456789abcdef", "short-secret"));

		assertThatThrownBy(config::jwtJtiHashKey)
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void jwtJtiHashKeyRejectsSameAsSigningSecret() {
		String secret = "0123456789abcdef0123456789abcdef";
		SecurityConfig config = new SecurityConfig(buildProperties("admin", secret, secret));

		assertThatThrownBy(config::jwtJtiHashKey)
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void jwtKeysAcceptConfiguredSecrets() {
		String signingSecret = "0123456789abcdef0123456789abcdef";
		String jtiHashSecret = "abcdef0123456789abcdef0123456789";
		SecurityConfig config = new SecurityConfig(buildProperties("admin", signingSecret, jtiHashSecret));

		assertThat(config.jwtSecretKey().getEncoded()).isEqualTo(signingSecret.getBytes(StandardCharsets.UTF_8));
		assertThat(config.jwtJtiHashKey().getEncoded()).isEqualTo(jtiHashSecret.getBytes(StandardCharsets.UTF_8));
	}

	private AppProperties buildProperties(String adminPass, String jwtSecret, String jtiHashSecret) {
		AppProperties.Security security = new AppProperties.Security("admin", adminPass);
		AppProperties.Jwt jwt = new AppProperties.Jwt(jwtSecret, jtiHashSecret, "issuer", 3600L, 300L, 1000, true);
		AppProperties.Llm.OpenAi openAi = new AppProperties.Llm.OpenAi(null, null, null, null, null);
		AppProperties.Llm llm = new AppProperties.Llm("none", openAi, false);
		AppProperties.Kb kb = new AppProperties.Kb(false, false);
		return new AppProperties(security, jwt, llm, kb);
	}
}
