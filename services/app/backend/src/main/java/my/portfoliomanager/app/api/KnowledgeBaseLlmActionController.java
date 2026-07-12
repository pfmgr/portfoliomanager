package my.portfoliomanager.app.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionCreateRequestDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionCreateResponseDto;
import my.portfoliomanager.app.service.KnowledgeBaseAvailabilityService;
import my.portfoliomanager.app.service.KnowledgeBaseLlmActionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.security.Principal;

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
		availabilityService.assertEnabled();
		return actionService.listActions();
	}

	@PostMapping
	@Operation(summary = "Create a durable Knowledge Base LLM action")
	public KnowledgeBaseLlmActionCreateResponseDto createAction(
			@RequestBody KnowledgeBaseLlmActionCreateRequestDto request,
			@RequestHeader(value = "Idempotency-Key", required = false) String headerIdempotencyKey,
			Principal principal) {
		availabilityService.assertEnabled();
		String actor = principal == null ? request.actor() : principal.getName();
		KnowledgeBaseLlmActionCreateRequestDto serverActorRequest = new KnowledgeBaseLlmActionCreateRequestDto(request.type(), request.isins(), request.dossierId(),
				request.autoApprove(), request.applyOverrides(), request.force(), request.refreshRequest(), actor, request.trigger(), request.idempotencyKey());
		KnowledgeBaseLlmActionDto action = actionService.create(serverActorRequest,
				headerIdempotencyKey == null || headerIdempotencyKey.isBlank() ? request.idempotencyKey() : headerIdempotencyKey);
		return new KnowledgeBaseLlmActionCreateResponseDto(action.actionId(), action.status());
	}

	@GetMapping("/{actionId}")
	@Operation(summary = "Get LLM action")
	public KnowledgeBaseLlmActionDto getAction(@PathVariable("actionId") String actionId) {
		availabilityService.assertEnabled();
		return actionService.getAction(actionId);
	}

	@PostMapping("/{actionId}/cancel")
	@Operation(summary = "Cancel LLM action")
	public KnowledgeBaseLlmActionDto cancelAction(@PathVariable("actionId") String actionId) {
		availabilityService.assertEnabled();
		return actionService.cancel(actionId);
	}

	@DeleteMapping("/{actionId}")
	@Operation(summary = "Dismiss LLM action")
	public void dismissAction(@PathVariable("actionId") String actionId) {
		availabilityService.assertEnabled();
		actionService.dismiss(actionId);
	}
}
