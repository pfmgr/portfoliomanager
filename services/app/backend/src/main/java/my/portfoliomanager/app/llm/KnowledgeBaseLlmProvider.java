package my.portfoliomanager.app.llm;

import java.util.List;
import java.util.Map;

public interface KnowledgeBaseLlmProvider {
	KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains);

	default KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains, String schemaName, Map<String, Object> schema) {
		return runWebSearch(prompt, allowedDomains);
	}

	KnowledgeBaseLlmResponse runJsonPrompt(String prompt);

	default KnowledgeBaseLlmResponse runJsonPrompt(String prompt, String schemaName, Map<String, Object> schema) {
		return runJsonPrompt(prompt);
	}
}
