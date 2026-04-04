package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LlmConfigBackupDto(
		@JsonProperty("standard") StandardBackupDto standard,
		@JsonProperty("websearch") ActionBackupDto websearch,
		@JsonProperty("extraction") ActionBackupDto extraction,
		@JsonProperty("narrative") ActionBackupDto narrative
) {
	public record StandardBackupDto(
			@JsonProperty("provider") String provider,
			@JsonProperty("base_url") String baseUrl,
			@JsonProperty("model") String model,
			@JsonProperty("api_key") String apiKey
	) {
	}

	public record ActionBackupDto(
			@JsonProperty("mode") String mode,
			@JsonProperty("provider") String provider,
			@JsonProperty("base_url") String baseUrl,
			@JsonProperty("model") String model,
			@JsonProperty("api_key") String apiKey
	) {
	}
}
