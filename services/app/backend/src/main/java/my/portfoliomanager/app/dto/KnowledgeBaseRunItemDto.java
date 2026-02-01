package my.portfoliomanager.app.dto;

import my.portfoliomanager.app.domain.KnowledgeBaseRunAction;
import my.portfoliomanager.app.domain.KnowledgeBaseRunStatus;

import java.time.LocalDateTime;

public record KnowledgeBaseRunItemDto(
		Long runId,
		String isin,
		KnowledgeBaseRunAction action,
		KnowledgeBaseRunStatus status,
		LocalDateTime startedAt,
		LocalDateTime finishedAt,
		Integer attempts,
		String error,
		String batchId,
		String requestId,
		KnowledgeBaseManualApprovalDto manualApproval
) {
}
