package my.portfoliomanager.app.service;

import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.llm.LlmActionSupport;
import my.portfoliomanager.app.llm.LlmActionType;
import my.portfoliomanager.app.llm.LlmClient;
import my.portfoliomanager.app.llm.NoopLlmClient;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeBaseAvailabilityService {
	private final LlmActionSupport llmActionSupport;
	private final boolean fallbackConfigured;
	private final boolean kbEnabled;
	private final boolean llmEnabled;

	public KnowledgeBaseAvailabilityService(AppProperties properties,
										LlmClient llmClient,
										ObjectProvider<LlmActionSupport> llmActionSupportProvider) {
		this.llmActionSupport = llmActionSupportProvider == null ? null : llmActionSupportProvider.getIfAvailable();
		this.fallbackConfigured = !(llmClient instanceof NoopLlmClient);
		this.kbEnabled = properties.kb() != null && properties.kb().enabled();
		this.llmEnabled = properties.kb() != null && properties.kb().llmEnabled();
	}

	public boolean isEnabled() {
		return kbEnabled;
	}

	public boolean isLlmAvailable() {
		return kbEnabled && llmEnabled && isConfiguredFor(LlmActionType.WEBSEARCH) && isConfiguredFor(LlmActionType.EXTRACTION);
	}

	public boolean isWebsearchAvailable() {
		return kbEnabled && llmEnabled && isConfiguredFor(LlmActionType.WEBSEARCH);
	}

	public boolean isExtractionAvailable() {
		return kbEnabled && llmEnabled && isConfiguredFor(LlmActionType.EXTRACTION);
	}

	public boolean isLlmConfigured() {
		return isConfiguredFor(LlmActionType.WEBSEARCH) && isConfiguredFor(LlmActionType.EXTRACTION);
	}

	private boolean isConfiguredFor(LlmActionType actionType) {
		if (llmActionSupport == null) {
			return fallbackConfigured;
		}
		return llmActionSupport.isConfiguredFor(actionType);
	}

	public void assertEnabled() {
		if (!isEnabled()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Knowledge Base is disabled. Enable KB to use these endpoints.");
		}
	}

	public void assertLlmAvailable() {
		if (!isLlmAvailable()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Knowledge Base LLM features are disabled. Configure an LLM provider and enable KB LLM support.");
		}
	}

	public void assertWebsearchAvailable() {
		if (!isWebsearchAvailable()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Knowledge Base websearch features are disabled. Configure websearch LLM settings and enable KB LLM support.");
		}
	}

	public void assertExtractionAvailable() {
		if (!isExtractionAvailable()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Knowledge Base extraction features are disabled. Configure extraction LLM settings and enable KB LLM support.");
		}
	}
}
