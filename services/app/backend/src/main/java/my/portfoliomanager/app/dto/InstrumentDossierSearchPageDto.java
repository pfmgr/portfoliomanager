package my.portfoliomanager.app.dto;

import java.util.List;

public record InstrumentDossierSearchPageDto(
		List<InstrumentDossierSearchItemDto> items,
		int total,
		int limit,
		int offset
) {
}
