package my.portfoliomanager.app.importer;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrPdfParserTest {
	private final TrPdfParser parser = new TrPdfParser();

	@Test
	void parseLinesAggregatesDuplicatePositionsAndValues() {
		List<String> lines = List.of(
				"1.000 Stk. Green Fund  1,23  1.234,56",
				"ISIN: DE0001234567",
				"Additional notes",
				"2.000 Stk. Green Fund  2,00  2.000,00",
				"ISIN: DE0001234567",
				"1.500 Stk. Blue Fund  3,00  3.000,00",
				"ISIN: DE0007654321"
		);

		List<Position> positions = parser.parseLines(lines, "tr", "hash", LocalDate.of(2025, 10, 1));

		assertThat(positions).hasSize(2);
		Position aggregated = positions.stream()
				.filter(pos -> "DE0001234567".equals(pos.isin()))
				.findFirst()
				.orElseThrow();
		assertThat(aggregated.shares()).isEqualByComparingTo(new BigDecimal("3000.00"));
		assertThat(aggregated.valueEur()).isEqualByComparingTo(new BigDecimal("3234.56"));
		assertThat(aggregated.name()).contains("Green Fund");

		Position other = positions.stream()
				.filter(pos -> "DE0007654321".equals(pos.isin()))
				.findFirst()
				.orElseThrow();
		assertThat(other.shares()).isEqualByComparingTo(new BigDecimal("1500.00"));
		assertThat(other.name()).contains("Blue Fund");
	}

	@Test
	void parseLinesSkipsEntriesWithoutIsin() {
		List<String> lines = List.of(
				"1.000 Stk. Missing  1,00  100,00"
		);

		List<Position> positions = parser.parseLines(lines, "tr", "hash", LocalDate.now());

		assertThat(positions).isEmpty();
	}

	@Test
	void inferAsOfDateUsesHeaderWhenPresent() {
		List<String> header = List.of("DATUM 15.02.2025");

		LocalDate date = ReflectionTestUtils.invokeMethod(parser, "inferAsOfDate", header, "file");

		assertThat(date).isEqualTo(LocalDate.of(2025, 2, 15));
	}

	@Test
	void inferAsOfDateFallsBackToFilename() {
		List<String> lines = List.of("no dates here");

		LocalDate date = ReflectionTestUtils.invokeMethod(parser, "inferAsOfDate", lines, "report_20230101.pdf");

		assertThat(date).isEqualTo(LocalDate.of(2023, 1, 1));
	}

	@Test
	void inferAsOfDatePickLatestCandidateFromLines() {
		List<String> lines = List.of("Some date 01.03.2024", "Other date 05.05.2025");

		LocalDate date = ReflectionTestUtils.invokeMethod(parser, "inferAsOfDate", lines, null);

		assertThat(date).isEqualTo(LocalDate.of(2025, 5, 5));
	}

	@Test
	void extractValueFallbackReturnsLastNumericValue() {
		List<String> block = List.of("Header 01.01.2025", "1.000,00", "2.500,00");

		@SuppressWarnings("unchecked")
		var result = (java.util.Optional<BigDecimal>) ReflectionTestUtils.invokeMethod(parser, "extractValueFallback", block);

		assertThat(result).contains(new BigDecimal("2500.00"));
	}

	@Test
	void extractValueFallbackSkipsInvalidEntries() {
		List<String> block = List.of("Datum 01.02.2025", "not a number", "3.500,00");

		@SuppressWarnings("unchecked")
		var result = (java.util.Optional<BigDecimal>) ReflectionTestUtils.invokeMethod(parser, "extractValueFallback", block);

		assertThat(result).contains(new BigDecimal("3500.00"));
	}

	@Test
	void extractIsinMatchesInlineText() {
		List<String> block = List.of("Some description DE0001112223 something");

		@SuppressWarnings("unchecked")
		var result = (java.util.Optional<String>) ReflectionTestUtils.invokeMethod(parser, "extractIsin", block);

		assertThat(result).contains("DE0001112223");
	}

	@Test
	void extractIsinReturnsEmptyWhenMissing() {
		List<String> block = List.of("No isin here", "Still nothing");

		@SuppressWarnings("unchecked")
		var result = (java.util.Optional<String>) ReflectionTestUtils.invokeMethod(parser, "extractIsin", block);

		assertThat(result).isEmpty();
	}
}
