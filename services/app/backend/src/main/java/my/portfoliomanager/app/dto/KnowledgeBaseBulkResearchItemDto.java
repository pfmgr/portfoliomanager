package my.portfoliomanager.app.dto;

public record KnowledgeBaseBulkResearchItemDto(
		String isin,
		KnowledgeBaseBulkResearchItemStatus status,
		Long dossierId,
		Long extractionId,
		String error
) {
}
