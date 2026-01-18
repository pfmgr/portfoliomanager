package my.portfoliomanager.app.importer;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DekaCsvParser implements DepotParser {
	private static final Pattern ISIN_RE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");
	private static final Pattern DATE_FROM_FILENAME = Pattern.compile("(\\d{8})");

	@Override
	public List<Position> parse(byte[] payload, String filename, String depotCode, String fileHash) {
		String content = decode(payload);
		LocalDate asOf = inferAsOfDate(filename);

		Map<String, Aggregation> agg = new HashMap<>();
		try (CSVParser parser = CSVParser.parse(
				new StringReader(content),
				CSVFormat.DEFAULT.withDelimiter(';').withFirstRecordAsHeader()
		)) {
			for (CSVRecord record : parser) {
				String isin = trimUpper(record.get("ISIN"));
				if (isin.isBlank() || !ISIN_RE.matcher(isin).matches()) {
					continue;
				}
				String name = trim(record.get("Wertpapier"));
				BigDecimal shares = parseDecimalDe(record.get("St_Nom"));
				BigDecimal value = parseDecimalDe(record.get("Wert"));

				Aggregation existing = agg.get(isin);
				if (existing == null) {
					agg.put(isin, new Aggregation(name, shares, value));
				} else {
					String finalName = existing.name.isBlank() ? name : existing.name;
					BigDecimal totalShares = existing.shares.add(shares);
					BigDecimal totalValue = existing.value.add(value);
					agg.put(isin, new Aggregation(finalName, totalShares, totalValue));
				}
			}
		} catch (IOException exc) {
			throw new IllegalArgumentException("Failed to read Deka CSV: " + exc.getMessage(), exc);
		}

		List<Position> positions = new ArrayList<>();
		for (Map.Entry<String, Aggregation> entry : agg.entrySet()) {
			Aggregation aggregation = entry.getValue();
			positions.add(new Position(
					depotCode,
					asOf,
					"DEKA_CSV",
					fileHash,
					entry.getKey(),
					aggregation.name.isBlank() ? entry.getKey() : aggregation.name,
					aggregation.shares.signum() == 0 ? null : aggregation.shares,
					aggregation.value.signum() == 0 ? null : aggregation.value,
					"EUR"
			));
		}
		positions.sort(Comparator.comparing(Position::isin));
		return positions;
	}

	private String decode(byte[] payload) {
		for (String charsetName : List.of("UTF-8", "ISO-8859-1")) {
			try {
				String decoded = new String(payload, Charset.forName(charsetName));
				if (!decoded.isEmpty() && decoded.charAt(0) == '\uFEFF') {
					return decoded.substring(1);
				}
				return decoded;
			} catch (Exception ignored) {
				// try next
			}
		}
		throw new IllegalArgumentException("CSV must be UTF-8 or latin-1");
	}

	private LocalDate inferAsOfDate(String filename) {
		Matcher matcher = DATE_FROM_FILENAME.matcher(filename == null ? "" : filename);
		String yyyymmdd = null;
		while (matcher.find()) {
			yyyymmdd = matcher.group(1);
		}
		if (yyyymmdd != null) {
			try {
				int year = Integer.parseInt(yyyymmdd.substring(0, 4));
				int month = Integer.parseInt(yyyymmdd.substring(4, 6));
				int day = Integer.parseInt(yyyymmdd.substring(6, 8));
				return LocalDate.of(year, month, day);
			} catch (RuntimeException ignored) {
				// fallback below
			}
		}
		return LocalDate.now();
	}

	private BigDecimal parseDecimalDe(String raw) {
		String value = trim(raw).replace("€", "").replace("EUR", "").replace(" ", "");
		if (value.isBlank()) {
			return BigDecimal.ZERO;
		}
		if (value.contains(",")) {
			value = value.replace(".", "").replace(",", ".");
		}
		try {
			return new BigDecimal(value);
		} catch (NumberFormatException exc) {
			return BigDecimal.ZERO;
		}
	}

	private String trim(String value) {
		return value == null ? "" : value.trim();
	}

	private String trimUpper(String value) {
		return trim(value).toUpperCase(Locale.ROOT);
	}

	private record Aggregation(String name, BigDecimal shares, BigDecimal value) {
		private Aggregation {
			name = name == null ? "" : name;
			shares = shares == null ? BigDecimal.ZERO : shares;
			value = value == null ? BigDecimal.ZERO : value;
		}
	}
}
