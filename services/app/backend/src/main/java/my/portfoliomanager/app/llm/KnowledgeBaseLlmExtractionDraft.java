package my.portfoliomanager.app.llm;

import com.fasterxml.jackson.databind.JsonNode;

public record KnowledgeBaseLlmExtractionDraft(
		JsonNode extractionJson,
		String model
) {
}
