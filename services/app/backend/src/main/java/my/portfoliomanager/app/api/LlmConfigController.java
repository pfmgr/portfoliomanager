package my.portfoliomanager.app.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import my.portfoliomanager.app.dto.LlmRuntimeConfigDto;
import my.portfoliomanager.app.dto.LlmRuntimeConfigUpdateDto;
import my.portfoliomanager.app.service.LlmRuntimeConfigService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/llm/config")
@Tag(name = "LLM Config")
public class LlmConfigController {
	private final LlmRuntimeConfigService llmRuntimeConfigService;

	public LlmConfigController(LlmRuntimeConfigService llmRuntimeConfigService) {
		this.llmRuntimeConfigService = llmRuntimeConfigService;
	}

	@GetMapping
	@Operation(summary = "Get runtime LLM config")
	public ResponseEntity<LlmRuntimeConfigDto> getConfig() {
		return noStoreResponse(llmRuntimeConfigService.getConfig());
	}

	@PutMapping
	@Operation(summary = "Update runtime LLM config")
	public ResponseEntity<LlmRuntimeConfigDto> updateConfig(@RequestBody LlmRuntimeConfigUpdateDto request) {
		return noStoreResponse(llmRuntimeConfigService.updateConfig(request));
	}

	private ResponseEntity<LlmRuntimeConfigDto> noStoreResponse(LlmRuntimeConfigDto body) {
		return ResponseEntity.ok()
				.header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, private")
				.header(HttpHeaders.PRAGMA, "no-cache")
				.header(HttpHeaders.EXPIRES, "0")
				.header("X-Content-Type-Options", "nosniff")
				.body(body);
	}
}
