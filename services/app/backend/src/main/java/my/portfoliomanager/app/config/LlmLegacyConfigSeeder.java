package my.portfoliomanager.app.config;

import my.portfoliomanager.app.service.LlmRuntimeConfigService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class LlmLegacyConfigSeeder implements ApplicationRunner {
	private final LlmRuntimeConfigService llmRuntimeConfigService;

	public LlmLegacyConfigSeeder(LlmRuntimeConfigService llmRuntimeConfigService) {
		this.llmRuntimeConfigService = llmRuntimeConfigService;
	}

	@Override
	public void run(ApplicationArguments args) {
		llmRuntimeConfigService.migrateLegacyConfigIfNeeded();
	}
}
