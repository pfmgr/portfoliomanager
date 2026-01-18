package my.portfoliomanager.app.dto;

import java.util.List;

public record KnowledgeBaseBulkResearchResponseDto(
		int total,
		int succeeded,
		int skipped,
		int failed,
		List<KnowledgeBaseBulkResearchItemDto> items
) {
}
