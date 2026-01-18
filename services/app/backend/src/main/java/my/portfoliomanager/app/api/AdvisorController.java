package my.portfoliomanager.app.api;

import my.portfoliomanager.app.domain.Ruleset;
import my.portfoliomanager.app.dto.AdvisorRunDetailDto;
import my.portfoliomanager.app.dto.AdvisorRunDto;
import my.portfoliomanager.app.dto.AdvisorSummaryDto;
import my.portfoliomanager.app.dto.ReclassificationDto;
import my.portfoliomanager.app.rules.RulesetDefinition;
import my.portfoliomanager.app.service.AdvisorService;
import my.portfoliomanager.app.service.ClassificationService;
import my.portfoliomanager.app.service.RulesetService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/advisor")
public class AdvisorController {
	private final AdvisorService advisorService;
	private final ClassificationService classificationService;
	private final RulesetService rulesetService;

	public AdvisorController(AdvisorService advisorService, ClassificationService classificationService,
								RulesetService rulesetService) {
		this.advisorService = advisorService;
		this.classificationService = classificationService;
		this.rulesetService = rulesetService;
	}

	@GetMapping("/summary")
	public AdvisorSummaryDto summary(@RequestParam(required = false)
									 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
		return advisorService.summary(asOf);
	}

	@PostMapping("/runs")
	public AdvisorRunDetailDto saveRun(@RequestParam(required = false)
									   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
		return advisorService.saveRun(asOf);
	}

	@GetMapping("/runs")
	public List<AdvisorRunDto> listRuns() {
		return advisorService.listRuns();
	}

	@GetMapping("/runs/{runId}")
	public AdvisorRunDetailDto runDetail(@PathVariable long runId) {
		return advisorService.getRun(runId);
	}

	@GetMapping("/reclassifications")
	public List<ReclassificationDto> reclassifications(
			@RequestParam(defaultValue = "0.0") double minConfidence,
			@RequestParam(defaultValue = "true") boolean onlyDifferent) {
		RulesetDefinition ruleset = resolveActiveRuleset();
		List<ReclassificationDto> results = classificationService.simulate(ruleset);
		return results.stream()
				.filter(r -> r.confidence() >= minConfidence)
				.filter(r -> !onlyDifferent || different(r))
				.toList();
	}

	private RulesetDefinition resolveActiveRuleset() {
		Ruleset ruleset = rulesetService.getActiveRuleset("default")
				.orElseThrow(() -> new IllegalArgumentException("Active ruleset not found"));
		return rulesetService.parseRuleset(ruleset.getContentYaml());
	}

	private boolean different(ReclassificationDto dto) {
		boolean classificationChanged = !dto.current().equals(dto.policyAdjusted());
		String suggestedName = dto.suggestedName();
		boolean nameChanged = suggestedName != null && !suggestedName.isBlank() && !suggestedName.equals(dto.name());
		return classificationChanged || nameChanged;
	}
}
