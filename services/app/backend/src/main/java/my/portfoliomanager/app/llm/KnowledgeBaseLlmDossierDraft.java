package my.portfoliomanager.app.llm;

import tools.jackson.databind.JsonNode;

public record KnowledgeBaseLlmDossierDraft(
		String contentMd,
		String displayName,
		JsonNode citations,
		String model
) {
}
