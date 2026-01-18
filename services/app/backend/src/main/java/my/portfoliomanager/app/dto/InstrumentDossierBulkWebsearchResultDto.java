package my.portfoliomanager.app.dto;

import java.util.List;

public record InstrumentDossierBulkWebsearchResultDto(
		int total,
		int completed,
		int created,
		int updated,
		int failed,
		List<InstrumentDossierBulkWebsearchItemDto> items
) {
}

