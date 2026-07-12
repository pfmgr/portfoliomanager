package my.portfoliomanager.app.service;

import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchItemDto;
import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchItemStatus;
import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchJobResponseDto;
import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchJobStatus;
import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchResultDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionStatus;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionTrigger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Legacy wire-format adapter. Lifecycle and jobId are the canonical durable action. */
@Service
public class KnowledgeBaseBulkWebsearchJobService {
	private final KnowledgeBaseLlmActionService actionService;

	public KnowledgeBaseBulkWebsearchJobService(KnowledgeBaseLlmActionService actionService) { this.actionService = actionService; }

	public InstrumentDossierBulkWebsearchJobResponseDto start(List<String> isins, String createdBy) {
		return toDto(actionService.startBulkResearch(isins, false, false, createdBy, KnowledgeBaseLlmActionTrigger.USER));
	}

	public InstrumentDossierBulkWebsearchJobResponseDto get(String jobId) {
		try { return toDto(actionService.getAction(jobId)); }
		catch (ResponseStatusException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bulk websearch job not found"); }
	}

	private InstrumentDossierBulkWebsearchJobResponseDto toDto(KnowledgeBaseLlmActionDto action) {
		int total = action.isins() == null ? 0 : action.isins().size();
		int completed = action.childCompleted() == null ? 0 : action.childCompleted();
		int failed = action.childFailed() == null ? 0 : action.childFailed();
		List<InstrumentDossierBulkWebsearchItemDto> items = List.of();
		int canceled = action.childCanceled() == null ? 0 : action.childCanceled();
		InstrumentDossierBulkWebsearchResultDto result = new InstrumentDossierBulkWebsearchResultDto(total, completed + failed + canceled, completed, 0, failed, items);
		InstrumentDossierBulkWebsearchJobStatus status = legacyStatus(action.status());
		return new InstrumentDossierBulkWebsearchJobResponseDto(action.actionId(), status, result, action.errorCode());
	}

	private InstrumentDossierBulkWebsearchJobStatus legacyStatus(KnowledgeBaseLlmActionStatus status) {
		return switch (status) {
			case QUEUED -> InstrumentDossierBulkWebsearchJobStatus.PENDING;
			case RUNNING, WAITING_RETRY -> InstrumentDossierBulkWebsearchJobStatus.RUNNING;
			case COMPLETED, DONE, REVIEW_REQUIRED -> InstrumentDossierBulkWebsearchJobStatus.DONE;
			case FAILED -> InstrumentDossierBulkWebsearchJobStatus.FAILED;
			case CANCELED -> InstrumentDossierBulkWebsearchJobStatus.CANCELED;
		};
	}
}
