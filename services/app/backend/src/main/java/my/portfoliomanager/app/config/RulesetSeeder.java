package my.portfoliomanager.app.config;

import my.portfoliomanager.app.dto.RulesetValidateResponse;
import my.portfoliomanager.app.repository.RulesetRepository;
import my.portfoliomanager.app.service.RulesetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Component
public class RulesetSeeder implements ApplicationRunner {
	private static final Logger logger = LoggerFactory.getLogger(RulesetSeeder.class);

	private final RulesetRepository rulesetRepository;
	private final RulesetService rulesetService;
	private final ResourceLoader resourceLoader;

	public RulesetSeeder(RulesetRepository rulesetRepository,
						 RulesetService rulesetService,
						 ResourceLoader resourceLoader) {
		this.rulesetRepository = rulesetRepository;
		this.rulesetService = rulesetService;
		this.resourceLoader = resourceLoader;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (rulesetRepository.count() > 0) {
			return;
		}
		Resource resource = resourceLoader.getResource("classpath:default_classification_ruleset.json");
		if (!resource.exists()) {
			logger.warn("Default ruleset resource not found: classpath:default_classification_ruleset.json");
			return;
		}
		try (InputStream inputStream = resource.getInputStream()) {
			String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			RulesetValidateResponse validation = rulesetService.validateRuleset(json);
			if (!validation.valid()) {
				logger.error("Default ruleset invalid: {}", String.join("; ", validation.errors()));
				return;
			}
			rulesetService.createNewVersion("default", json, true);
			logger.info("Seeded default reclassification ruleset (name=default) from classpath:default_classification_ruleset.json");
		} catch (Exception ex) {
			logger.error("Failed to seed default ruleset: {}", ex.getMessage());
		}
	}
}

