package my.portfoliomanager.app.dto;

import java.util.List;

public record KnowledgeBaseAlternativesResponseDto(
		String baseIsin,
		List<KnowledgeBaseAlternativeItemDto> alternatives
) {
}
