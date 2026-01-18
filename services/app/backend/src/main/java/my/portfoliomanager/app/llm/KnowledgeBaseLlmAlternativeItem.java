package my.portfoliomanager.app.llm;

import com.fasterxml.jackson.databind.JsonNode;

public record KnowledgeBaseLlmAlternativeItem(
		String isin,
		String rationale,
		JsonNode citations
) {
}
