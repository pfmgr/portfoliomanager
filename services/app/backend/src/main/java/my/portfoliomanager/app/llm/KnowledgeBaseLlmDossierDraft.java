package my.portfoliomanager.app.llm;

import com.fasterxml.jackson.databind.JsonNode;

public record KnowledgeBaseLlmDossierDraft(
		String contentMd,
		String displayName,
		JsonNode citations,
		String model
) {
}
