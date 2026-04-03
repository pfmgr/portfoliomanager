package my.portfoliomanager.app.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
		Security security,
		Jwt jwt,
		String llmConfigEncryptionPassword,
		LegacyLlm legacyLlm,
		Kb kb
) {
	public record Security(
			@NotBlank String adminUser,
			@NotBlank String adminPass
	) {
	}

	public record Jwt(
			@NotBlank String secret,
			@NotBlank String jtiHashSecret,
			@NotBlank String issuer,
			Long expiresInSeconds,
			Long cleanupIntervalSeconds,
			Integer cleanupBatchSize,
			Boolean cleanupEnabled
	) {
	}

	public record LegacyLlm(
			String provider,
			String baseUrl,
			String model,
			String apiKey
	) {
	}

	public record Kb(
			boolean enabled,
			boolean llmEnabled
	) {
	}
}
