package my.portfoliomanager.app.importer;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Position(
		String depotCode,
		LocalDate asOfDate,
		String source,
		String fileHash,
		String isin,
		String name,
		BigDecimal shares,
		BigDecimal valueEur,
		String currency
) {
}
