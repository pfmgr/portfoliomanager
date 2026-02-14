package my.portfoliomanager.app.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import my.portfoliomanager.app.domain.KnowledgeBaseRunStatus;
import my.portfoliomanager.app.dto.KnowledgeBaseRunItemDto;
import my.portfoliomanager.app.dto.KnowledgeBaseRunPageDto;
import my.portfoliomanager.app.service.KnowledgeBaseAvailabilityService;
import my.portfoliomanager.app.service.KnowledgeBaseService;
import my.portfoliomanager.app.service.KnowledgeBaseRunService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/kb/runs")
@Tag(name = "Knowledge Base Runs")
public class KnowledgeBaseRunController {
	private final KnowledgeBaseRunService runService;
	private final KnowledgeBaseAvailabilityService availabilityService;
	private final KnowledgeBaseService knowledgeBaseService;

	public KnowledgeBaseRunController(KnowledgeBaseRunService runService,
									  KnowledgeBaseAvailabilityService availabilityService,
									  KnowledgeBaseService knowledgeBaseService) {
		this.runService = runService;
		this.availabilityService = availabilityService;
		this.knowledgeBaseService = knowledgeBaseService;
	}

	@GetMapping
	@Operation(summary = "List knowledge base runs")
	public KnowledgeBaseRunPageDto listRuns(@RequestParam(required = false) String isin,
											@RequestParam(required = false) String status,
											@RequestParam(defaultValue = "0") int page,
											@RequestParam(defaultValue = "50") int size) {
		availabilityService.assertEnabled();
		KnowledgeBaseRunStatus statusFilter = null;
		if (status != null && !status.isBlank()) {
			statusFilter = KnowledgeBaseRunStatus.valueOf(status.trim().toUpperCase());
		}
		PageRequest pageable = PageRequest.of(Math.max(0, page), Math.max(1, size));
		Page<my.portfoliomanager.app.domain.KnowledgeBaseRun> runs = runService.search(isin, statusFilter, pageable);
		List<KnowledgeBaseRunItemDto> items = runs.stream().map(run -> new KnowledgeBaseRunItemDto(
				run.getRunId(),
				run.getIsin(),
				run.getAction(),
				run.getStatus(),
				run.getStartedAt(),
				run.getFinishedAt(),
				run.getAttempts(),
				run.getError(),
				run.getBatchId(),
				run.getRequestId(),
				knowledgeBaseService.resolveManualApprovalForIsin(run.getIsin())
		)).toList();
		int offset = page * size;
		return new KnowledgeBaseRunPageDto(items, Math.toIntExact(runs.getTotalElements()), size, offset);
	}
}
