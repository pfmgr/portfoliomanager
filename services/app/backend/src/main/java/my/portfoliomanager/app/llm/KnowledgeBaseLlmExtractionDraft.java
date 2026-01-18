package my.portfoliomanager.app.llm;

import tools.jackson.databind.JsonNode;

public record KnowledgeBaseLlmExtractionDraft(
		JsonNode extractionJson,
		String model
) {
}
