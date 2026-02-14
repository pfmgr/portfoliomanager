package my.portfoliomanager.app.dto;

import java.util.List;

public record KnowledgeBaseMissingMetricsResponseDto(
		String isin,
		KnowledgeBaseBulkResearchItemStatus status,
		Long dossierId,
		Long extractionId,
		List<String> missingFields,
		String error
) {
}
