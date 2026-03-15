package my.portfoliomanager.app.llm;

import java.util.List;

public class NoopLlmClient implements LlmClient, KnowledgeBaseLlmProvider {
	private static final String LLM_DISABLED = "LLM disabled";

	@Override
	public LlmSuggestion suggestReclassification(String context) {
		return new LlmSuggestion("", LLM_DISABLED);
	}

	@Override
	public LlmSuggestion suggestSavingPlanProposal(String context) {
		return new LlmSuggestion("", LLM_DISABLED);
	}

	@Override
	public LlmSuggestion extractInstrumentDossierFields(String context) {
		return new LlmSuggestion("", LLM_DISABLED);
	}

	@Override
	public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains) {
		throw new LlmRequestException(LLM_DISABLED, null, false, null);
	}

	@Override
	public KnowledgeBaseLlmResponse runJsonPrompt(String prompt) {
		throw new LlmRequestException(LLM_DISABLED, null, false, null);
	}
}
