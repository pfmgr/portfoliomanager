package my.portfoliomanager.app.service;

import my.portfoliomanager.app.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class LlmPromptPolicy {
	private static final Logger logger = LoggerFactory.getLogger(LlmPromptPolicy.class);
	private static final List<Pattern> SENSITIVE_MARKERS = List.of(
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
	private static final List<String> LOCAL_HOSTS = List.of("localhost", "127.0.0.1", "::1", "0.0.0.0");

	private final AppProperties properties;

	public LlmPromptPolicy(AppProperties properties) {
		this.properties = properties;
	}

	public String validatePrompt(String prompt, LlmPromptPurpose purpose) {
		if (!isExternalProvider() || prompt == null || prompt.isBlank()) {
			return prompt;
		}
		List<String> violations = new ArrayList<>();
		for (Pattern pattern : SENSITIVE_MARKERS) {
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

	public boolean isExternalProvider() {
		if (properties == null || properties.llm() == null) {
			return false;
		}
		Boolean explicit = properties.llm().externalProvider();
		if (Boolean.TRUE.equals(explicit)) {
			return true;
		}
		String provider = safeLower(properties.llm().provider());
		if (provider.isBlank() || provider.equals("none") || provider.equals("noop") || provider.equals("disabled")) {
			return false;
		}
		String baseUrl = properties.llm().openai() == null ? null : properties.llm().openai().baseUrl();
		return !isLocalBaseUrl(baseUrl);
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

	private String safeLower(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}
}
