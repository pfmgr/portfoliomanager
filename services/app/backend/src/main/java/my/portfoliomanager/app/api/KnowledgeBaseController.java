package my.portfoliomanager.app.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import my.portfoliomanager.app.domain.DossierStatus;
import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchJobResponseDto;
import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchRequest;
import my.portfoliomanager.app.dto.InstrumentDossierCreateRequest;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionResponseDto;
import my.portfoliomanager.app.dto.InstrumentDossierResponseDto;
import my.portfoliomanager.app.dto.InstrumentDossierSearchPageDto;
import my.portfoliomanager.app.dto.InstrumentDossierUpdateRequest;
import my.portfoliomanager.app.dto.InstrumentDossierWebsearchRequest;
import my.portfoliomanager.app.dto.InstrumentDossierWebsearchJobResponseDto;
import my.portfoliomanager.app.dto.KnowledgeBaseAlternativesRequestDto;
import my.portfoliomanager.app.dto.KnowledgeBaseBulkResearchRequestDto;
import my.portfoliomanager.app.dto.KnowledgeBaseDossierDeleteRequestDto;
import my.portfoliomanager.app.dto.KnowledgeBaseDossierDeleteResultDto;
import my.portfoliomanager.app.dto.KnowledgeBaseDossierDetailDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionTrigger;
import my.portfoliomanager.app.dto.KnowledgeBaseRefreshBatchRequestDto;
import my.portfoliomanager.app.dto.KnowledgeBaseRefreshRequestDto;
import my.portfoliomanager.app.service.KnowledgeBaseAvailabilityService;
import my.portfoliomanager.app.service.KnowledgeBaseBulkWebsearchJobService;
import my.portfoliomanager.app.service.KnowledgeBaseLlmActionService;
import my.portfoliomanager.app.service.KnowledgeBaseService;
import my.portfoliomanager.app.service.KnowledgeBaseWebsearchJobService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/kb")
@Tag(name = "Knowledge Base")
public class KnowledgeBaseController {
	private final KnowledgeBaseService knowledgeBaseService;
	private final KnowledgeBaseLlmActionService actionService;
	private final KnowledgeBaseAvailabilityService availabilityService;
	private final KnowledgeBaseWebsearchJobService websearchJobService;
	private final KnowledgeBaseBulkWebsearchJobService bulkWebsearchJobService;

