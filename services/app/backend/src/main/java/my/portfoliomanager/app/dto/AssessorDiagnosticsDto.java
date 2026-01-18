package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AssessorDiagnosticsDto(
		@JsonProperty("within_tolerance") boolean withinTolerance,
		@JsonProperty("suppressed_deltas_count") int suppressedDeltasCount,
		@JsonProperty("suppressed_amount_total") Double suppressedAmountTotal,
		@JsonProperty("redistribution_notes") List<String> redistributionNotes,
		@JsonProperty("kb_enabled") boolean kbEnabled,
		@JsonProperty("kb_complete") boolean kbComplete,
		@JsonProperty("missing_kb_isins") List<String> missingKbIsins
) {
}
