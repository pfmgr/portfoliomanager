package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record AssessorInstrumentAssessmentDto(
		@JsonProperty("amount_eur") Double amountEur,
		@JsonProperty("score_cutoff") Double scoreCutoff,
		@JsonProperty("risk_thresholds") LayerTargetRiskThresholdsDto riskThresholds,
		@JsonProperty("risk_thresholds_by_layer") Map<Integer, LayerTargetRiskThresholdsDto> riskThresholdsByLayer,
		@JsonProperty("items") List<AssessorInstrumentAssessmentItemDto> items,
		@JsonProperty("missing_kb_isins") List<String> missingKbIsins,
		@JsonProperty("narrative") String narrative
) {
}
