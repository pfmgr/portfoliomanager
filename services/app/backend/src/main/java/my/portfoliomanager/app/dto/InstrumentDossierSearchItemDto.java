package my.portfoliomanager.app.dto;

import my.portfoliomanager.app.domain.DossierStatus;

import java.time.LocalDateTime;

public record InstrumentDossierSearchItemDto(
		String isin,
		String name,
		Integer effectiveLayer,
		boolean hasDossier,
		Long latestDossierId,
		DossierStatus latestDossierStatus,
		LocalDateTime latestUpdatedAt,
		Integer latestDossierVersion,
		LocalDateTime latestApprovedAt,
		boolean hasApprovedDossier,
		boolean hasApprovedExtraction,
		boolean stale,
		DossierExtractionFreshness extractionFreshness
) {
}
