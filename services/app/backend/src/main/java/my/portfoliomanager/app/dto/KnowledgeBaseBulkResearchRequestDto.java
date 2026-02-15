package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record KnowledgeBaseBulkResearchRequestDto(
		@JsonProperty("isins")
		@NotEmpty
		@Size(max = 200)
		List<@NotBlank @Pattern(regexp = "^[A-Za-z]{2}[A-Za-z0-9]{9}[0-9]$", message = "must be a valid ISIN") String> isins,
		@JsonProperty("autoApprove") Boolean autoApprove,
		@JsonProperty("applyToOverrides") Boolean applyToOverrides
) {
}
