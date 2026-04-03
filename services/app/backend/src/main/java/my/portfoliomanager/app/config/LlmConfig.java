package my.portfoliomanager.app.config;

import my.portfoliomanager.app.llm.DbBackedLlmClient;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmProvider;
import my.portfoliomanager.app.llm.LlmClient;
import my.portfoliomanager.app.llm.NoopLlmClient;
import my.portfoliomanager.app.service.LlmRuntimeConfigService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class LlmConfig {
	@Bean
	@ConditionalOnMissingBean(LlmClient.class)
	public LlmClient llmClient(LlmRuntimeConfigService configService) {
		return new DbBackedLlmClient(configService);
	}

	@Bean
	@Primary
	@ConditionalOnMissingBean(KnowledgeBaseLlmProvider.class)
	public KnowledgeBaseLlmProvider knowledgeBaseLlmProvider(LlmClient llmClient) {
		if (llmClient instanceof KnowledgeBaseLlmProvider provider) {
			return provider;
		}
		return new NoopLlmClient();
	}
}
