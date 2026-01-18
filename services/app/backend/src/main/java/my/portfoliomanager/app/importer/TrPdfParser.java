package my.portfoliomanager.app.importer;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TrPdfParser implements DepotParser {
	private static final Pattern POSITION_START_RE = Pattern.compile("^\\s*(?<shares>[\\d\\.,]+)\\s+Stk\\.?\\s+(?<rest>.+?)\\s*$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern ISIN_RE = Pattern.compile("\\b([A-Z]{2}[A-Z0-9]{9}[0-9])\\b");
	private static final Pattern ISIN_LINE_RE = Pattern.compile("^\\s*ISIN:\\s*(?<isin>[A-Z]{2}[A-Z0-9]{9}[0-9])\\s*$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern DATE_DDMMYYYY_RE = Pattern.compile("\\b(?<d>\\d{2}\\.\\d{2}\\.\\d{4})\\b");
	private static final Pattern HEADER_ASOF_1_RE = Pattern.compile("^\\s*DATUM\\s+(?<d>\\d{2}\\.\\d{2}\\.\\d{4})\\s*$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern HEADER_ASOF_2_RE = Pattern.compile("^\\s*zum\\s+(?<d>\\d{2}\\.\\d{2}\\.\\d{4})\\s*$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern PURE_NUMBER_RE = Pattern.compile("^\\s*[\\d\\.,]+\\s*$");
	private static final Pattern TRAILING_TWO_NUMS_RE = Pattern.compile(
			"^(?<name>.*\\S)\\s+(?<n1>[\\d\\.,]+)\\s+(?<n2>[\\d\\.,]+)\\s*(?:EUR|€)?\\s*$",
			Pattern.CASE_INSENSITIVE
	);
	private static final Pattern TRAILING_ONE_NUM_RE = Pattern.compile(
			"^(?<name>.*\\S)\\s+(?<n1>[\\d\\.,]+)\\s*(?:EUR|€)?\\s*$",
			Pattern.CASE_INSENSITIVE
	);

	@Override
	public List<Position> parse(byte[] payload, String filename, String depotCode, String fileHash) {
		List<String> lines = extractLines(payload);
		LocalDate asOf = inferAsOfDate(lines, filename);
		return parseLines(lines, depotCode, fileHash, asOf);
	}

	List<Position> parseLines(List<String> lines, String depotCode, String fileHash, LocalDate asOf) {
		List<Position> positions = new ArrayList<>();
		String currentName = null;
		BigDecimal currentShares = null;
		BigDecimal currentValueEur = null;
		List<String> currentBlock = new ArrayList<>();

		for (String line : lines) {
			String trimmed = line == null ? "" : line.trim();
			Matcher start = POSITION_START_RE.matcher(trimmed);
			if (start.matches()) {
				flush(currentName, currentShares, currentValueEur, currentBlock, positions, depotCode, fileHash, asOf);
				currentShares = parseDecimalDe(start.group("shares"));
				String rest = start.group("rest");
				ParsedRest parsed = splitRestIntoNameAndValue(rest);
				currentName = parsed.name();
				currentValueEur = parsed.valueEur();
				currentBlock = new ArrayList<>();
				currentBlock.add(line);
			} else if (currentName != null) {
				currentBlock.add(line);
			}
		}

		flush(currentName, currentShares, currentValueEur, currentBlock, positions, depotCode, fileHash, asOf);
		return aggregatePositions(positions);
	}

	private List<Position> aggregatePositions(List<Position> positions) {
		Map<String, Position> agg = new HashMap<>();
		for (Position pos : positions) {
			Position existing = agg.get(pos.isin());
			if (existing == null) {
				agg.put(pos.isin(), pos);
				continue;
			}
			String name = chooseName(existing.name(), pos.name(), pos.isin());
			BigDecimal shares = (existing.shares() == null ? BigDecimal.ZERO : existing.shares())
					.add(pos.shares() == null ? BigDecimal.ZERO : pos.shares());
			BigDecimal value = null;
			if (existing.valueEur() != null || pos.valueEur() != null) {
				value = (existing.valueEur() == null ? BigDecimal.ZERO : existing.valueEur())
						.add(pos.valueEur() == null ? BigDecimal.ZERO : pos.valueEur());
			}
			agg.put(pos.isin(), new Position(pos.depotCode(), pos.asOfDate(), pos.source(), pos.fileHash(),
					pos.isin(), name, shares, value, pos.currency()));
		}
		return agg.values().stream().sorted(Comparator.comparing(Position::isin)).toList();
	}

	private void flush(String name, BigDecimal shares, BigDecimal valueEur, List<String> block,
					   List<Position> out, String depotCode, String fileHash, LocalDate asOf) {
		if (name == null || shares == null) {
			return;
		}
		String isin = extractIsin(block).orElse(null);
		if (isin == null) {
			return;
		}
		BigDecimal finalValue = valueEur != null ? valueEur : extractValueFallback(block).orElse(null);
		out.add(new Position(
				depotCode,
				asOf,
				"TR_PDF",
				fileHash,
				isin,
				sanitizeName(name, isin),
				shares,
				finalValue,
				"EUR"
		));
	}

	private List<String> extractLines(byte[] payload) {
		try (PDDocument doc = PDDocument.load(payload)) {
			PDFTextStripper stripper = new PDFTextStripper();
			String text = stripper.getText(doc);
			List<String> lines = new ArrayList<>();
			for (String line : text.split("\\R")) {
				lines.add(line == null ? "" : line);
			}
			return lines;
		} catch (IOException exc) {
			throw new IllegalArgumentException("Failed to read TR PDF: " + exc.getMessage(), exc);
		}
	}

	private LocalDate inferAsOfDate(List<String> lines, String filename) {
		Optional<LocalDate> header = extractHeaderDate(lines, HEADER_ASOF_1_RE)
				.or(() -> extractHeaderDate(lines, HEADER_ASOF_2_RE));
		if (header.isPresent()) {
			return header.get();
		}
		List<LocalDate> candidates = new ArrayList<>();
		for (String line : lines) {
			Matcher matcher = DATE_DDMMYYYY_RE.matcher(line == null ? "" : line);
			while (matcher.find()) {
				parseDate(matcher.group("d")).ifPresent(candidates::add);
			}
		}
		if (!candidates.isEmpty()) {
			return candidates.stream().max(Comparator.naturalOrder()).orElse(LocalDate.now());
		}
		Matcher fileMatch = Pattern.compile("(\\d{8})").matcher(filename == null ? "" : filename);
		if (fileMatch.find()) {
			String value = fileMatch.group(1);
			try {
				int year = Integer.parseInt(value.substring(0, 4));
				int month = Integer.parseInt(value.substring(4, 6));
				int day = Integer.parseInt(value.substring(6, 8));
				return LocalDate.of(year, month, day);
			} catch (RuntimeException ignored) {
				// fallback below
			}
		}
		return LocalDate.now();
	}

	private Optional<LocalDate> extractHeaderDate(List<String> lines, Pattern pattern) {
		for (String line : lines) {
			Matcher matcher = pattern.matcher(line == null ? "" : line);
			if (matcher.matches()) {
				return parseDate(matcher.group("d"));
			}
		}
		return Optional.empty();
	}

	private Optional<LocalDate> parseDate(String value) {
		try {
			return Optional.of(LocalDate.parse(value, DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)));
		} catch (DateTimeParseException exc) {
			return Optional.empty();
		}
	}

	private ParsedRest splitRestIntoNameAndValue(String rest) {
		String trimmed = rest == null ? "" : rest.trim();
		List<String> columns = new ArrayList<>();
		for (String col : trimmed.split("\\s{2,}")) {
			if (!col.isBlank()) {
				columns.add(col.trim());
			}
		}
		if (columns.size() >= 3) {
			BigDecimal n1 = parseDecimalDe(stripCurrency(columns.get(columns.size() - 2)));
			BigDecimal n2 = parseDecimalDe(stripCurrency(columns.get(columns.size() - 1)));
			if (n1 != null && n2 != null) {
				String name = String.join(" ", columns.subList(0, columns.size() - 2));
				return new ParsedRest(sanitizeName(name, name), n2);
			}
		}
		if (columns.size() >= 2) {
			BigDecimal n1 = parseDecimalDe(stripCurrency(columns.get(columns.size() - 1)));
			if (n1 != null) {
				String name = String.join(" ", columns.subList(0, columns.size() - 1));
				return new ParsedRest(sanitizeName(name, name), n1);
			}
		}
		Matcher m2 = TRAILING_TWO_NUMS_RE.matcher(trimmed);
		if (m2.matches()) {
			String name = m2.group("name");
			BigDecimal n2 = parseDecimalDe(stripCurrency(m2.group("n2")));
			return new ParsedRest(sanitizeName(name, name), n2);
		}
		Matcher m1 = TRAILING_ONE_NUM_RE.matcher(trimmed);
		if (m1.matches()) {
			String name = m1.group("name");
			BigDecimal n1 = parseDecimalDe(stripCurrency(m1.group("n1")));
			return new ParsedRest(sanitizeName(name, name), n1);
		}
		return new ParsedRest(sanitizeName(trimmed, trimmed), null);
	}

	private Optional<String> extractIsin(List<String> blockLines) {
		for (String line : blockLines) {
			String trimmed = line == null ? "" : line.trim();
			Matcher exact = ISIN_LINE_RE.matcher(trimmed);
			if (exact.matches()) {
				return Optional.of(exact.group("isin").trim());
			}
			Matcher inline = ISIN_RE.matcher(trimmed);
			if (inline.find()) {
				return Optional.of(inline.group(1).trim());
			}
		}
		return Optional.empty();
	}

	private Optional<BigDecimal> extractValueFallback(List<String> blockLines) {
		List<BigDecimal> numbers = new ArrayList<>();
		for (String line : blockLines) {
			String trimmed = line == null ? "" : line.trim();
			if (DATE_DDMMYYYY_RE.matcher(trimmed).find()) {
				continue;
			}
			String stripped = stripCurrency(trimmed);
			if (!PURE_NUMBER_RE.matcher(stripped).matches()) {
				continue;
			}
			if (stripped.chars().filter(ch -> ch == ',').count() > 1) {
				continue;
			}
			BigDecimal value = parseDecimalDe(stripped);
			if (value != null) {
				numbers.add(value);
			}
		}
		return numbers.isEmpty() ? Optional.empty() : Optional.of(numbers.get(numbers.size() - 1));
	}

	private BigDecimal parseDecimalDe(String raw) {
		if (raw == null) {
			return null;
		}
		String value = raw.trim()
				.replace(" ", "")
				.replace("\u00a0", "")
				.replace("\u202f", "");
		if (value.isBlank()) {
			return null;
		}
		value = value.replace(".", "").replace(",", ".");
		try {
			return new BigDecimal(value);
		} catch (NumberFormatException exc) {
			return null;
		}
	}

	private String stripCurrency(String value) {
		if (value == null) {
			return "";
		}
		return value.replaceAll("\\s*(EUR|€)\\s*$", "").trim();
	}

	private String sanitizeName(String name, String fallback) {
		String clean = name == null ? "" : name.trim();
		clean = clean.replaceAll("\\s+\\d{2}\\.\\d{2}\\.\\d{4}\\s*$", "").trim();
		clean = clean.replaceAll("\\s+", " ").trim();
		if (clean.isBlank()) {
			return fallback == null ? "" : fallback;
		}
		return clean;
	}

	private String chooseName(String existing, String candidate, String isin) {
		if (existing != null && !existing.isBlank() && !existing.equalsIgnoreCase(isin)) {
			return existing;
		}
		if (candidate != null && !candidate.isBlank()) {
			return candidate;
		}
		return existing == null ? "" : existing;
	}

	private record ParsedRest(String name, BigDecimal valueEur) {
	}
}
