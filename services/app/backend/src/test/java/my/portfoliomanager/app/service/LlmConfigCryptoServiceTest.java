package my.portfoliomanager.app.service;

import my.portfoliomanager.app.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmConfigCryptoServiceTest {
	@Test
	void encryptsWithRandomSaltAndIvAndDecryptsBack() {
		LlmConfigCryptoService service = new LlmConfigCryptoService(buildProperties("encryption-password"));

		String encryptedOne = service.encrypt("my-secret-key");
		String encryptedTwo = service.encrypt("my-secret-key");

		assertThat(encryptedOne).isNotEqualTo(encryptedTwo);
		assertThat(service.decrypt(encryptedOne)).isEqualTo("my-secret-key");
		assertThat(service.decrypt(encryptedTwo)).isEqualTo("my-secret-key");
	}

	@Test
	void returnsNullWhenPasswordMissing() {
		LlmConfigCryptoService service = new LlmConfigCryptoService(buildProperties(""));

		assertThat(service.decrypt("payload")).isNull();
		assertThatThrownBy(() -> service.encrypt("my-secret-key"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("encryption password");
	}

	private AppProperties buildProperties(String password) {
		AppProperties.Security security = new AppProperties.Security("admin", "admin");
		AppProperties.Jwt jwt = new AppProperties.Jwt("secretsecretsecretsecretsecretsecret", "hashhashhashhashhashhashhashhash", "issuer", 3600L, 300L, 1000, true);
		AppProperties.LegacyLlm legacyLlm = new AppProperties.LegacyLlm(null, null, null, null);
		AppProperties.Kb kb = new AppProperties.Kb(true, true);
		return new AppProperties(security, jwt, password, legacyLlm, kb);
	}
}
