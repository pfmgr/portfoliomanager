package my.portfoliomanager.app.llm;

import java.util.Map;

public interface LlmClient {
	LlmSuggestion suggestReclassification(String context);

	LlmSuggestion suggestSavingPlanProposal(String context);

	default LlmSuggestion extractInstrumentDossierFields(String context) {
		return new LlmSuggestion("", "LLM dossier extraction not supported");
	}

	default LlmSuggestion createInstrumentDossierViaWebSearch(String context) {
		return new LlmSuggestion("", "LLM websearch not supported");
	}

	default LlmSuggestion createInstrumentDossierViaWebSearch(String context, String reasoningEffort) {
		return createInstrumentDossierViaWebSearch(context);
	}

	default LlmSuggestion createInstrumentDossierViaWebSearch(String context, String schemaName, Map<String, Object> schema) {
		return createInstrumentDossierViaWebSearch(context);
	}

	default LlmSuggestion createInstrumentDossierViaWebSearch(String context,
															 String schemaName,
															 Map<String, Object> schema,
															 String reasoningEffort) {
		return createInstrumentDossierViaWebSearch(context, schemaName, schema);
	}
}
