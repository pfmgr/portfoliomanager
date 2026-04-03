package my.portfoliomanager.app.service;

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

		LlmPromptPolicy policy = new LlmPromptPolicy(objectProvider(support));

		String blocked = policy.validatePrompt("Please use user_id and account_id from context", LlmPromptPurpose.KB_DOSSIER_WEBSEARCH);
		String allowed = policy.validatePrompt("Please use user_id and account_id from context", LlmPromptPurpose.REBALANCER_NARRATIVE);

		assertThat(blocked).isNull();
		assertThat(allowed).isEqualTo("Please use user_id and account_id from context");
	}

	@Test
	void treatsMissingActionSupportAsExternalFailSafe() {
		LlmPromptPolicy policy = new LlmPromptPolicy(objectProvider(null));

		assertThat(policy.isExternalProvider(LlmPromptPurpose.REBALANCER_NARRATIVE)).isTrue();
		assertThat(policy.isExternalProvider(LlmPromptPurpose.KB_DOSSIER_WEBSEARCH)).isTrue();
		assertThat(policy.validatePrompt("Please use user_id and account_id from context", LlmPromptPurpose.KB_DOSSIER_WEBSEARCH)).isNull();
	}

	private ObjectProvider<LlmActionSupport> objectProvider(LlmActionSupport support) {
		@SuppressWarnings("unchecked")
		ObjectProvider<LlmActionSupport> provider = Mockito.mock(ObjectProvider.class);
		Mockito.when(provider.getIfAvailable()).thenReturn(support);
		return provider;
	}
}
