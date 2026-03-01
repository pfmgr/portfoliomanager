package my.portfoliomanager.app.service;

import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.llm.LlmActionSupport;
import my.portfoliomanager.app.llm.LlmActionType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPromptPolicyTest {
	@Test
	void blocksSensitiveMarkersForExternalWebsearchOnly() {
		LlmActionSupport support = Mockito.mock(LlmActionSupport.class);
		Mockito.when(support.isExternalProviderFor(LlmActionType.WEBSEARCH)).thenReturn(true);
		Mockito.when(support.isExternalProviderFor(LlmActionType.NARRATIVE)).thenReturn(false);
		Mockito.when(support.isExternalProviderFor(LlmActionType.EXTRACTION)).thenReturn(false);

		LlmPromptPolicy policy = new LlmPromptPolicy(buildProperties(), objectProvider(support));

		String blocked = policy.validatePrompt("Please use user_id and account_id from context", LlmPromptPurpose.KB_DOSSIER_WEBSEARCH);
		String allowed = policy.validatePrompt("Please use user_id and account_id from context", LlmPromptPurpose.REBALANCER_NARRATIVE);

		assertThat(blocked).isNull();
		assertThat(allowed).isEqualTo("Please use user_id and account_id from context");
	}

	@Test
	void fallsBackToLocalBaseUrlCheckWhenNoActionSupport() {
		AppProperties.Security security = new AppProperties.Security("admin", "admin");
		AppProperties.Jwt jwt = new AppProperties.Jwt("secret", "hash-secret", "issuer", 3600L, 300L, 1000, true);
		AppProperties.Llm.OpenAi openAi = new AppProperties.Llm.OpenAi("key", "http://localhost:1234", "model", 300, 300);
		AppProperties.Llm.Action websearch = new AppProperties.Llm.Action("openai", "key", "https://api.openai.com/v1", "model");
		AppProperties.Llm llm = new AppProperties.Llm("openai", openAi, false, websearch, null, null);
		AppProperties.Kb kb = new AppProperties.Kb(true, true);
		LlmPromptPolicy policy = new LlmPromptPolicy(new AppProperties(security, jwt, llm, kb), objectProvider(null));

		assertThat(policy.isExternalProvider(LlmPromptPurpose.REBALANCER_NARRATIVE)).isFalse();
		assertThat(policy.isExternalProvider(LlmPromptPurpose.KB_DOSSIER_WEBSEARCH)).isTrue();
	}

	private AppProperties buildProperties() {
		AppProperties.Security security = new AppProperties.Security("admin", "admin");
		AppProperties.Jwt jwt = new AppProperties.Jwt("secret", "hash-secret", "issuer", 3600L, 300L, 1000, true);
		AppProperties.Llm.OpenAi openAi = new AppProperties.Llm.OpenAi("key", "https://api.openai.com/v1", "model", 300, 300);
		AppProperties.Llm llm = new AppProperties.Llm("openai", openAi, false, null, null, null);
		AppProperties.Kb kb = new AppProperties.Kb(true, true);
		return new AppProperties(security, jwt, llm, kb);
	}

	private ObjectProvider<LlmActionSupport> objectProvider(LlmActionSupport support) {
		@SuppressWarnings("unchecked")
		ObjectProvider<LlmActionSupport> provider = Mockito.mock(ObjectProvider.class);
		Mockito.when(provider.getIfAvailable()).thenReturn(support);
		return provider;
	}
}
