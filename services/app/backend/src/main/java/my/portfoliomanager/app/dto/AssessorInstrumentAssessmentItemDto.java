package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AssessorInstrumentAssessmentItemDto(
		@JsonProperty("isin") String isin,
		@JsonProperty("instrument_name") String instrumentName,
		@JsonProperty("layer") Integer layer,
		@JsonProperty("score") Integer score,
		@JsonProperty("risk_category") String riskCategory,
		@JsonProperty("allocation") Double allocation,
		@JsonProperty("score_components") List<AssessorInstrumentAssessmentScoreComponentDto> scoreComponents
) {
}
