package my.portfoliomanager.app.dto;

import tools.jackson.databind.JsonNode;
import my.portfoliomanager.app.domain.KnowledgeBaseAlternativeStatus;

public record KnowledgeBaseAlternativeItemDto(
		String isin,
		String rationale,
		JsonNode citations,
		KnowledgeBaseAlternativeStatus status,
		Long dossierId,
		Long extractionId,
		String error
) {
}
