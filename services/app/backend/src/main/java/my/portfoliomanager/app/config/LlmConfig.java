package my.portfoliomanager.app.config;

import my.portfoliomanager.app.llm.ActionRoutedLlmClient;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmProvider;
import my.portfoliomanager.app.llm.LlmClient;
import my.portfoliomanager.app.llm.NoopLlmClient;
import my.portfoliomanager.app.llm.OpenAiLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Configuration
public class LlmConfig {
	private static final Logger logger = LoggerFactory.getLogger(LlmConfig.class);
	private static final String DEFAULT_PROVIDER = "openai";
	private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
	private static final String DEFAULT_MODEL = "gpt-5-mini";
	private static final int MIN_TIMEOUT_SECONDS = 300;
	private static final List<String> LOCAL_HOSTS = List.of("localhost", "127.0.0.1", "::1", "0.0.0.0");

	@Bean
	@ConditionalOnMissingBean(LlmClient.class)
	public LlmClient llmClient(AppProperties properties) {
		ResolvedActionConfig websearch = resolveActionConfig(properties, actionConfig(properties, "websearch"), "websearch");
		ResolvedActionConfig extraction = resolveActionConfig(properties, actionConfig(properties, "extraction"), "extraction");
		ResolvedActionConfig narrative = resolveActionConfig(properties, actionConfig(properties, "narrative"), "narrative");

		LlmClient websearchClient = createActionClient(websearch);
		LlmClient extractionClient = createActionClient(extraction);
		LlmClient narrativeClient = createActionClient(narrative);

		return new ActionRoutedLlmClient(
				websearchClient,
				extractionClient,
				narrativeClient,
				websearch.enabled(),
				extraction.enabled(),
				narrative.enabled(),
				websearch.externalProvider(),
				extraction.externalProvider(),
				narrative.externalProvider()
		);
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

	private AppProperties.Llm.Action actionConfig(AppProperties properties, String actionName) {
		if (properties == null || properties.llm() == null) {
			return null;
		}
		return switch (actionName) {
			case "websearch" -> properties.llm().websearch();
			case "extraction" -> properties.llm().extraction();
			case "narrative" -> properties.llm().narrative();
			default -> null;
		};
	}

	private LlmClient createActionClient(ResolvedActionConfig config) {
		if (config == null || !config.enabled()) {
			String reason = config == null ? "missing configuration" : config.disableReason();
			logger.info("LLM {} action disabled (provider={}, reason={}).",
					config == null ? "unknown" : config.actionName(),
					config == null ? "none" : config.provider(),
					reason);
			return new NoopLlmClient();
		}
		if (!"openai".equals(config.provider())) {
			logger.warn("Unsupported LLM provider '{}' for {} action. Currently only openai is supported; action disabled.",
					config.provider(), config.actionName());
			return new NoopLlmClient();
		}
		logger.info("LLM {} action enabled (provider=openai, model={}).", config.actionName(), config.model());
		return new OpenAiLlmClient(
				config.baseUrl(),
				config.apiKey(),
				config.model(),
				Duration.ofSeconds(config.connectTimeoutSeconds()),
				Duration.ofSeconds(config.readTimeoutSeconds())
		);
	}

	private ResolvedActionConfig resolveActionConfig(AppProperties properties,
										  AppProperties.Llm.Action action,
										  String actionName) {
		AppProperties.Llm llm = properties == null ? null : properties.llm();
		AppProperties.Llm.OpenAi global = llm == null ? null : llm.openai();

		String provider = normalizeProvider(firstNonBlank(
				action == null ? null : action.provider(),
				llm == null ? null : llm.provider(),
				DEFAULT_PROVIDER
		));
		String apiKey = firstNonBlank(
				action == null ? null : action.apiKey(),
				global == null ? null : global.apiKey()
		);
		String baseUrl = firstNonBlank(
				action == null ? null : action.baseUrl(),
				global == null ? null : global.baseUrl(),
				DEFAULT_BASE_URL
		);
		String model = firstNonBlank(
				action == null ? null : action.model(),
				global == null ? null : global.model(),
				DEFAULT_MODEL
		);

		int connectTimeoutSeconds = timeoutOrDefault(global == null ? null : global.connectTimeoutSeconds());
		int readTimeoutSeconds = timeoutOrDefault(global == null ? null : global.readTimeoutSeconds());

		String disableReason = null;
		boolean enabled;
		if (isDisabledProvider(provider)) {
			enabled = false;
			disableReason = "provider disabled";
		} else if (!"openai".equals(provider)) {
			enabled = false;
			disableReason = "provider unsupported";
		} else if (!isValidBaseUrl(baseUrl)) {
			enabled = false;
			disableReason = "invalid base url";
		} else if (apiKey == null || apiKey.isBlank()) {
			enabled = false;
			disableReason = "missing api key";
		} else {
			enabled = true;
		}

		boolean explicitExternal = llm != null && Boolean.TRUE.equals(llm.externalProvider());
		boolean externalProvider = enabled && (explicitExternal || !isLocalBaseUrl(baseUrl));

		return new ResolvedActionConfig(
				actionName,
				provider,
				apiKey,
				baseUrl,
				model,
				connectTimeoutSeconds,
				readTimeoutSeconds,
				enabled,
				externalProvider,
				disableReason
		);
	}

	private int timeoutOrDefault(Integer rawSeconds) {
		if (rawSeconds == null) {
			return MIN_TIMEOUT_SECONDS;
		}
		return Math.max(MIN_TIMEOUT_SECONDS, rawSeconds);
	}

	private String normalizeProvider(String value) {
		String normalized = value == null ? DEFAULT_PROVIDER : value.trim().toLowerCase(Locale.ROOT);
		return normalized.isBlank() ? DEFAULT_PROVIDER : normalized;
	}

	private boolean isDisabledProvider(String value) {
		if (value == null) {
			return false;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		return normalized.equals("none") || normalized.equals("noop") || normalized.equals("disabled") || normalized.equals("off");
	}

	private boolean isLocalBaseUrl(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return false;
		}
		try {
			URI uri = URI.create(baseUrl);
			String host = uri.getHost();
			if (host == null || host.isBlank()) {
				return false;
			}
			String normalized = host.toLowerCase(Locale.ROOT);
			if (LOCAL_HOSTS.contains(normalized)) {
				return true;
			}
			return normalized.endsWith(".local");
		} catch (Exception ex) {
			return false;
		}
	}

	private boolean isValidBaseUrl(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return false;
		}
		try {
			URI uri = URI.create(baseUrl);
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (scheme == null || host == null || host.isBlank()) {
				return false;
			}
			if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
				return false;
			}
			String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
			return normalizedScheme.equals("https") || normalizedScheme.equals("http");
		} catch (Exception ex) {
			return false;
		}
	}

	private String firstNonBlank(String... values) {
		if (values == null || values.length == 0) {
			return null;
		}
		for (String value : values) {
			if (value == null) {
				continue;
			}
			String trimmed = value.trim();
			if (!trimmed.isBlank()) {
				return trimmed;
			}
		}
		return null;
	}

	private record ResolvedActionConfig(
			String actionName,
			String provider,
			String apiKey,
			String baseUrl,
			String model,
			int connectTimeoutSeconds,
			int readTimeoutSeconds,
			boolean enabled,
			boolean externalProvider,
			String disableReason
	) {
	}
}
