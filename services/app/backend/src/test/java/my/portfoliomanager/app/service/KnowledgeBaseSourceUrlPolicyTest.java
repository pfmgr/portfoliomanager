package my.portfoliomanager.app.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseSourceUrlPolicyTest {

	@Test
	void validateCitationUrl_rejectsUnsupportedForms() {
		assertThat(KnowledgeBaseSourceUrlPolicy.validateCitationUrl("http://example.com")).isNull();
		assertThat(KnowledgeBaseSourceUrlPolicy.validateCitationUrl("https://user:pass@example.com")).isNull();
		assertThat(KnowledgeBaseSourceUrlPolicy.validateCitationUrl("https://example.com")).isNotNull();
		assertThat(KnowledgeBaseSourceUrlPolicy.matchesAllowedDomain("sub.example.com", List.of("example.com"))).isTrue();
		assertThat(KnowledgeBaseSourceUrlPolicy.matchesAllowedDomain("example.com", List.of("disallowed.com"))).isFalse();
	}

	@Test
	void validateCitationUrl_canonicalizesIdnHosts() {
		var validated = KnowledgeBaseSourceUrlPolicy.validateCitationUrl("https://bücher.example/path");
		assertThat(validated).isNotNull();
		assertThat(validated.host()).isEqualTo("xn--bcher-kva.example");
		assertThat(KnowledgeBaseSourceUrlPolicy.matchesAllowedDomain(validated.host(), List.of("xn--bcher-kva.example"))).isTrue();
	}
}
