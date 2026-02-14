package my.portfoliomanager.app.llm;

import java.util.List;

public interface KnowledgeBaseLlmClient {
	KnowledgeBaseLlmDossierDraft generateDossier(String isin,
										 String context,
										 List<String> allowedDomains,
										 int maxChars);

	KnowledgeBaseLlmDossierDraft patchDossierMissingFields(String isin,
										 String contentMd,
										 tools.jackson.databind.JsonNode existingCitations,
										 List<String> missingFields,
										 String context,
										 List<String> allowedDomains,
										 int maxChars);

	KnowledgeBaseLlmExtractionDraft extractMetadata(String dossierText);

	KnowledgeBaseLlmAlternativesDraft findAlternatives(String isin, List<String> allowedDomains);
}
