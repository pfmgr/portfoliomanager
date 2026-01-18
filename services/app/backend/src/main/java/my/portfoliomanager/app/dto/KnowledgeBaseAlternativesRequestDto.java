package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KnowledgeBaseAlternativesRequestDto(
		@JsonProperty("autoApprove") Boolean autoApprove
) {
}
