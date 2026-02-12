package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KnowledgeBaseRefreshRequestDto(
		@JsonProperty("autoApprove") Boolean autoApprove,
		@JsonProperty("force") Boolean force
) {
}
