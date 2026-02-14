package my.portfoliomanager.app.service;

import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.llm.LlmClient;
import my.portfoliomanager.app.llm.NoopLlmClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeBaseAvailabilityService {
	private final AppProperties properties;
	private final boolean llmConfigured;
	private final boolean kbEnabled;
	private final boolean llmEnabled;

	public KnowledgeBaseAvailabilityService(AppProperties properties, LlmClient llmClient) {
		this.properties = properties;
		this.llmConfigured = !(llmClient instanceof NoopLlmClient);
		this.kbEnabled = properties.kb() != null && properties.kb().enabled();
		this.llmEnabled = properties.kb() != null && properties.kb().llmEnabled();
	}

	public boolean isEnabled() {
		return kbEnabled;
	}

	public boolean isLlmAvailable() {
		return kbEnabled && llmEnabled && llmConfigured;
	}

	public boolean isLlmConfigured() {
		return llmConfigured;
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
}
