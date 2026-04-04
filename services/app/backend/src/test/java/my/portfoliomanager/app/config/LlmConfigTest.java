package my.portfoliomanager.app.config;

import my.portfoliomanager.app.llm.DbBackedLlmClient;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmProvider;
import my.portfoliomanager.app.llm.LlmClient;
import my.portfoliomanager.app.llm.NoopLlmClient;
import my.portfoliomanager.app.service.LlmRuntimeConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigTest {
	@Test
	void createsDbBackedClientBean() {
		LlmRuntimeConfigService runtimeConfigService = Mockito.mock(LlmRuntimeConfigService.class);
		LlmClient llmClient = new LlmConfig().llmClient(runtimeConfigService);

		assertThat(llmClient).isInstanceOf(DbBackedLlmClient.class);
	}

	@Test
	void exposesKnowledgeBaseProviderWhenClientImplementsProvider() {
		LlmRuntimeConfigService runtimeConfigService = Mockito.mock(LlmRuntimeConfigService.class);
		LlmClient llmClient = new LlmConfig().llmClient(runtimeConfigService);

		KnowledgeBaseLlmProvider provider = new LlmConfig().knowledgeBaseLlmProvider(llmClient);
		assertThat(provider).isSameAs(llmClient);
	}

	@Test
	void fallsBackToNoopProviderWhenClientDoesNotSupportKnowledgeBase() {
		KnowledgeBaseLlmProvider provider = new LlmConfig().knowledgeBaseLlmProvider(Mockito.mock(LlmClient.class));
		assertThat(provider).isInstanceOf(NoopLlmClient.class);
	}
}
