package my.portfoliomanager.app.llm;

import my.portfoliomanager.app.service.LlmRuntimeConfigService;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DbBackedLlmClient implements LlmClient, KnowledgeBaseLlmProvider, LlmActionSupport {
	private static final int TIMEOUT_SECONDS = 300;

	private final LlmRuntimeConfigService configService;

	public DbBackedLlmClient(LlmRuntimeConfigService configService) {
		this.configService = configService;
	}

	@Override
	public LlmSuggestion suggestReclassification(String context) {
		return clientFor(LlmActionType.NARRATIVE).suggestReclassification(context);
	}

	@Override
	public LlmSuggestion suggestSavingPlanProposal(String context) {
		return clientFor(LlmActionType.NARRATIVE).suggestSavingPlanProposal(context);
	}

	@Override
	public LlmSuggestion extractInstrumentDossierFields(String context) {
		return clientFor(LlmActionType.EXTRACTION).extractInstrumentDossierFields(context);
	}

	@Override
	public LlmSuggestion createInstrumentDossierViaWebSearch(String context) {
		return clientFor(LlmActionType.WEBSEARCH).createInstrumentDossierViaWebSearch(context);
	}

	@Override
	public LlmSuggestion createInstrumentDossierViaWebSearch(String context, String reasoningEffort) {
		return clientFor(LlmActionType.WEBSEARCH).createInstrumentDossierViaWebSearch(context, reasoningEffort);
	}

	@Override
	public LlmSuggestion createInstrumentDossierViaWebSearch(String context, String schemaName, Map<String, Object> schema) {
		return clientFor(LlmActionType.WEBSEARCH).createInstrumentDossierViaWebSearch(context, schemaName, schema);
	}

	@Override
	public LlmSuggestion createInstrumentDossierViaWebSearch(String context,
													 String schemaName,
													 Map<String, Object> schema,
													 String reasoningEffort) {
		return clientFor(LlmActionType.WEBSEARCH).createInstrumentDossierViaWebSearch(context, schemaName, schema, reasoningEffort);
	}

	@Override
	public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains) {
		return providerFor(LlmActionType.WEBSEARCH).runWebSearch(prompt, allowedDomains);
	}

	@Override
	public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains, String reasoningEffort) {
		return providerFor(LlmActionType.WEBSEARCH).runWebSearch(prompt, allowedDomains, reasoningEffort);
	}

	@Override
	public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains, String schemaName, Map<String, Object> schema) {
		return providerFor(LlmActionType.WEBSEARCH).runWebSearch(prompt, allowedDomains, schemaName, schema);
	}

	@Override
	public KnowledgeBaseLlmResponse runWebSearch(String prompt,
													 List<String> allowedDomains,
													 String reasoningEffort,
													 String schemaName,
													 Map<String, Object> schema) {
		return providerFor(LlmActionType.WEBSEARCH).runWebSearch(prompt, allowedDomains, reasoningEffort, schemaName, schema);
	}

	@Override
	public KnowledgeBaseLlmResponse runJsonPrompt(String prompt) {
		return providerFor(LlmActionType.EXTRACTION).runJsonPrompt(prompt);
	}

	@Override
	public KnowledgeBaseLlmResponse runJsonPrompt(String prompt, String schemaName, Map<String, Object> schema) {
		return providerFor(LlmActionType.EXTRACTION).runJsonPrompt(prompt, schemaName, schema);
	}

	@Override
	public boolean isConfiguredFor(LlmActionType actionType) {
		if (actionType == null) {
			return false;
		}
		return configService.resolveAction(actionType).enabled();
	}

	@Override
	public boolean isExternalProviderFor(LlmActionType actionType) {
		if (actionType == null) {
			return false;
		}
		return configService.resolveAction(actionType).externalProvider();
	}

	private LlmClient clientFor(LlmActionType actionType) {
		LlmRuntimeConfigService.ResolvedActionConfig config = configService.resolveAction(actionType);
		if (!config.enabled() || !LlmRuntimeConfigService.DEFAULT_PROVIDER.equals(config.provider())) {
			return new NoopLlmClient();
		}
		return new OpenAiLlmClient(
				config.baseUrl(),
				config.apiKey(),
				config.model(),
				Duration.ofSeconds(TIMEOUT_SECONDS),
				Duration.ofSeconds(TIMEOUT_SECONDS)
		);
	}

	private KnowledgeBaseLlmProvider providerFor(LlmActionType actionType) {
		LlmClient client = clientFor(actionType);
		if (client instanceof KnowledgeBaseLlmProvider provider) {
			return provider;
		}
		return new NoopLlmClient();
	}
}
