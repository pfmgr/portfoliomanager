package my.portfoliomanager.app.dto;

import java.util.List;

public record KnowledgeBaseDossierDetailDto(
		String isin,
		String displayName,
		InstrumentDossierResponseDto latestDossier,
		List<KnowledgeBaseDossierVersionDto> versions,
		List<InstrumentDossierExtractionResponseDto> extractions,
		KnowledgeBaseRunItemDto lastRefreshRun
) {
}
