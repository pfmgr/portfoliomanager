package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.Depot;
import my.portfoliomanager.app.domain.Instrument;
import my.portfoliomanager.app.domain.SavingPlan;
import my.portfoliomanager.app.dto.SavingPlanDto;
import my.portfoliomanager.app.dto.SavingPlanImportResultDto;
import my.portfoliomanager.app.dto.SavingPlanUpsertRequest;
import my.portfoliomanager.app.repository.DepotRepository;
import my.portfoliomanager.app.repository.InstrumentRepository;
import my.portfoliomanager.app.repository.SavingPlanRepository;
import my.portfoliomanager.app.repository.projection.SavingPlanListProjection;
import my.portfoliomanager.app.util.CsvParsing;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SavingPlanService {
	private static final Pattern ISIN_RE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");
	private final SavingPlanRepository savingPlanRepository;
	private final DepotRepository depotRepository;
	private final InstrumentRepository instrumentRepository;

	public SavingPlanService(SavingPlanRepository savingPlanRepository,
						   DepotRepository depotRepository,
						   InstrumentRepository instrumentRepository) {
		this.savingPlanRepository = savingPlanRepository;
		this.depotRepository = depotRepository;
		this.instrumentRepository = instrumentRepository;
	}

	public List<SavingPlanDto> list() {
		return savingPlanRepository.findAllWithDetails().stream()
				.map(this::toDto)
				.toList();
	}

	@Transactional
	public SavingPlanDto create(SavingPlanUpsertRequest request) {
		Depot depot = depotRepository.findById(request.depotId())
				.orElseThrow(() -> new IllegalArgumentException("Depot not found"));
		String isin = normalizeIsin(request.isin());
		Instrument instrument = instrumentRepository.findById(isin)
				.orElseThrow(() -> new IllegalArgumentException("ISIN not found in instruments"));

		Optional<SavingPlan> existing = savingPlanRepository.findByDepotIdAndIsin(depot.getDepotId(), isin);
		if (existing.isPresent()) {
			throw new IllegalArgumentException("SavingPlan already exists for this depot/ISIN");
		}

		SavingPlan savingPlan = new SavingPlan();
		savingPlan.setDepotId(depot.getDepotId());
		savingPlan.setIsin(isin);
		savingPlan.setName(trimOrNull(request.name()));
		savingPlan.setAmountEur(request.amountEur());
		savingPlan.setFrequency(normalizeFrequency(request.frequency()));
		savingPlan.setDayOfMonth(request.dayOfMonth());
		savingPlan.setActive(request.active() == null || request.active());
		savingPlan.setLastChanged(request.lastChanged() == null ? LocalDate.now() : request.lastChanged());
		SavingPlan saved = savingPlanRepository.save(savingPlan);
		return toDto(saved, depot, instrument.getName());
	}

	@Transactional
	public SavingPlanDto update(Long id, SavingPlanUpsertRequest request) {
		SavingPlan savingPlan = savingPlanRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("SavingPlan not found"));
		Depot depot = depotRepository.findById(savingPlan.getDepotId())
				.orElseThrow(() -> new IllegalArgumentException("Depot not found"));

		String name = trimOrNull(request.name());
		BigDecimal amount = request.amountEur();
		String frequency = normalizeFrequency(request.frequency());
		Integer dayOfMonth = request.dayOfMonth();
		boolean active = request.active() == null || request.active();

		boolean changed = !equalsNullable(savingPlan.getName(), name)
				|| !equalsNullable(savingPlan.getAmountEur(), amount)
				|| !equalsNullable(savingPlan.getFrequency(), frequency)
				|| !equalsNullable(savingPlan.getDayOfMonth(), dayOfMonth)
				|| savingPlan.isActive() != active;

		if (changed) {
			savingPlan.setName(name);
			savingPlan.setAmountEur(amount);
			savingPlan.setFrequency(frequency);
			savingPlan.setDayOfMonth(dayOfMonth);
			savingPlan.setActive(active);
			savingPlan.setLastChanged(request.lastChanged() == null ? LocalDate.now() : request.lastChanged());
		} else if (request.lastChanged() != null) {
			savingPlan.setLastChanged(request.lastChanged());
		}

		SavingPlan saved = savingPlanRepository.save(savingPlan);
		String instrumentName = instrumentRepository.findById(saved.getIsin()).map(Instrument::getName).orElse(null);
		return toDto(saved, depot, instrumentName);
	}

	@Transactional
	public void delete(Long id) {
		if (!savingPlanRepository.existsById(id)) {
			throw new IllegalArgumentException("SavingPlan not found");
		}
		savingPlanRepository.deleteById(id);
	}

	@Transactional
	public SavingPlanImportResultDto importCsv(MultipartFile file) {
		byte[] payload = readFile(file);
		String text = decode(payload);
		String sample = text.substring(0, Math.min(text.length(), 2048));
		char delimiter = CsvParsing.sniffDelimiter(sample);

		Map<String, String> headerMap = new HashMap<>();
		Map<RowKey, SavingPlanCsvRow> parsed = new HashMap<>();
		int totalRows = 0;
		int skippedEmpty = 0;

		try (CSVParser parser = CSVParser.parse(
				new StringReader(text),
				CSVFormat.DEFAULT.withDelimiter(delimiter).withFirstRecordAsHeader()
		)) {
			for (Map.Entry<String, Integer> entry : parser.getHeaderMap().entrySet()) {
				if (entry.getKey() != null && !entry.getKey().isBlank()) {
					headerMap.put(entry.getKey().trim().toLowerCase(Locale.ROOT), entry.getKey());
				}
			}
			for (String required : List.of("depot_code", "isin", "amount_eur")) {
				if (!headerMap.containsKey(required)) {
					throw new IllegalArgumentException("CSV header must include '" + required + "'");
				}
			}

			for (CSVRecord record : parser) {
				if (record == null || record.toMap().values().stream().allMatch(v -> v == null || v.isBlank())) {
					skippedEmpty += 1;
					continue;
				}
				totalRows += 1;
				SavingPlanCsvRow row = parseRow(record, headerMap);
				parsed.put(new RowKey(row.depotCode, row.isin), row);
			}
		} catch (IOException exc) {
			throw new IllegalArgumentException("Failed to read CSV: " + exc.getMessage(), exc);
		}

		if (totalRows == 0 || parsed.isEmpty()) {
			throw new IllegalArgumentException("CSV contained no savingPlans");
		}

		Set<String> depotCodes = parsed.values().stream().map(row -> row.depotCode).collect(java.util.stream.Collectors.toSet());
		Map<String, Depot> depotMap = new HashMap<>();
		for (Depot depot : depotRepository.findAll()) {
			if (depotCodes.contains(depot.getDepotCode())) {
				depotMap.put(depot.getDepotCode(), depot);
			}
		}
		List<String> missingDepots = depotCodes.stream().filter(code -> !depotMap.containsKey(code)).sorted().toList();
		if (!missingDepots.isEmpty()) {
			throw new IllegalArgumentException("Unknown depot_code(s): " + String.join(", ", missingDepots));
		}

		List<String> isins = parsed.values().stream().map(row -> row.isin).distinct().toList();
		Set<String> existingIsins = instrumentRepository.findByIsinIn(isins).stream()
				.map(Instrument::getIsin)
				.collect(java.util.stream.Collectors.toSet());

		int created = 0;
		int updated = 0;
		int skippedMissing = 0;
		int skippedUnchanged = 0;

		List<SavingPlanCsvRow> rows = parsed.values().stream()
				.sorted(Comparator.comparing((SavingPlanCsvRow row) -> row.depotCode).thenComparing(row -> row.isin))
				.toList();

		for (SavingPlanCsvRow row : rows) {
			if (!existingIsins.contains(row.isin)) {
				skippedMissing += 1;
				continue;
			}
			Depot depot = depotMap.get(row.depotCode);
			SavingPlan savingPlan = savingPlanRepository.findByDepotIdAndIsin(depot.getDepotId(), row.isin).orElse(null);
			if (savingPlan == null) {
				SavingPlan createdEntity = new SavingPlan();
				createdEntity.setDepotId(depot.getDepotId());
				createdEntity.setIsin(row.isin);
				createdEntity.setName(row.name);
				createdEntity.setAmountEur(row.amountEur);
				createdEntity.setFrequency(row.frequency);
				createdEntity.setDayOfMonth(row.dayOfMonth);
				createdEntity.setActive(row.active);
				createdEntity.setLastChanged(row.lastChanged == null ? LocalDate.now() : row.lastChanged);
				savingPlanRepository.save(createdEntity);
				created += 1;
				continue;
			}
			String name = row.name != null ? row.name : savingPlan.getName();
			String frequency = row.frequency != null ? row.frequency : savingPlan.getFrequency();
			Integer day = row.dayOfMonth != null ? row.dayOfMonth : savingPlan.getDayOfMonth();
			Boolean active = row.activeExplicit ? row.active : savingPlan.isActive();

			boolean changed = !equalsNullable(savingPlan.getName(), name)
					|| !equalsNullable(savingPlan.getAmountEur(), row.amountEur)
					|| !equalsNullable(savingPlan.getFrequency(), frequency)
					|| !equalsNullable(savingPlan.getDayOfMonth(), day)
					|| savingPlan.isActive() != active;

			LocalDate lastChanged = row.lastChanged != null ? row.lastChanged : savingPlan.getLastChanged();
			if (row.lastChanged == null && changed) {
				lastChanged = LocalDate.now();
			}

			if (changed || !equalsNullable(savingPlan.getLastChanged(), lastChanged)) {
				savingPlan.setName(name);
				savingPlan.setAmountEur(row.amountEur);
				savingPlan.setFrequency(frequency);
				savingPlan.setDayOfMonth(day);
				savingPlan.setActive(active);
				savingPlan.setLastChanged(lastChanged);
				savingPlanRepository.save(savingPlan);
				updated += 1;
			} else {
				skippedUnchanged += 1;
			}
		}

		return new SavingPlanImportResultDto(created, updated, skippedMissing, skippedUnchanged, skippedEmpty);
	}

	public String exportCsv() {
		List<SavingPlanDto> rows = list();
		StringBuilder builder = new StringBuilder();
		builder.append("depot_code,isin,name,amount_eur,frequency,day_of_month,active,last_changed\n");
		for (SavingPlanDto row : rows) {
			builder.append(csv(row.depotCode())).append(',')
					.append(csv(row.isin())).append(',')
					.append(csv(row.name())).append(',')
					.append(csv(row.amountEur())).append(',')
					.append(csv(row.frequency())).append(',')
					.append(csv(row.dayOfMonth())).append(',')
					.append(csv(row.active())).append(',')
					.append(csv(row.lastChanged()))
					.append('\n');
		}
		return builder.toString();
	}

	private SavingPlanDto toDto(SavingPlan savingPlan, Depot depot, String instrumentName) {
		String name = savingPlan.getName();
		if (name == null || name.isBlank()) {
			name = instrumentName;
		}
		Integer layer = fetchEffectiveLayer(savingPlan.getIsin());
		return new SavingPlanDto(
				savingPlan.getSavingPlanId(),
				savingPlan.getDepotId(),
				depot.getDepotCode(),
				depot.getName(),
				savingPlan.getIsin(),
				name,
				savingPlan.getAmountEur(),
				savingPlan.getFrequency(),
				savingPlan.getDayOfMonth(),
				savingPlan.isActive(),
				savingPlan.getLastChanged(),
				layer
		);
	}

	private SavingPlanCsvRow parseRow(CSVRecord record, Map<String, String> headerMap) {
		String depotCode = get(record, headerMap, "depot_code").toLowerCase(Locale.ROOT);
		if (depotCode.isBlank()) {
			throw new IllegalArgumentException("depot_code is required");
		}
		String isin = normalizeIsin(get(record, headerMap, "isin"));
		if (!ISIN_RE.matcher(isin).matches()) {
			throw new IllegalArgumentException("Invalid ISIN: " + isin);
		}
		String name = trimOrNull(get(record, headerMap, "name"));
		BigDecimal amount = parseAmount(get(record, headerMap, "amount_eur"));
		String frequency = trimOrNull(get(record, headerMap, "frequency"));
		if (frequency != null && frequency.isBlank()) {
			frequency = "monthly";
		}
		Integer dayOfMonth = parseDayOfMonth(get(record, headerMap, "day_of_month"));
		String activeRaw = get(record, headerMap, "active");
		boolean activeExplicit = headerMap.containsKey("active");
		Boolean active = activeExplicit ? parseBoolean(activeRaw, true, "active") : null;
		LocalDate lastChanged = parseDate(get(record, headerMap, "last_changed"), "last_changed");

		return new SavingPlanCsvRow(depotCode, isin, name, amount, frequency == null ? "monthly" : frequency,
				dayOfMonth, active == null ? true : active, activeExplicit, lastChanged);
	}

	private String get(CSVRecord record, Map<String, String> headerMap, String key) {
		String header = headerMap.get(key);
		if (header == null) {
			return "";
		}
		return record.get(header);
	}

	private BigDecimal parseAmount(String raw) {
		String value = trim(raw).replace(",", ".");
		if (value.isBlank()) {
			throw new IllegalArgumentException("amount_eur is required");
		}
		try {
			return new BigDecimal(value);
		} catch (NumberFormatException exc) {
			throw new IllegalArgumentException("amount_eur must be numeric");
		}
	}

	private Integer parseDayOfMonth(String raw) {
		String value = trim(raw);
		if (value.isBlank()) {
			return null;
		}
		try {
			int day = Integer.parseInt(value);
			if (day < 1 || day > 31) {
				throw new IllegalArgumentException("day_of_month must be between 1 and 31");
			}
			return day;
		} catch (NumberFormatException exc) {
			throw new IllegalArgumentException("day_of_month must be an integer");
		}
	}

	private Boolean parseBoolean(String raw, boolean defaultValue, String fieldName) {
		String value = trim(raw).toLowerCase(Locale.ROOT);
		if (value.isBlank()) {
			return defaultValue;
		}
		return switch (value) {
			case "1", "true", "yes", "y", "on" -> true;
			case "0", "false", "no", "n", "off" -> false;
			default -> throw new IllegalArgumentException(fieldName + " must be true/false");
		};
	}

	private LocalDate parseDate(String raw, String fieldName) {
		String value = trim(raw);
		if (value.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(value);
		} catch (Exception exc) {
			throw new IllegalArgumentException(fieldName + " must be YYYY-MM-DD");
		}
	}

	private String normalizeIsin(String value) {
		String trimmed = trim(value).toUpperCase(Locale.ROOT);
		if (!ISIN_RE.matcher(trimmed).matches()) {
			throw new IllegalArgumentException("Invalid ISIN: " + trimmed);
		}
		return trimmed;
	}

	private SavingPlanDto toDto(SavingPlanListProjection row) {
		if (row == null) {
			throw new IllegalArgumentException("SavingPlan row must not be null");
		}
		return new SavingPlanDto(
				row.getSavingPlanId(),
				row.getDepotId(),
				row.getDepotCode(),
				row.getDepotName(),
				row.getIsin(),
				row.getName(),
				row.getAmountEur(),
				row.getFrequency(),
				row.getDayOfMonth(),
				Boolean.TRUE.equals(row.getActive()),
				row.getLastChanged(),
				row.getLayer()
		);
	}

	private String normalizeFrequency(String frequency) {
		String value = trimOrNull(frequency);
		return value == null || value.isBlank() ? "monthly" : value;
	}

	private String trim(String value) {
		return value == null ? "" : value.trim();
	}

	private String trimOrNull(String value) {
		String trimmed = trim(value);
		return trimmed.isBlank() ? null : trimmed;
	}

	private boolean equalsNullable(Object left, Object right) {
		return left == null ? right == null : left.equals(right);
	}

	private Integer fetchEffectiveLayer(String isin) {
		return instrumentRepository.findEffectiveLayer(isin);
	}

	private byte[] readFile(MultipartFile file) {
		try {
			byte[] payload = file.getBytes();
			if (payload.length == 0) {
				throw new IllegalArgumentException("File is empty");
			}
			return payload;
		} catch (IOException exc) {
			throw new IllegalArgumentException("Failed to read upload: " + exc.getMessage(), exc);
		}
	}

	private String decode(byte[] payload) {
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			String decoded = decoder.decode(java.nio.ByteBuffer.wrap(payload)).toString();
			return CsvParsing.stripBom(decoded);
		} catch (Exception ignored) {
			return new String(payload, StandardCharsets.ISO_8859_1);
		}
	}

	private String csv(Object value) {
		if (value == null) {
			return "";
		}
		String raw = value.toString();
		if (raw.contains(",") || raw.contains("\"") || raw.contains("\n")) {
			return "\"" + raw.replace("\"", "\"\"") + "\"";
		}
		return raw;
	}

	private record RowKey(String depotCode, String isin) {
	}

	private record SavingPlanCsvRow(String depotCode,
								  String isin,
								  String name,
								  BigDecimal amountEur,
								  String frequency,
								  Integer dayOfMonth,
								  boolean active,
								  boolean activeExplicit,
								  LocalDate lastChanged) {
	}
}
