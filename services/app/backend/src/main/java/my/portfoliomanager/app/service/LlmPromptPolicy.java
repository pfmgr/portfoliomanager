package my.portfoliomanager.app.service;

import my.portfoliomanager.app.llm.LlmActionSupport;
import my.portfoliomanager.app.llm.LlmActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class LlmPromptPolicy {
	private static final Logger logger = LoggerFactory.getLogger(LlmPromptPolicy.class);
	private static final List<Pattern> STRICT_SENSITIVE_MARKERS = List.of(
			Pattern.compile("\\biban\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bbic\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\baccount\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\baccount_id\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bdepot_id\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bdepot_code\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bdepot\\s+id\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\buser_id\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\busername\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bpassword\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\btoken\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\baccess_token\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\brefresh_token\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bemail\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\baddress\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bphone\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bclient_id\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bprincipal\\b", Pattern.CASE_INSENSITIVE)
	);
	private static final List<Pattern> KB_SENSITIVE_MARKERS = List.of(
			Pattern.compile("\\biban\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bbic\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\baccount_id\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bdepot_id\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bdepot_code\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bdepot\\s+id\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\buser_id\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\busername\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bpassword\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\btoken\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\baccess_token\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\brefresh_token\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bclient_id\\b", Pattern.CASE_INSENSITIVE)
	);
	private final LlmActionSupport llmActionSupport;

	public LlmPromptPolicy(ObjectProvider<LlmActionSupport> llmActionSupportProvider) {
		this.llmActionSupport = llmActionSupportProvider == null ? null : llmActionSupportProvider.getIfAvailable();
	}

	public String validatePrompt(String prompt, LlmPromptPurpose purpose) {
		if (!isExternalProvider(purpose) || prompt == null || prompt.isBlank()) {
			return prompt;
		}
		List<String> violations = new ArrayList<>();
		for (Pattern pattern : markersForPurpose(purpose)) {
			if (pattern.matcher(prompt).find()) {
				violations.add(pattern.pattern());
			}
		}
		if (!violations.isEmpty()) {
			logger.warn("Blocked LLM prompt for {} due to sensitive markers: {}", purpose, String.join(", ", violations));
			return null;
		}
		return prompt;
	}

	private List<Pattern> markersForPurpose(LlmPromptPurpose purpose) {
		if (purpose == null) {
			return STRICT_SENSITIVE_MARKERS;
		}
		return switch (purpose) {
			case KB_BULK_WEBSEARCH, KB_DOSSIER_WEBSEARCH, KB_DOSSIER_PATCH, KB_DOSSIER_EXTRACTION, KB_ALTERNATIVES_WEBSEARCH ->
					KB_SENSITIVE_MARKERS;
			default -> STRICT_SENSITIVE_MARKERS;
		};
	}

	public boolean isExternalProvider() {
		return isExternalProvider(LlmPromptPurpose.REBALANCER_NARRATIVE);
	}

	public boolean isExternalProvider(LlmPromptPurpose purpose) {
		LlmActionType actionType = actionTypeForPurpose(purpose);
		if (llmActionSupport == null) {
			return true;
		}
		return llmActionSupport.isExternalProviderFor(actionType);
	}

	private LlmActionType actionTypeForPurpose(LlmPromptPurpose purpose) {
		if (purpose == null) {
			return LlmActionType.NARRATIVE;
		}
		return switch (purpose) {
			case KB_BULK_WEBSEARCH, KB_DOSSIER_WEBSEARCH, KB_DOSSIER_PATCH, KB_ALTERNATIVES_WEBSEARCH ->
					LlmActionType.WEBSEARCH;
			case KB_DOSSIER_EXTRACTION -> LlmActionType.EXTRACTION;
			default -> LlmActionType.NARRATIVE;
		};
	}

}
