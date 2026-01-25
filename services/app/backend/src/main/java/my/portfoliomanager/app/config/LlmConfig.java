package my.portfoliomanager.app.config;

import my.portfoliomanager.app.llm.KnowledgeBaseLlmProvider;
import my.portfoliomanager.app.llm.LlmClient;
import my.portfoliomanager.app.llm.NoopLlmClient;
import my.portfoliomanager.app.llm.OpenAiLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {
	private static final Logger logger = LoggerFactory.getLogger(LlmConfig.class);

	@Bean
	@ConditionalOnProperty(name = "app.llm.provider", havingValue = "openai")
	public OpenAiLlmClient openAiLlmClient(AppProperties properties) {
		String apiKey = properties.llm().openai().apiKey();
		String baseUrl = properties.llm().openai().baseUrl();
		String model = properties.llm().openai().model();
		Integer connectTimeoutSeconds = properties.llm().openai().connectTimeoutSeconds();
		Integer readTimeoutSeconds = properties.llm().openai().readTimeoutSeconds();
		if (model == null || model.isBlank()) {
			model = "gpt-5-mini";
		}
		int connectTimeout = connectTimeoutSeconds == null ? 300 : Math.max(300, connectTimeoutSeconds);
		int readTimeout = readTimeoutSeconds == null ? 300 : Math.max(300, readTimeoutSeconds);
		logger.info("LLM client enabled (provider=openai, model={}).", model);
		return new OpenAiLlmClient(baseUrl, apiKey, model,
				java.time.Duration.ofSeconds(connectTimeout),
				java.time.Duration.ofSeconds(readTimeout));
	}

	@Bean
	@ConditionalOnMissingBean(LlmClient.class)
	public NoopLlmClient noopLlmClient() {
		logger.info("LLM client disabled (provider=noop).");
		return new NoopLlmClient();
	}

	@Bean
	@ConditionalOnMissingBean(KnowledgeBaseLlmProvider.class)
	public KnowledgeBaseLlmProvider knowledgeBaseLlmProvider(LlmClient llmClient) {
		if (llmClient instanceof KnowledgeBaseLlmProvider provider) {
			return provider;
		}
		return new NoopLlmClient();
	}
}
