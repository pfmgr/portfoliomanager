package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LlmRuntimeConfigUpdateDto(
		@JsonProperty("standard") StandardUpdateDto standard,
		@JsonProperty("websearch") ActionUpdateDto websearch,
		@JsonProperty("extraction") ActionUpdateDto extraction,
		@JsonProperty("narrative") ActionUpdateDto narrative
) {
	public record StandardUpdateDto(
			@JsonProperty("provider") String provider,
			@JsonProperty("base_url") String baseUrl,
			@JsonProperty("model") String model,
			@JsonProperty("api_key") String apiKey
	) {
	}

	public record ActionUpdateDto(
			@JsonProperty("mode") String mode,
			@JsonProperty("provider") String provider,
			@JsonProperty("base_url") String baseUrl,
			@JsonProperty("model") String model,
			@JsonProperty("api_key") String apiKey
	) {
	}
}
