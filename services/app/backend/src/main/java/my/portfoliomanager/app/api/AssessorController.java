package my.portfoliomanager.app.api;

import my.portfoliomanager.app.dto.AssessorRunJobResponseDto;
import my.portfoliomanager.app.dto.AssessorRunRequestDto;
import my.portfoliomanager.app.service.AssessorJobService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assessor")
public class AssessorController {
	private final AssessorJobService assessorJobService;

	public AssessorController(AssessorJobService assessorJobService) {
		this.assessorJobService = assessorJobService;
	}

	@PostMapping("/run")
	public AssessorRunJobResponseDto run(@RequestBody(required = false) AssessorRunRequestDto request) {
		return assessorJobService.start(request);
	}

	@GetMapping("/run/{jobId}")
	public AssessorRunJobResponseDto get(@PathVariable("jobId") String jobId) {
		return assessorJobService.get(jobId);
	}
}
