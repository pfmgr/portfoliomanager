package my.portfoliomanager.app.dto;

import java.util.List;

public record KnowledgeBaseRunPageDto(
		List<KnowledgeBaseRunItemDto> items,
		int total,
		int limit,
		int offset
) {
}
