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
			String secret,
			@NotBlank String issuer
	) {
	}

	public record Llm(
			@NotBlank String provider,
			OpenAi openai,
			Boolean externalProvider
	) {
		public record OpenAi(
				String apiKey,
				String baseUrl,
				String model,
				Integer connectTimeoutSeconds,
				Integer readTimeoutSeconds
		) {
		}
	}

	public record Kb(
			boolean enabled,
			boolean llmEnabled,
			Search search
	) {
		public record Search(
				String provider,
				Searxng searxng,
				Integer maxResults,
				Integer maxSnippetChars,
				Integer timeoutSeconds
		) {
			public record Searxng(
					String baseUrl
			) {
			}
		}
	}
}
