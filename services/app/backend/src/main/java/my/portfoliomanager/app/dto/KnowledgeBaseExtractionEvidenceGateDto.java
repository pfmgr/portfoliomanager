package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KnowledgeBaseExtractionEvidenceGateDto(
		@JsonProperty("passed") boolean passed,
		@JsonProperty("missing_evidence") List<String> missingEvidence
) {
}
