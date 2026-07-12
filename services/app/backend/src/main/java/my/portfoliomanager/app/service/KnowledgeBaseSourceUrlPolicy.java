package my.portfoliomanager.app.service;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class KnowledgeBaseSourceUrlPolicy {

	private static final int MAX_REDACTED_LENGTH = 512;
	private static final Pattern TOKEN_SHAPED_VALUE_RE = Pattern.compile(
			"(?i)\\b(?:sk-[A-Za-z0-9_-]{8,}|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}|[A-Fa-f0-9]{32,})\\b"
	);
	private static final Pattern QUERY_SECRET_RE = Pattern.compile(
			"(?i)([?&](?:api[_-]?key|access[_-]?token|auth[_-]?token|token|secret|password|pass|key)=)([^&#\\s]+)"
	);

	private KnowledgeBaseSourceUrlPolicy() {
	}

	public static String normalizeConfiguredDomain(String rawDomain) {
		ValidatedHost host = validateHostCandidate(rawDomain, false);
		if (host == null || !isPublicHost(host.asciiHost())) {
			return null;
		}
		return host.asciiHost();
	}

	public static boolean matchesAllowedDomain(String host, List<String> allowedDomains) {
		String candidate = normalizeHost(host);
		if (candidate == null || allowedDomains == null || allowedDomains.isEmpty()) {
			return false;
		}
		for (String allowed : allowedDomains) {
			String normalizedAllowed = normalizeConfiguredDomain(allowed);
			if (normalizedAllowed == null) {
				continue;
			}
			if (candidate.equals(normalizedAllowed) || candidate.endsWith("." + normalizedAllowed)) {
				return true;
			}
		}
		return false;
	}

	public static ValidatedCitationUrl validateCitationUrl(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			return null;
		}
		try {
			URI uri = URI.create(rawUrl.trim());
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) {
				return null;
			}
			String hostValue = uri.getHost();
			if (hostValue == null) {
				hostValue = hostFromAuthority(uri.getRawAuthority());
			}
			ValidatedHost host = validateHostCandidate(hostValue, true);
			if (host == null || !isPublicHost(host.asciiHost())) {
				return null;
			}
			String path = uri.getRawPath();
			if (path == null || path.isBlank()) {
				path = "/";
			}
			URI normalized = new URI(
					"https",
					null,
					host.asciiHost(),
					uri.getPort() < 0 ? -1 : uri.getPort(),
					path,
					uri.getRawQuery(),
					null
			);
			return new ValidatedCitationUrl(host.asciiHost(), normalized.toString());
		} catch (IllegalArgumentException | URISyntaxException ex) {
			return null;
		}
	}

	private static String hostFromAuthority(String authority) {
		if (authority == null || authority.isBlank()) {
			return null;
		}
		String cleaned = authority;
		int at = cleaned.lastIndexOf('@');
		if (at >= 0) {
			cleaned = cleaned.substring(at + 1);
		}
		if (cleaned.startsWith("[")) {
			int end = cleaned.indexOf(']');
			return end > 1 ? cleaned.substring(1, end) : null;
		}
		int colon = cleaned.lastIndexOf(':');
		if (colon > 0) {
			cleaned = cleaned.substring(0, colon);
		}
		return cleaned;
	}

	public static String normalizeHost(String host) {
		ValidatedHost validated = validateHostCandidate(host, false);
		return validated == null ? null : validated.asciiHost();
	}

	public static String redactSensitiveText(String value) {
		if (value == null || value.isBlank()) {
			return value;
		}
		String redacted = QUERY_SECRET_RE.matcher(value).replaceAll("$1[REDACTED]");
		redacted = TOKEN_SHAPED_VALUE_RE.matcher(redacted).replaceAll("[REDACTED]");
		redacted = redacted.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._\\-+/=]{16,}", "$1[REDACTED]");
		redacted = redacted.replaceAll("[\\r\\n]+", " ").trim();
		return redacted.length() > MAX_REDACTED_LENGTH ? redacted.substring(0, MAX_REDACTED_LENGTH) : redacted;
	}

	public static String bound(String value, int maxChars) {
		if (value == null) {
			return null;
		}
		int limit = Math.max(0, maxChars);
		if (value.length() <= limit) {
			return value;
		}
		return value.substring(0, limit);
	}

	private static ValidatedHost validateHostCandidate(String host, boolean requireAsciiNormalization) {
		if (host == null || host.isBlank()) {
			return null;
		}
		String trimmed = host.trim().toLowerCase(Locale.ROOT);
		if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
			trimmed = trimmed.substring(1, trimmed.length() - 1);
		}
		if (trimmed.contains("@") || trimmed.contains("/") || trimmed.contains("?") || trimmed.contains("#") || trimmed.contains(" ")) {
			return null;
		}
		if (trimmed.endsWith(".")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		if (trimmed.isBlank() || trimmed.contains("..")) {
			return null;
		}
		String ascii = toAscii(trimmed, requireAsciiNormalization);
		return ascii == null ? null : new ValidatedHost(ascii);
	}

	private static String toAscii(String host, boolean strict) {
		try {
			String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
			if (ascii.isBlank() || ascii.contains("@") || ascii.contains("/") || ascii.contains("?") || ascii.contains("#")) {
				return null;
			}
			return ascii;
		} catch (IllegalArgumentException ex) {
			return strict ? null : null;
		}
	}

	private static boolean isPublicHost(String host) {
		if (host == null || host.isBlank()) {
			return false;
		}
		String normalized = host.toLowerCase(Locale.ROOT).trim();
		if (normalized.equals("localhost")
				|| normalized.endsWith(".localhost")
				|| normalized.endsWith(".local")
				|| normalized.endsWith(".internal")
				|| normalized.endsWith(".nip.io")
				|| normalized.endsWith(".sslip.io")
				|| normalized.endsWith(".xip.io")
				|| normalized.endsWith(".test")
				|| normalized.endsWith(".invalid")) {
			return false;
		}
		if (!normalized.contains(".") && !normalized.contains(":")) {
			return false;
		}
		if (normalized.contains("@") || normalized.contains("/") || normalized.contains("?")) {
			return false;
		}
		if (normalized.matches("^[0-9.]+$")) {
			return false;
		}
		return true;
	}

	public record ValidatedCitationUrl(String host, String canonicalUrl) {
	}

	private record ValidatedHost(String asciiHost) {
	}
}
