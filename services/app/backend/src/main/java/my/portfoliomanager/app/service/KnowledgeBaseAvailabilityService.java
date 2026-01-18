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

	public KnowledgeBaseAvailabilityService(AppProperties properties, LlmClient llmClient) {
		this.properties = properties;
		this.llmConfigured = !(llmClient instanceof NoopLlmClient);
	}

	public boolean isAvailable() {
		return properties.kb() != null && properties.kb().enabled() && llmConfigured;
	}

	public boolean isLlmConfigured() {
		return llmConfigured;
	}

	public void assertAvailable() {
		if (!isAvailable()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Knowledge Base is disabled. Configure LLM provider and enable KB.");
		}
	}
}
