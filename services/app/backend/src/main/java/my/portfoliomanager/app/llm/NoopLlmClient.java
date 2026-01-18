package my.portfoliomanager.app.llm;

import java.util.List;

public class NoopLlmClient implements LlmClient, KnowledgeBaseLlmProvider {
	@Override
	public LlmSuggestion suggestReclassification(String context) {
		return new LlmSuggestion("", "LLM disabled");
	}

	@Override
	public LlmSuggestion suggestSavingPlanProposal(String context) {
		return new LlmSuggestion("", "LLM disabled");
	}

	@Override
	public LlmSuggestion extractInstrumentDossierFields(String context) {
		return new LlmSuggestion("", "LLM disabled");
	}

	@Override
	public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains) {
		throw new LlmRequestException("LLM disabled", null, false, null);
	}

	@Override
	public KnowledgeBaseLlmResponse runJsonPrompt(String prompt) {
		throw new LlmRequestException("LLM disabled", null, false, null);
	}
}
