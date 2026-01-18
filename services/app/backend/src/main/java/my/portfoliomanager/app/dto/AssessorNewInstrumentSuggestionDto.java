package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AssessorNewInstrumentSuggestionDto(
		@JsonProperty("isin") String isin,
		@JsonProperty("instrument_name") String instrumentName,
		@JsonProperty("layer") Integer layer,
		@JsonProperty("amount") Double amount,
		@JsonProperty("action") String action,
		@JsonProperty("rationale") String rationale
) {
}
