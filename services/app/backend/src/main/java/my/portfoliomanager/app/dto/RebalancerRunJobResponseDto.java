package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RebalancerRunJobResponseDto(
		@JsonProperty("job_id") String jobId,
		@JsonProperty("status") RebalancerRunJobStatus status,
		@JsonProperty("result") RebalancerRunResponseDto result,
		@JsonProperty("error") String error
) {
}
