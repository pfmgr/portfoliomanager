package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record KnowledgeBaseBulkResearchRequestDto(
		@JsonProperty("isins") @NotEmpty List<String> isins,
		@JsonProperty("autoApprove") Boolean autoApprove,
		@JsonProperty("applyToOverrides") Boolean applyToOverrides
) {
}
