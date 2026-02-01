package my.portfoliomanager.app.dto;

public record KnowledgeBaseManualApprovalItemDto(
		String isin,
		KnowledgeBaseManualApprovalDto approval
) {
}
