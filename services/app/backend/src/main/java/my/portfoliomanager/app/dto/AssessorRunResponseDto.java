package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record AssessorRunResponseDto(
		@JsonProperty("selected_profile") String selectedProfile,
		@JsonProperty("as_of_date") LocalDate asOfDate,
		@JsonProperty("current_monthly_total") Double currentMonthlyTotal,
		@JsonProperty("current_layer_distribution") Map<Integer, Double> currentLayerDistribution,
		@JsonProperty("target_layer_distribution") Map<Integer, Double> targetLayerDistribution,
		@JsonProperty("saving_plan_suggestions") List<AssessorSavingPlanSuggestionDto> savingPlanSuggestions,
		@JsonProperty("saving_plan_new_instruments") List<AssessorNewInstrumentSuggestionDto> savingPlanNewInstruments,
		@JsonProperty("saving_plan_narrative") String savingPlanNarrative,
		@JsonProperty("one_time_allocation") AssessorOneTimeAllocationDto oneTimeAllocation,
		@JsonProperty("one_time_narrative") String oneTimeNarrative,
		@JsonProperty("diagnostics") AssessorDiagnosticsDto diagnostics
) {
}
