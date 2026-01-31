package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AssessorInstrumentAssessmentDto(
		@JsonProperty("amount_eur") Double amountEur,
		@JsonProperty("score_cutoff") Integer scoreCutoff,
		@JsonProperty("risk_thresholds") LayerTargetRiskThresholdsDto riskThresholds,
		@JsonProperty("items") List<AssessorInstrumentAssessmentItemDto> items,
		@JsonProperty("missing_kb_isins") List<String> missingKbIsins,
		@JsonProperty("narrative") String narrative
) {
}
