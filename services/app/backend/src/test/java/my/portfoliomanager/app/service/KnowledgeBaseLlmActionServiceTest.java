package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.KnowledgeBaseRun;
import my.portfoliomanager.app.llm.LlmRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseLlmActionServiceTest {

	@Test
	void classifyProviderFailureUsesTypedMetadataAndCapsRetryAfter() {
		KnowledgeBaseLlmActionService service = service(30);
		LlmRequestException ex = new LlmRequestException(
				"OpenAI responses API returned HTTP 429",
				429,
				true,
				Duration.ofSeconds(120),
				"req-123",
				null
		);

		KnowledgeBaseLlmActionService.FailureClassification failure = service.classify(ex);

		assertThat(failure.retryable()).isTrue();
		assertThat(failure.code()).isEqualTo("RATE_LIMIT");
		assertThat(failure.requestId()).isEqualTo("req-123");
		assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(120));
		assertThat(service.retryDelay(failure)).isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void classifyTimeoutLikeFailureIsRetryable() {
		KnowledgeBaseLlmActionService service = service(30);
		LlmRequestException ex = new LlmRequestException(
				"OpenAI request failed",
				null,
				true,
				null,
				null,
				new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out"))
		);

		KnowledgeBaseLlmActionService.FailureClassification failure = service.classify(ex);

		assertThat(failure.retryable()).isTrue();
		assertThat(failure.code()).isEqualTo("TIMEOUT");
		assertThat(failure.requestId()).isNull();
	}

	@Test
	void classifyUnknownErrorStaysNonRetryable() {
		KnowledgeBaseLlmActionService service = service(30);

		KnowledgeBaseLlmActionService.FailureClassification failure = service.classify(new IllegalStateException("provider unavailable"));

		assertThat(failure.retryable()).isFalse();
		assertThat(failure.code()).isEqualTo("ACTION_FAILED");
		assertThat(failure.retryAfter()).isNull();
	}

	private KnowledgeBaseLlmActionService service(int maxBackoffSeconds) {
		KnowledgeBaseMaintenanceService maintenanceService = mock(KnowledgeBaseMaintenanceService.class);
		KnowledgeBaseRefreshService refreshService = mock(KnowledgeBaseRefreshService.class);
		KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
		my.portfoliomanager.app.repository.InstrumentDossierRepository dossierRepository = mock(my.portfoliomanager.app.repository.InstrumentDossierRepository.class);
		my.portfoliomanager.app.repository.InstrumentDossierExtractionRepository extractionRepository = mock(my.portfoliomanager.app.repository.InstrumentDossierExtractionRepository.class);
		my.portfoliomanager.app.repository.KnowledgeBaseAlternativeRepository alternativeRepository = mock(my.portfoliomanager.app.repository.KnowledgeBaseAlternativeRepository.class);
		KnowledgeBaseRunService runService = mock(KnowledgeBaseRunService.class);
		KnowledgeBaseConfigService configService = mock(KnowledgeBaseConfigService.class);
		when(configService.getSnapshot()).thenReturn(new KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot(
				false,
				30,
				false,
				false,
				false,
				10,
				120000,
				2,
				5,
				300,
				100,
				3,
				2,
				maxBackoffSeconds,
				15000,
				7,
				30,
				"low",
				List.of(),
				2,
				true,
				0.6,
				true,
				2,
				null
		));
		return new KnowledgeBaseLlmActionService(maintenanceService, refreshService, knowledgeBaseService, dossierRepository, extractionRepository, alternativeRepository, runService, configService);
	}
}
