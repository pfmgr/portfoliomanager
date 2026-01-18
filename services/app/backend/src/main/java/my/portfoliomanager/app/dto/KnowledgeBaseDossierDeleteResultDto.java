package my.portfoliomanager.app.dto;

public record KnowledgeBaseDossierDeleteResultDto(
		int isinsRequested,
		int dossiersDeleted,
		int extractionsDeleted,
		int knowledgeBaseExtractionsDeleted
) {
}
