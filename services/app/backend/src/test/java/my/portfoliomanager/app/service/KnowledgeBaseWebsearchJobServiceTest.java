package my.portfoliomanager.app.service;

import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchJobResponseDto;
import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchJobStatus;
import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchResultDto;
import my.portfoliomanager.app.dto.InstrumentDossierWebsearchJobResponseDto;
import my.portfoliomanager.app.dto.InstrumentDossierWebsearchJobStatus;
import my.portfoliomanager.app.dto.InstrumentDossierWebsearchResponseDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionStatus;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionTrigger;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseWebsearchJobServiceTest {

	@Test
	void legacySingleWebsearchAdapterUsesCanonicalPersistedActionId() {
		KnowledgeBaseLlmActionService actionService = mock(KnowledgeBaseLlmActionService.class);
		KnowledgeBaseWebsearchJobService service = new KnowledgeBaseWebsearchJobService(actionService);

		KnowledgeBaseLlmActionDto queued = actionDto("run-123", KnowledgeBaseLlmActionStatus.QUEUED, List.of("DE0000000001"), null, null, null, null);
		KnowledgeBaseLlmActionDto completed = actionDto("run-123", KnowledgeBaseLlmActionStatus.COMPLETED, List.of("DE0000000001"), null, null, null, null);
		when(actionService.startLegacyWebsearch("DE0000000001", "alice")).thenReturn(queued);
		when(actionService.getAction("run-123")).thenReturn(completed);
		when(actionService.legacyWebsearchResult("run-123")).thenReturn(new InstrumentDossierWebsearchResponseDto("# result", "Instrument", null, "test-model"));

		InstrumentDossierWebsearchJobResponseDto started = service.start("DE0000000001", "alice");
		InstrumentDossierWebsearchJobResponseDto loaded = service.get("run-123");

		assertThat(started.jobId()).isEqualTo("run-123");
		assertThat(started.status()).isEqualTo(InstrumentDossierWebsearchJobStatus.PENDING);
		assertThat(loaded.jobId()).isEqualTo("run-123");
		assertThat(loaded.status()).isEqualTo(InstrumentDossierWebsearchJobStatus.DONE);
		assertThat(loaded.result()).isNotNull();
		verify(actionService).startLegacyWebsearch("DE0000000001", "alice");
		verify(actionService).getAction("run-123");
		verify(actionService).legacyWebsearchResult("run-123");
	}

	@Test
	void legacyStatusMappingDoesNotLeaveRetryOrCancellationPendingAndPreservesBulkProgress() {
		KnowledgeBaseLlmActionService actionService = mock(KnowledgeBaseLlmActionService.class);
		KnowledgeBaseWebsearchJobService single = new KnowledgeBaseWebsearchJobService(actionService);
		KnowledgeBaseBulkWebsearchJobService bulk = new KnowledgeBaseBulkWebsearchJobService(actionService);
		when(actionService.getAction("retry")).thenReturn(actionDto("retry", KnowledgeBaseLlmActionStatus.WAITING_RETRY, List.of("DE0000000001"), null, null, null, null));
		when(actionService.getAction("cancel")).thenReturn(actionDto("cancel", KnowledgeBaseLlmActionStatus.CANCELED, List.of("DE0000000001", "DE0000000002", "DE0000000003"), 3, 1, 1, 1));

		assertThat(single.get("retry").status()).isEqualTo(InstrumentDossierWebsearchJobStatus.RUNNING);
		InstrumentDossierBulkWebsearchJobResponseDto canceled = bulk.get("cancel");
		assertThat(canceled.status()).isEqualTo(InstrumentDossierBulkWebsearchJobStatus.CANCELED);
		assertThat(canceled.result()).isEqualTo(new InstrumentDossierBulkWebsearchResultDto(3, 3, 1, 0, 1, List.of()));
	}

	@Test
	void legacyBulkWebsearchAdapterUsesCanonicalPersistedActionId() {
		KnowledgeBaseLlmActionService actionService = mock(KnowledgeBaseLlmActionService.class);
		KnowledgeBaseBulkWebsearchJobService service = new KnowledgeBaseBulkWebsearchJobService(actionService);

		KnowledgeBaseLlmActionDto action = actionDto("bulk-456", KnowledgeBaseLlmActionStatus.COMPLETED, List.of("DE0000000001", "DE0000000002"), 2, 2, 0, 0);
		when(actionService.startBulkResearch(List.of("DE0000000001", "DE0000000002"), false, false, "system", KnowledgeBaseLlmActionTrigger.USER)).thenReturn(action);
		when(actionService.getAction("bulk-456")).thenReturn(action);

		InstrumentDossierBulkWebsearchJobResponseDto started = service.start(List.of("DE0000000001", "DE0000000002"), "system");
		InstrumentDossierBulkWebsearchJobResponseDto loaded = service.get("bulk-456");

		assertThat(started.jobId()).isEqualTo("bulk-456");
		assertThat(started.status()).isEqualTo(InstrumentDossierBulkWebsearchJobStatus.DONE);
		assertThat(started.result()).isEqualTo(new InstrumentDossierBulkWebsearchResultDto(2, 2, 2, 0, 0, List.of()));
		assertThat(loaded.jobId()).isEqualTo("bulk-456");
		assertThat(loaded.status()).isEqualTo(InstrumentDossierBulkWebsearchJobStatus.DONE);
		verify(actionService).startBulkResearch(List.of("DE0000000001", "DE0000000002"), false, false, "system", KnowledgeBaseLlmActionTrigger.USER);
		verify(actionService).getAction("bulk-456");
	}

	private KnowledgeBaseLlmActionDto actionDto(String actionId,
										KnowledgeBaseLlmActionStatus status,
										List<String> isins,
										Integer childTotal,
										Integer childCompleted,
										Integer childFailed,
										Integer childCanceled) {
		return new KnowledgeBaseLlmActionDto(
				actionId,
				KnowledgeBaseLlmActionType.RESEARCH,
				status,
				KnowledgeBaseLlmActionTrigger.USER,
				isins,
				null,
				null,
				"done",
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				childTotal,
				childCompleted,
				childFailed,
				childCanceled
		);
	}
}
