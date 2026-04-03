package my.portfoliomanager.app.service;

import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.llm.LlmActionSupport;
import my.portfoliomanager.app.llm.LlmActionType;
import my.portfoliomanager.app.llm.NoopLlmClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBaseAvailabilityServiceTest {
	@Test
	void reportsPerActionAvailabilityFromActionSupport() {
		LlmActionSupport support = Mockito.mock(LlmActionSupport.class);
		Mockito.when(support.isConfiguredFor(LlmActionType.WEBSEARCH)).thenReturn(true);
		Mockito.when(support.isConfiguredFor(LlmActionType.EXTRACTION)).thenReturn(false);
		ObjectProvider<LlmActionSupport> provider = objectProvider(support);

		KnowledgeBaseAvailabilityService service = new KnowledgeBaseAvailabilityService(
				buildProperties(true, true),
				new NoopLlmClient(),
				provider
		);

		assertThat(service.isWebsearchAvailable()).isTrue();
		assertThat(service.isExtractionAvailable()).isFalse();
		assertThat(service.isLlmAvailable()).isFalse();
	}

	@Test
	void extractionAssertFailsWhenExtractionActionUnavailable() {
		LlmActionSupport support = Mockito.mock(LlmActionSupport.class);
		Mockito.when(support.isConfiguredFor(LlmActionType.WEBSEARCH)).thenReturn(true);
		Mockito.when(support.isConfiguredFor(LlmActionType.EXTRACTION)).thenReturn(false);
		ObjectProvider<LlmActionSupport> provider = objectProvider(support);

		KnowledgeBaseAvailabilityService service = new KnowledgeBaseAvailabilityService(
				buildProperties(true, true),
				new NoopLlmClient(),
				provider
		);

		assertThatThrownBy(service::assertExtractionAvailable)
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("extraction features are disabled");
	}

	@Test
	void fallsBackToLlmClientWhenNoActionSupportBeanExists() {
		ObjectProvider<LlmActionSupport> provider = objectProvider(null);
		KnowledgeBaseAvailabilityService disabled = new KnowledgeBaseAvailabilityService(
				buildProperties(true, true),
				new NoopLlmClient(),
				provider
		);

		assertThat(disabled.isLlmAvailable()).isFalse();
	}

	private ObjectProvider<LlmActionSupport> objectProvider(LlmActionSupport support) {
		@SuppressWarnings("unchecked")
		ObjectProvider<LlmActionSupport> provider = Mockito.mock(ObjectProvider.class);
		Mockito.when(provider.getIfAvailable()).thenReturn(support);
		return provider;
	}

	private AppProperties buildProperties(boolean kbEnabled, boolean kbLlmEnabled) {
		AppProperties.Security security = new AppProperties.Security("admin", "admin");
		AppProperties.Jwt jwt = new AppProperties.Jwt("secret", "hash-secret", "issuer", 3600L, 300L, 1000, true);
		AppProperties.LegacyLlm legacyLlm = new AppProperties.LegacyLlm(null, null, null, null);
		AppProperties.Kb kb = new AppProperties.Kb(kbEnabled, kbLlmEnabled);
		return new AppProperties(security, jwt, "", legacyLlm, kb);
	}
}
