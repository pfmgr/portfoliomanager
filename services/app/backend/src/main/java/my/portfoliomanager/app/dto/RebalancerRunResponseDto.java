package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RebalancerRunResponseDto(
		@JsonProperty("summary") AdvisorSummaryDto summary,
		@JsonProperty("saved_run") AdvisorRunDetailDto savedRun
) {
}
