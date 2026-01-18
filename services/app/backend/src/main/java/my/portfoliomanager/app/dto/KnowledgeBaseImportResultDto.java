package my.portfoliomanager.app.dto;

public record KnowledgeBaseImportResultDto(int dossiersImported,
										   int dossierExtractionsImported,
										   int knowledgeBaseExtractionsImported,
										   int formatVersion,
										   String exportedAt) {
}
