package my.portfoliomanager.app.dto;

import java.util.List;

/** Canonical durable-action submission shape.  The legacy domain endpoints remain adapters. */
public record KnowledgeBaseLlmActionCreateRequestDto(
		KnowledgeBaseLlmActionType type,
		List<String> isins,
		Long dossierId,
		Boolean autoApprove,
		Boolean applyOverrides,
		Boolean force,
		KnowledgeBaseRefreshBatchRequestDto refreshRequest,
		String actor,
		KnowledgeBaseLlmActionTrigger trigger,
		String idempotencyKey
) { }
