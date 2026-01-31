package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AssessorInstrumentAssessmentItemDto(
		@JsonProperty("isin") String isin,
		@JsonProperty("instrument_name") String instrumentName,
		@JsonProperty("layer") Integer layer,
		@JsonProperty("score") Integer score,
		@JsonProperty("allocation") Double allocation
) {
}
