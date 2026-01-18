package my.portfoliomanager.app.api;

import jakarta.validation.Valid;
import my.portfoliomanager.app.domain.Ruleset;
import my.portfoliomanager.app.dto.ApplyRequest;
import my.portfoliomanager.app.dto.ReclassificationDto;
import my.portfoliomanager.app.dto.RulesetDetailDto;
import my.portfoliomanager.app.dto.RulesetSummaryDto;
import my.portfoliomanager.app.dto.RulesetUpsertRequest;
import my.portfoliomanager.app.dto.RulesetValidateRequest;
import my.portfoliomanager.app.dto.RulesetValidateResponse;
import my.portfoliomanager.app.dto.SimulationRequest;
import my.portfoliomanager.app.rules.RulesetDefinition;
import my.portfoliomanager.app.service.ClassificationService;
import my.portfoliomanager.app.service.RulesetService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/rulesets")
public class RulesetController {
	private final RulesetService rulesetService;
	private final ClassificationService classificationService;

	public RulesetController(RulesetService rulesetService, ClassificationService classificationService) {
		this.rulesetService = rulesetService;
		this.classificationService = classificationService;
	}

	@GetMapping
	public List<RulesetSummaryDto> listRulesets() {
		return rulesetService.listRulesets();
	}

	@GetMapping("/{name}")
	public RulesetDetailDto getRuleset(@PathVariable String name) {
		return rulesetService.getLatestByName(name)
				.orElseThrow(() -> new IllegalArgumentException("Ruleset not found"));
	}

	@PutMapping("/{name}")
	@ResponseStatus(HttpStatus.CREATED)
	public RulesetDetailDto upsertRuleset(@PathVariable String name,
													 @Valid @RequestBody RulesetUpsertRequest request) {
		RulesetValidateResponse validation = rulesetService.validateRuleset(request.contentJson());
		if (!validation.valid()) {
			throw new IllegalArgumentException("Ruleset validation failed: " + String.join("; ", validation.errors()));
		}
		return rulesetService.createNewVersion(name, request.contentJson(), request.activate());
	}

	@PostMapping("/{name}/validate")
	public RulesetValidateResponse validate(@PathVariable String name,
												 @Valid @RequestBody RulesetValidateRequest request) {
		return rulesetService.validateRuleset(request.contentJson());
	}

	@PostMapping("/{name}/simulate")
	public List<ReclassificationDto> simulate(@PathVariable String name,
												 @RequestBody(required = false) SimulationRequest request) {
		RulesetDefinition ruleset = resolveRuleset(name, request == null ? null : request.contentJson());
		return classificationService.simulate(ruleset);
	}

	@PostMapping("/{name}/apply")
	public List<ReclassificationDto> apply(@PathVariable String name,
											 @RequestBody ApplyRequest request,
											 Principal principal) {
		String editedBy = principal == null ? "system" : principal.getName();
		RulesetDefinition ruleset = resolveRuleset(name, request == null ? null : request.contentJson());
		boolean dryRun = request != null && request.dryRun();
		return classificationService.apply(ruleset, dryRun, editedBy, request == null ? null : request.isins());
	}

	private RulesetDefinition resolveRuleset(String name, String contentJson) {
		if (contentJson != null && !contentJson.isBlank()) {
			RulesetDefinition definition = rulesetService.parseRuleset(contentJson);
			List<String> errors = rulesetService.validateDefinition(definition);
			if (!errors.isEmpty()) {
				throw new IllegalArgumentException("Ruleset validation failed: " + String.join("; ", errors));
			}
			return definition;
		}
		Ruleset ruleset = rulesetService.getActiveRuleset(name)
				.orElseThrow(() -> new IllegalArgumentException("Active ruleset not found"));
		RulesetDefinition definition = rulesetService.parseRuleset(ruleset.getContentYaml());
		List<String> errors = rulesetService.validateDefinition(definition);
		if (!errors.isEmpty()) {
			throw new IllegalArgumentException("Active ruleset invalid: " + String.join("; ", errors));
		}
		return definition;
	}
}
