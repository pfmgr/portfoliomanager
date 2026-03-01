package my.portfoliomanager.app.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
		Security security,
		Jwt jwt,
		Llm llm,
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

	public record Llm(
			@NotBlank String provider,
			OpenAi openai,
			Boolean externalProvider,
			Action websearch,
			Action extraction,
			Action narrative
	) {
		public record OpenAi(
				String apiKey,
				String baseUrl,
				String model,
				Integer connectTimeoutSeconds,
				Integer readTimeoutSeconds
		) {
		}

		public record Action(
				String provider,
				String apiKey,
				String baseUrl,
				String model
		) {
		}
	}

	public record Kb(
			boolean enabled,
			boolean llmEnabled
	) {
	}
}
