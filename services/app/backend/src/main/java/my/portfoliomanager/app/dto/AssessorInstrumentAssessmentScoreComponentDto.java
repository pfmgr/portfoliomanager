package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AssessorInstrumentAssessmentScoreComponentDto(
		@JsonProperty("criterion") String criterion,
		@JsonProperty("points") Double points
) {
}
