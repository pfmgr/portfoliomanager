package my.portfoliomanager.app.dto;

public record KnowledgeBaseRefreshItemDto(
		String isin,
		KnowledgeBaseBulkResearchItemStatus status,
		Long dossierId,
		Long extractionId,
		String error,
		KnowledgeBaseManualApprovalDto manualApproval
) {
}
