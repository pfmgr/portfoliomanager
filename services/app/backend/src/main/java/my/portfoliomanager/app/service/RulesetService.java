package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.Ruleset;
import my.portfoliomanager.app.dto.RulesetDetailDto;
import my.portfoliomanager.app.dto.RulesetSummaryDto;
import my.portfoliomanager.app.dto.RulesetValidateResponse;
import my.portfoliomanager.app.repository.RulesetRepository;
import my.portfoliomanager.app.rules.RulesetDefinition;
import my.portfoliomanager.app.rules.RulesetParser;
import my.portfoliomanager.app.rules.RulesetValidator;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class RulesetService {
	private final RulesetRepository rulesetRepository;
	private final RulesetParser parser;
	private final RulesetValidator validator;

	public RulesetService(RulesetRepository rulesetRepository) {
		this.rulesetRepository = rulesetRepository;
		this.parser = new RulesetParser();
		this.validator = new RulesetValidator();
	}

	public List<RulesetSummaryDto> listRulesets() {
		return rulesetRepository.findAllOrderByName().stream()
				.map(r -> new RulesetSummaryDto(r.getName(), r.getVersion(), r.isActive(), r.getCreatedAt(), r.getUpdatedAt()))
				.toList();
	}

	public Optional<RulesetDetailDto> getLatestByName(String name) {
		List<Ruleset> versions = rulesetRepository.findByNameOrderByVersionDesc(name);
		if (versions.isEmpty()) {
			return Optional.empty();
		}
		Ruleset latest = versions.get(0);
		return Optional.of(toDetailDto(latest));
	}

	public Optional<Ruleset> getActiveRuleset(String name) {
		return rulesetRepository.findActiveByName(name);
	}

	public RulesetDetailDto createNewVersion(String name, String contentJson, boolean activate) {
		int nextVersion = rulesetRepository.findByNameOrderByVersionDesc(name).stream()
				.map(Ruleset::getVersion)
				.max(Comparator.naturalOrder())
				.orElse(0) + 1;

		if (activate) {
			rulesetRepository.findByNameOrderByVersionDesc(name).forEach(r -> {
				if (r.isActive()) {
					r.setActive(false);
					r.setUpdatedAt(LocalDateTime.now());
					rulesetRepository.save(r);
				}
			});
		}

		Ruleset ruleset = new Ruleset();
		ruleset.setName(name);
		ruleset.setVersion(nextVersion);
		ruleset.setContentYaml(contentJson);
		ruleset.setActive(activate);
		ruleset.setCreatedAt(LocalDateTime.now());
		ruleset.setUpdatedAt(LocalDateTime.now());
		rulesetRepository.save(ruleset);

		return toDetailDto(ruleset);
	}

	public RulesetValidateResponse validateRuleset(String contentJson) {
		try {
			RulesetDefinition definition = parser.parse(contentJson);
			List<String> errors = validator.validate(definition);
			return new RulesetValidateResponse(errors.isEmpty(), errors);
		} catch (Exception ex) {
			return new RulesetValidateResponse(false, List.of("Failed to parse ruleset JSON: " + ex.getMessage()));
		}
	}

	public RulesetDefinition parseRuleset(String contentJson) {
		try {
			return parser.parse(contentJson);
		} catch (Exception ex) {
			throw new IllegalArgumentException("Invalid ruleset JSON: " + ex.getMessage(), ex);
		}
	}

	public List<String> validateDefinition(RulesetDefinition definition) {
		return validator.validate(definition);
	}

	private RulesetDetailDto toDetailDto(Ruleset ruleset) {
		return new RulesetDetailDto(ruleset.getName(), ruleset.getVersion(), ruleset.isActive(), ruleset.getContentYaml());
	}
}
