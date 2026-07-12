package my.portfoliomanager.app.service;

import my.portfoliomanager.app.dto.InstrumentDossierWebsearchJobResponseDto;
import my.portfoliomanager.app.dto.InstrumentDossierWebsearchJobStatus;
import my.portfoliomanager.app.dto.InstrumentDossierWebsearchResponseDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeBaseWebsearchJobService {
	private final KnowledgeBaseLlmActionService actionService;

	public KnowledgeBaseWebsearchJobService(KnowledgeBaseLlmActionService actionService) {
		this.actionService = actionService;
	}

	public InstrumentDossierWebsearchJobResponseDto start(String isin, String actor) {
		return toDto(actionService.startLegacyWebsearch(isin, actor));
	}

	public InstrumentDossierWebsearchJobResponseDto get(String jobId) {
		try { return toDto(actionService.getAction(jobId)); }
		catch (ResponseStatusException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Websearch job not found"); }
	}

	private InstrumentDossierWebsearchJobResponseDto toDto(my.portfoliomanager.app.dto.KnowledgeBaseLlmActionDto job) {
		return new InstrumentDossierWebsearchJobResponseDto(
				job.actionId(), legacyStatus(job.status()),
				job.status() == my.portfoliomanager.app.dto.KnowledgeBaseLlmActionStatus.COMPLETED ? actionService.legacyWebsearchResult(job.actionId()) : null, job.errorCode()
		);
	}

	private InstrumentDossierWebsearchJobStatus legacyStatus(my.portfoliomanager.app.dto.KnowledgeBaseLlmActionStatus status) {
		return switch (status) {
			case QUEUED -> InstrumentDossierWebsearchJobStatus.PENDING;
			case RUNNING, WAITING_RETRY -> InstrumentDossierWebsearchJobStatus.RUNNING;
			case COMPLETED, DONE, REVIEW_REQUIRED -> InstrumentDossierWebsearchJobStatus.DONE;
			case FAILED -> InstrumentDossierWebsearchJobStatus.FAILED;
			case CANCELED -> InstrumentDossierWebsearchJobStatus.CANCELED;
		};
	}
}
