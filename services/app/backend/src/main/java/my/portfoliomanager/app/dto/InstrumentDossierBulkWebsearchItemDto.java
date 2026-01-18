package my.portfoliomanager.app.dto;

public record InstrumentDossierBulkWebsearchItemDto(
		String isin,
		InstrumentDossierBulkWebsearchItemStatus status,
		Long dossierId,
		String error
) {
}

