package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KnowledgeBaseRefreshBatchRequestDto(
		@JsonProperty("limit") Integer limit,
		@JsonProperty("batchSize") Integer batchSize,
		@JsonProperty("dryRun") Boolean dryRun,
		@JsonProperty("scope") KnowledgeBaseRefreshScopeDto scope
) {
}
