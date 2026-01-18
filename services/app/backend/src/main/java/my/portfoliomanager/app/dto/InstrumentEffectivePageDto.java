package my.portfoliomanager.app.dto;

import java.util.List;

public record InstrumentEffectivePageDto(
		List<InstrumentEffectiveDto> items,
		int total,
		int limit,
		int offset
) {
}
