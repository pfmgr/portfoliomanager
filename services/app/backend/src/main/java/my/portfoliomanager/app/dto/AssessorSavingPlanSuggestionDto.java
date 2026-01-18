package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AssessorSavingPlanSuggestionDto(
		@JsonProperty("type") String type,
		@JsonProperty("isin") String isin,
		@JsonProperty("instrument_name") String instrumentName,
		@JsonProperty("layer") Integer layer,
		@JsonProperty("depot_id") Long depotId,
		@JsonProperty("depot_name") String depotName,
		@JsonProperty("old_amount") Double oldAmount,
		@JsonProperty("new_amount") Double newAmount,
		@JsonProperty("delta") Double delta,
		@JsonProperty("rationale") String rationale
) {
}
