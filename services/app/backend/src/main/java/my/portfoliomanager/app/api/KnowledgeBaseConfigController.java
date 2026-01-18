package my.portfoliomanager.app.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import my.portfoliomanager.app.dto.KnowledgeBaseConfigDto;
import my.portfoliomanager.app.service.KnowledgeBaseAvailabilityService;
import my.portfoliomanager.app.service.KnowledgeBaseConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kb/config")
@Tag(name = "Knowledge Base Config")
public class KnowledgeBaseConfigController {
	private final KnowledgeBaseConfigService configService;
	private final KnowledgeBaseAvailabilityService availabilityService;

	public KnowledgeBaseConfigController(KnowledgeBaseConfigService configService,
										 KnowledgeBaseAvailabilityService availabilityService) {
		this.configService = configService;
		this.availabilityService = availabilityService;
	}

	@GetMapping
	@Operation(summary = "Get knowledge base config")
	public KnowledgeBaseConfigDto getConfig() {
		availabilityService.assertAvailable();
		return configService.getConfig();
	}

	@PutMapping
	@Operation(summary = "Update knowledge base config")
	public KnowledgeBaseConfigDto updateConfig(@Valid @RequestBody KnowledgeBaseConfigDto request) {
		availabilityService.assertAvailable();
		return configService.updateConfig(request);
	}
}
