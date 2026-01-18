package my.portfoliomanager.app.llm;

import tools.jackson.databind.JsonNode;

public record KnowledgeBaseLlmAlternativeItem(
		String isin,
		String rationale,
		JsonNode citations
) {
}
