package my.portfoliomanager.app.dto;

import java.util.List;

public record KnowledgeBaseRefreshBatchResponseDto(
		int totalCandidates,
		int processed,
		int succeeded,
		int skipped,
		int failed,
		boolean dryRun,
		List<KnowledgeBaseRefreshItemDto> items
) {
}
