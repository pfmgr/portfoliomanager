package my.portfoliomanager.app.llm;

import java.util.Map;
import java.util.List;

public class ActionRoutedLlmClient implements LlmClient, KnowledgeBaseLlmProvider, LlmActionSupport {
	private final LlmClient websearchClient;
	private final LlmClient extractionClient;
	private final LlmClient narrativeClient;
	private final KnowledgeBaseLlmProvider websearchProvider;
	private final KnowledgeBaseLlmProvider extractionProvider;
	private final boolean websearchConfigured;
	private final boolean extractionConfigured;
	private final boolean narrativeConfigured;
	private final boolean websearchExternal;
	private final boolean extractionExternal;
	private final boolean narrativeExternal;

	public ActionRoutedLlmClient(LlmClient websearchClient,
								  LlmClient extractionClient,
								  LlmClient narrativeClient,
								  boolean websearchConfigured,
								  boolean extractionConfigured,
								  boolean narrativeConfigured,
								  boolean websearchExternal,
								  boolean extractionExternal,
								  boolean narrativeExternal) {
		this.websearchClient = websearchClient == null ? new NoopLlmClient() : websearchClient;
		this.extractionClient = extractionClient == null ? new NoopLlmClient() : extractionClient;
		this.narrativeClient = narrativeClient == null ? new NoopLlmClient() : narrativeClient;
		this.websearchProvider = this.websearchClient instanceof KnowledgeBaseLlmProvider provider
				? provider
				: new NoopLlmClient();
		this.extractionProvider = this.extractionClient instanceof KnowledgeBaseLlmProvider provider
				? provider
				: new NoopLlmClient();
		this.websearchConfigured = websearchConfigured;
		this.extractionConfigured = extractionConfigured;
		this.narrativeConfigured = narrativeConfigured;
		this.websearchExternal = websearchExternal;
		this.extractionExternal = extractionExternal;
		this.narrativeExternal = narrativeExternal;
	}

	@Override
	public LlmSuggestion suggestReclassification(String context) {
		return narrativeClient.suggestReclassification(context);
	}

	@Override
	public LlmSuggestion suggestSavingPlanProposal(String context) {
		return narrativeClient.suggestSavingPlanProposal(context);
	}

	@Override
	public LlmSuggestion extractInstrumentDossierFields(String context) {
		return extractionClient.extractInstrumentDossierFields(context);
	}

	@Override
	public LlmSuggestion createInstrumentDossierViaWebSearch(String context) {
		return websearchClient.createInstrumentDossierViaWebSearch(context);
	}

	@Override
	public LlmSuggestion createInstrumentDossierViaWebSearch(String context, String reasoningEffort) {
		return websearchClient.createInstrumentDossierViaWebSearch(context, reasoningEffort);
	}

	@Override
	public LlmSuggestion createInstrumentDossierViaWebSearch(String context,
												 String schemaName,
												 Map<String, Object> schema) {
		return websearchClient.createInstrumentDossierViaWebSearch(context, schemaName, schema);
	}

	@Override
	public LlmSuggestion createInstrumentDossierViaWebSearch(String context,
												 String schemaName,
												 Map<String, Object> schema,
												 String reasoningEffort) {
		return websearchClient.createInstrumentDossierViaWebSearch(context, schemaName, schema, reasoningEffort);
	}

	@Override
	public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains) {
		return websearchProvider.runWebSearch(prompt, allowedDomains);
	}

	@Override
	public KnowledgeBaseLlmResponse runWebSearch(String prompt,
												 List<String> allowedDomains,
												 String reasoningEffort) {
		return websearchProvider.runWebSearch(prompt, allowedDomains, reasoningEffort);
	}

	@Override
	public KnowledgeBaseLlmResponse runWebSearch(String prompt,
												 List<String> allowedDomains,
												 String schemaName,
												 Map<String, Object> schema) {
		return websearchProvider.runWebSearch(prompt, allowedDomains, schemaName, schema);
	}

	@Override
	public KnowledgeBaseLlmResponse runWebSearch(String prompt,
												 List<String> allowedDomains,
												 String reasoningEffort,
												 String schemaName,
												 Map<String, Object> schema) {
		return websearchProvider.runWebSearch(prompt, allowedDomains, reasoningEffort, schemaName, schema);
	}

	@Override
	public KnowledgeBaseLlmResponse runJsonPrompt(String prompt) {
		return extractionProvider.runJsonPrompt(prompt);
	}

	@Override
	public KnowledgeBaseLlmResponse runJsonPrompt(String prompt, String schemaName, Map<String, Object> schema) {
		return extractionProvider.runJsonPrompt(prompt, schemaName, schema);
	}

	@Override
	public boolean isConfiguredFor(LlmActionType actionType) {
		if (actionType == null) {
			return false;
		}
		return switch (actionType) {
			case WEBSEARCH -> websearchConfigured;
			case EXTRACTION -> extractionConfigured;
			case NARRATIVE -> narrativeConfigured;
		};
	}

	@Override
	public boolean isExternalProviderFor(LlmActionType actionType) {
		if (actionType == null) {
			return false;
		}
		return switch (actionType) {
			case WEBSEARCH -> websearchExternal;
			case EXTRACTION -> extractionExternal;
			case NARRATIVE -> narrativeExternal;
		};
	}
}
