package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AssessorRunJobResponseDto(
		@JsonProperty("job_id") String jobId,
		@JsonProperty("status") AssessorRunJobStatus status,
		@JsonProperty("result") AssessorRunResponseDto result,
		@JsonProperty("error") String error
) {
}
