package my.portfoliomanager.app.importer;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class DekaCsvParserTest {
	@Test
	void parsesAndAggregatesRows() {
		String csv = "Wertpapier;St_Nom;Wert;ISIN\n"
				+ "Alpha Fonds;1.000,00;2.000,00;DE0000000001\n"
				+ ";3.000,00;4.000,00;DE0000000001\n";
		DekaCsvParser parser = new DekaCsvParser();

		List<Position> positions = parser.parse(csv.getBytes(), "Depot_123_Depotbestand_20251229.CSV", "deka", "hash");

		assertThat(positions).hasSize(1);
		Position position = positions.get(0);
		assertThat(position.asOfDate()).isEqualTo(LocalDate.of(2025, 12, 29));
		assertThat(position.shares()).isEqualByComparingTo(new BigDecimal("4000.00"));
		assertThat(position.valueEur()).isEqualByComparingTo(new BigDecimal("6000.00"));
		assertThat(position.name()).isEqualTo("Alpha Fonds");
	}

	@Test
	void skipsInvalidIsinRows() {
		String csv = "Wertpapier;St_Nom;Wert;ISIN\n"
				+ "Alpha Fonds;1.000,00;2.000,00;INVALID\n";
		DekaCsvParser parser = new DekaCsvParser();

		List<Position> positions = parser.parse(csv.getBytes(), "Depot_20251229.CSV", "deka", "hash");

		assertThat(positions).isEmpty();
	}

	@Test
	void fallsBackToTodayWhenFilenameInvalidAndNumbersMissing() {
		String csv = "Wertpapier;St_Nom;Wert;ISIN\n"
				+ "Beta Fonds;;EUR;DE0000000002\n";
		DekaCsvParser parser = new DekaCsvParser();

		List<Position> positions = parser.parse(csv.getBytes(), "Depot_invalid.CSV", "deka", "hash");

		assertThat(positions).hasSize(1);
		Position position = positions.get(0);
		assertThat(position.shares()).isNull();
		assertThat(position.valueEur()).isNull();
		assertThat(position.name()).isEqualTo("Beta Fonds");
	}

	@Test
	void parsesSampleResourceFile() throws IOException {
		byte[] payload = readResource("/imports/Depot_12345678_Depotbestand_20260101.CSV");
		DekaCsvParser parser = new DekaCsvParser();

		List<Position> positions = parser.parse(payload, "Depot_12345678_Depotbestand_20260101.CSV", "deka", "hash");

		assertThat(positions).hasSize(20);
		Position aggregated = positions.stream()
				.filter(pos -> "DE0005152623".equals(pos.isin()))
				.findFirst()
				.orElseThrow();
		assertThat(aggregated.asOfDate()).isEqualTo(LocalDate.of(2026, 1, 1));
		assertThat(aggregated.shares()).isEqualByComparingTo(new BigDecimal("88.428"));
		assertThat(aggregated.valueEur()).isEqualByComparingTo(new BigDecimal("9807.55"));
	}

	@Test
	void parsesBomPrefixedCsv() {
		String csv = "\uFEFFWertpapier;St_Nom;Wert;ISIN\n"
				+ "Gamma Fonds;1.000,00;2.000,00;DE0000000003\n";
		DekaCsvParser parser = new DekaCsvParser();

		List<Position> positions = parser.parse(csv.getBytes(), "Depot_99999999_Depotbestand_20250101.CSV", "deka", "hash");

		assertThat(positions).hasSize(1);
		assertThat(positions.get(0).isin()).isEqualTo("DE0000000003");
	}

	@Test
	void parseDecimalDeHandlesFormattedValues() {
		DekaCsvParser parser = new DekaCsvParser();

		BigDecimal formatted = ReflectionTestUtils.invokeMethod(parser, "parseDecimalDe", "1.234,56 €");
		BigDecimal invalid = ReflectionTestUtils.invokeMethod(parser, "parseDecimalDe", "abc");

		assertThat(formatted).isEqualByComparingTo(new BigDecimal("1234.56"));
		assertThat(invalid).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void inferAsOfDateExtractsFromFilename() {
		DekaCsvParser parser = new DekaCsvParser();
		LocalDate inferred = ReflectionTestUtils.invokeMethod(parser, "inferAsOfDate", "Depot_20221231_extra.csv");

		assertThat(inferred).isEqualTo(LocalDate.of(2022, 12, 31));
	}

	private static byte[] readResource(String path) throws IOException {
		try (InputStream in = Objects.requireNonNull(DekaCsvParserTest.class.getResourceAsStream(path))) {
			return in.readAllBytes();
		}
	}
}