	public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService,
							   KnowledgeBaseLlmActionService actionService,
							   KnowledgeBaseAvailabilityService availabilityService,
							   KnowledgeBaseWebsearchJobService websearchJobService,
							   KnowledgeBaseBulkWebsearchJobService bulkWebsearchJobService) {
		this.knowledgeBaseService = knowledgeBaseService;
		this.actionService = actionService;
		this.availabilityService = availabilityService;
		this.websearchJobService = websearchJobService;
		this.bulkWebsearchJobService = bulkWebsearchJobService;
	}

	@GetMapping("/dossiers")
	@Operation(summary = "Search knowledge base dossiers")
	public InstrumentDossierSearchPageDto searchDossiers(@RequestParam(required = false) String q,
												 @RequestParam(required = false) String query,
												 @RequestParam(required = false) String status,
												 @RequestParam(required = false) Boolean stale,
												 @RequestParam(required = false, defaultValue = "0") int page,
												 @RequestParam(required = false, defaultValue = "50") int size,
												 @RequestParam(required = false) Integer limit,
												 @RequestParam(required = false) Integer offset) {
		availabilityService.assertAvailable();
		String finalQuery = q != null ? q : query;
		int finalLimit = limit != null ? limit : size;
		int finalOffset = offset != null ? offset : page * finalLimit;
		DossierStatus statusFilter = null;
		if (status != null && !status.isBlank()) {
			statusFilter = DossierStatus.valueOf(status.trim().toUpperCase());
		}
		return knowledgeBaseService.searchDossiers(finalQuery, statusFilter, stale, finalLimit, finalOffset);
	}

	@PostMapping("/dossiers")
	@Operation(summary = "Create dossier")
	public InstrumentDossierResponseDto createDossier(@Valid @RequestBody InstrumentDossierCreateRequest request,
												  Principal principal) {
		availabilityService.assertAvailable();
		String createdBy = principal == null ? "system" : principal.getName();
		return knowledgeBaseService.createDossier(request, createdBy);
	}

	@PostMapping("/dossiers/bulk-research")
	@Operation(summary = "Run bulk KB research")
	public KnowledgeBaseLlmActionDto bulkResearch(@Valid @RequestBody KnowledgeBaseBulkResearchRequestDto request,
												 Principal principal) {
		availabilityService.assertAvailable();
		String actor = principal == null ? "system" : principal.getName();
		return actionService.startBulkResearch(request.isins(), request.autoApprove(), request.applyToOverrides(), actor,
				KnowledgeBaseLlmActionTrigger.USER);
	}

	@PostMapping("/dossiers/delete")
	@Operation(summary = "Delete knowledge base dossiers by ISIN")
	public KnowledgeBaseDossierDeleteResultDto deleteDossiers(@Valid @RequestBody KnowledgeBaseDossierDeleteRequestDto request) {
		availabilityService.assertAvailable();
		return knowledgeBaseService.deleteDossiers(request.isins());
	}

	@PostMapping("/dossiers/{isin:[A-Z0-9]{12}}/refresh")
	@Operation(summary = "Refresh dossier for ISIN")
	public KnowledgeBaseLlmActionDto refreshDossier(@PathVariable("isin") String isin,
													@RequestBody(required = false) KnowledgeBaseRefreshRequestDto request,
													Principal principal) {
		availabilityService.assertAvailable();
		String actor = principal == null ? "system" : principal.getName();
		Boolean autoApprove = request == null ? null : request.autoApprove();
		return actionService.startRefreshSingle(isin, actor, autoApprove, KnowledgeBaseLlmActionTrigger.USER);
	}

	@PostMapping("/alternatives/{isin:[A-Z0-9]{12}}")
	@Operation(summary = "Find alternatives for ISIN")
	public KnowledgeBaseLlmActionDto findAlternatives(@PathVariable("isin") String isin,
													  @RequestBody(required = false) KnowledgeBaseAlternativesRequestDto request,
													  Principal principal) {
		availabilityService.assertAvailable();
		String actor = principal == null ? "system" : principal.getName();
		Boolean autoApprove = request == null ? null : request.autoApprove();
		return actionService.startAlternatives(isin, autoApprove, actor, KnowledgeBaseLlmActionTrigger.USER);
	}

	@PostMapping("/refresh/batch")
	@Operation(summary = "Run KB refresh batch")
	public KnowledgeBaseLlmActionDto refreshBatch(@RequestBody(required = false) KnowledgeBaseRefreshBatchRequestDto request,
												  Principal principal) {
		availabilityService.assertAvailable();
		String actor = principal == null ? "system" : principal.getName();
		return actionService.startRefreshBatch(request, actor, KnowledgeBaseLlmActionTrigger.USER);
	}

	@PostMapping("/refresh/{isin:[A-Z0-9]{12}}")
	@Operation(summary = "Refresh single ISIN (admin)")
	public KnowledgeBaseLlmActionDto refreshSingle(@PathVariable("isin") String isin,
												   @RequestBody(required = false) KnowledgeBaseRefreshRequestDto request,
												   Principal principal) {
		availabilityService.assertAvailable();
		String actor = principal == null ? "system" : principal.getName();
		Boolean autoApprove = request == null ? null : request.autoApprove();
		return actionService.startRefreshSingle(isin, actor, autoApprove, KnowledgeBaseLlmActionTrigger.USER);
	}

	@PostMapping("/dossiers/websearch")
	@Operation(summary = "Start LLM websearch draft")
	public InstrumentDossierWebsearchJobResponseDto startWebsearchDraft(@Valid @RequestBody InstrumentDossierWebsearchRequest request) {
		availabilityService.assertAvailable();
		return websearchJobService.start(request.isin());
	}

	@PostMapping("/dossiers/websearch/bulk")
	@Operation(summary = "Start bulk LLM websearch draft")
	public InstrumentDossierBulkWebsearchJobResponseDto startBulkWebsearchDraft(@Valid @RequestBody InstrumentDossierBulkWebsearchRequest request,
													Principal principal) {
		availabilityService.assertAvailable();
		String createdBy = principal == null ? "system" : principal.getName();
		return bulkWebsearchJobService.start(request.isins(), createdBy);
	}

	@GetMapping("/dossiers/websearch/{jobId}")
	@Operation(summary = "Get LLM websearch draft job")
	public InstrumentDossierWebsearchJobResponseDto getWebsearchDraft(@PathVariable("jobId") String jobId) {
		availabilityService.assertAvailable();
		return websearchJobService.get(jobId);
	}

	@GetMapping("/dossiers/websearch/bulk/{jobId}")
	@Operation(summary = "Get bulk LLM websearch draft job")
	public InstrumentDossierBulkWebsearchJobResponseDto getBulkWebsearchDraft(@PathVariable("jobId") String jobId) {
		availabilityService.assertAvailable();
		return bulkWebsearchJobService.get(jobId);
	}

	@GetMapping("/dossiers/{isin:[A-Z0-9]{12}}")
	@Operation(summary = "Get dossier detail by ISIN")
	public KnowledgeBaseDossierDetailDto getDossierByIsin(@PathVariable("isin") String isin) {
		availabilityService.assertAvailable();
		return knowledgeBaseService.getDossierDetail(isin);
	}

	@GetMapping("/dossiers/{id:\\d+}")
	@Operation(summary = "Get dossier by id")
	public InstrumentDossierResponseDto getDossier(@PathVariable("id") Long dossierId) {
		availabilityService.assertAvailable();
		return knowledgeBaseService.getDossier(dossierId);
	}

	@PutMapping("/dossiers/{id:\\d+}")
	@Operation(summary = "Update dossier")
	public InstrumentDossierResponseDto updateDossier(@PathVariable("id") Long dossierId,
												  @Valid @RequestBody InstrumentDossierUpdateRequest request,
												  Principal principal) {
		availabilityService.assertAvailable();
		String updatedBy = principal == null ? "system" : principal.getName();
		return knowledgeBaseService.updateDossier(dossierId, request, updatedBy);
	}

	@PostMapping("/dossiers/{id:\\d+}/approve")
	@Operation(summary = "Approve dossier")
	public InstrumentDossierResponseDto approveDossier(@PathVariable("id") Long dossierId, Principal principal) {
		availabilityService.assertAvailable();
		String approvedBy = principal == null ? "system" : principal.getName();
		return knowledgeBaseService.approveDossier(dossierId, approvedBy);
	}

	@PostMapping("/dossiers/{id:\\d+}/reject")
	@Operation(summary = "Reject dossier")
	public InstrumentDossierResponseDto rejectDossier(@PathVariable("id") Long dossierId, Principal principal) {
		availabilityService.assertAvailable();
		String rejectedBy = principal == null ? "system" : principal.getName();
		return knowledgeBaseService.rejectDossier(dossierId, rejectedBy);
	}

	@PostMapping("/dossiers/{id:\\d+}/extract")
	@Operation(summary = "Run extraction for dossier")
	public KnowledgeBaseLlmActionDto runExtraction(@PathVariable("id") Long dossierId,
								   Principal principal) {
		availabilityService.assertAvailable();
		String actor = principal == null ? "system" : principal.getName();
		return actionService.startExtraction(dossierId, actor, KnowledgeBaseLlmActionTrigger.USER);
	}

	@PostMapping("/dossiers/{isin:[A-Z0-9]{12}}/missing-data")
	@Operation(summary = "Fill missing dossier data for ISIN")
	public KnowledgeBaseLlmActionDto fillMissingData(@PathVariable("isin") String isin,
									@RequestBody(required = false) KnowledgeBaseRefreshRequestDto request,
									Principal principal) {
		availabilityService.assertAvailable();
		String actor = principal == null ? "system" : principal.getName();
		Boolean autoApprove = request == null ? null : request.autoApprove();
		return actionService.startMissingDataFill(isin, actor, autoApprove, KnowledgeBaseLlmActionTrigger.USER);
	}

	@GetMapping("/dossiers/{id:\\d+}/extractions")
	@Operation(summary = "List dossier extractions")
	public List<InstrumentDossierExtractionResponseDto> listExtractions(@PathVariable("id") Long dossierId) {
		availabilityService.assertAvailable();
		return knowledgeBaseService.listExtractions(dossierId);
	}

	@PostMapping("/extractions/{id}/approve")
	@Operation(summary = "Approve extraction")
	public InstrumentDossierExtractionResponseDto approveExtraction(@PathVariable("id") Long extractionId,
													Principal principal) {
		availabilityService.assertAvailable();
		String approvedBy = principal == null ? "system" : principal.getName();
		return knowledgeBaseService.approveExtraction(extractionId, approvedBy);
	}

	@PostMapping("/extractions/{id}/reject")
	@Operation(summary = "Reject extraction")
	public InstrumentDossierExtractionResponseDto rejectExtraction(@PathVariable("id") Long extractionId,
												  Principal principal) {
		availabilityService.assertAvailable();
		String rejectedBy = principal == null ? "system" : principal.getName();
		return knowledgeBaseService.rejectExtraction(extractionId, rejectedBy);
	}

	@PostMapping("/extractions/{id}/apply")
	@Operation(summary = "Apply extraction to overrides")
	public InstrumentDossierExtractionResponseDto applyExtraction(@PathVariable("id") Long extractionId,
												  Principal principal) {
		availabilityService.assertAvailable();
		String appliedBy = principal == null ? "system" : principal.getName();
		return knowledgeBaseService.applyExtraction(extractionId, appliedBy);
	}
}
