package my.portfoliomanager.app.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionDto;
import my.portfoliomanager.app.service.KnowledgeBaseAvailabilityService;
import my.portfoliomanager.app.service.KnowledgeBaseLlmActionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/kb/llm-actions")
@Tag(name = "Knowledge Base")
public class KnowledgeBaseLlmActionController {
	private final KnowledgeBaseLlmActionService actionService;
	private final KnowledgeBaseAvailabilityService availabilityService;

	public KnowledgeBaseLlmActionController(KnowledgeBaseLlmActionService actionService,
											KnowledgeBaseAvailabilityService availabilityService) {
		this.actionService = actionService;
		this.availabilityService = availabilityService;
	}

	@GetMapping
	@Operation(summary = "List LLM actions")
	public List<KnowledgeBaseLlmActionDto> listActions() {
		availabilityService.assertAvailable();
		return actionService.listActions();
	}

	@GetMapping("/{actionId}")
	@Operation(summary = "Get LLM action")
	public KnowledgeBaseLlmActionDto getAction(@PathVariable("actionId") String actionId) {
		availabilityService.assertAvailable();
		return actionService.getAction(actionId);
	}

	@PostMapping("/{actionId}/cancel")
	@Operation(summary = "Cancel LLM action")
	public KnowledgeBaseLlmActionDto cancelAction(@PathVariable("actionId") String actionId) {
		availabilityService.assertAvailable();
		return actionService.cancel(actionId);
	}

	@DeleteMapping("/{actionId}")
	@Operation(summary = "Dismiss LLM action")
	public void dismissAction(@PathVariable("actionId") String actionId) {
		availabilityService.assertAvailable();
		actionService.dismiss(actionId);
	}
}
