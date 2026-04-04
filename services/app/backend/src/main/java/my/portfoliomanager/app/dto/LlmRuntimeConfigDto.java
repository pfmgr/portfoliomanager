package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LlmRuntimeConfigDto(
		@JsonProperty("editable") boolean editable,
		@JsonProperty("password_set") boolean passwordSet,
		@JsonProperty("standard") StandardConfigDto standard,
		@JsonProperty("websearch") ActionConfigDto websearch,
		@JsonProperty("extraction") ActionConfigDto extraction,
		@JsonProperty("narrative") ActionConfigDto narrative
) {
	public record StandardConfigDto(
			@JsonProperty("provider") String provider,
			@JsonProperty("base_url") String baseUrl,
			@JsonProperty("model") String model,
			@JsonProperty("api_key_set") boolean apiKeySet
	) {
	}

	public record ActionConfigDto(
			@JsonProperty("mode") String mode,
			@JsonProperty("provider") String provider,
			@JsonProperty("base_url") String baseUrl,
			@JsonProperty("model") String model,
			@JsonProperty("api_key_set") boolean apiKeySet,
			@JsonProperty("enabled") boolean enabled,
			@JsonProperty("disable_reason") String disableReason
	) {
	}
}
