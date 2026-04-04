package my.portfoliomanager.app.service;

import my.portfoliomanager.app.llm.LlmClient;
import my.portfoliomanager.app.llm.LlmActionSupport;
import my.portfoliomanager.app.llm.LlmActionType;
import my.portfoliomanager.app.llm.NoopLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class DefaultLlmNarrativeService implements LlmNarrativeService {
	private static final Logger logger = LoggerFactory.getLogger(DefaultLlmNarrativeService.class);
	private final LlmClient llmClient;
	private final LlmActionSupport llmActionSupport;
	private final boolean fallbackEnabled;

	public DefaultLlmNarrativeService(LlmClient llmClient, ObjectProvider<LlmActionSupport> llmActionSupportProvider) {
		this.llmClient = llmClient;
		this.llmActionSupport = llmActionSupportProvider == null ? null : llmActionSupportProvider.getIfAvailable();
		this.fallbackEnabled = !(llmClient instanceof NoopLlmClient);
	}

	@Override
	public boolean isEnabled() {
		if (llmActionSupport != null) {
			return llmActionSupport.isConfiguredFor(LlmActionType.NARRATIVE);
		}
		return fallbackEnabled;
	}

	@Override
	public String suggestSavingPlanNarrative(String prompt) {
		if (!isEnabled()) {
			return null;
		}
		try {
			logger.info("Sending savings plan narrative request to LLM (promptChars={}).", prompt.length());
			var suggestion = llmClient.suggestSavingPlanProposal(prompt);
			if (suggestion == null || suggestion.suggestion() == null || suggestion.suggestion().isBlank()) {
				return null;
			}
			String narrative = suggestion.suggestion().trim();
			if (narrative.isBlank()) {
				return null;
			}
			logger.info("LLM client returned savings plan narrative.");
			logger.debug("LLM savings plan narrative: {}", narrative);
			return narrative;
		} catch (Exception ex) {
			logger.debug("LLM savings plan narrative request failed: {}", ex.getMessage());
			return null;
		}
	}
}
