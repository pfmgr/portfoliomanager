package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.Instrument;
import my.portfoliomanager.app.domain.InstrumentOverride;
import my.portfoliomanager.app.dto.InstrumentOverrideRequest;
import my.portfoliomanager.app.dto.OverridesImportResultDto;
import my.portfoliomanager.app.repository.InstrumentRepository;
import my.portfoliomanager.app.repository.InstrumentOverrideRepository;
import my.portfoliomanager.app.util.CsvParsing;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class OverridesService {
	private static final Pattern ISIN_RE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");
	private final InstrumentRepository instrumentRepository;
	private final InstrumentOverrideRepository overrideRepository;
	private final AuditService auditService;

	public OverridesService(InstrumentRepository instrumentRepository,
							InstrumentOverrideRepository overrideRepository,
							AuditService auditService) {
		this.instrumentRepository = instrumentRepository;
		this.overrideRepository = overrideRepository;
		this.auditService = auditService;
	}

	public String exportCsv() {
		List<InstrumentOverride> overrides = overrideRepository.findAll();
		StringBuilder builder = new StringBuilder();
		builder.append("isin,name,instrument_type,asset_class,sub_class,layer,layer_last_changed,layer_notes\n");
		for (InstrumentOverride override : overrides) {
			builder.append(csv(override.getIsin())).append(',')
					.append(csv(override.getName())).append(',')
					.append(csv(override.getInstrumentType())).append(',')
					.append(csv(override.getAssetClass())).append(',')
					.append(csv(override.getSubClass())).append(',')
					.append(csv(override.getLayer())).append(',')
					.append(csv(override.getLayerLastChanged())).append(',')
					.append(csv(override.getLayerNotes()))
					.append('\n');
		}
		return builder.toString();
	}

	@Transactional
	public OverridesImportResultDto importCsv(MultipartFile file, String editedBy) {
		byte[] payload = readFile(file);
		String text = decode(payload);
		String sample = text.substring(0, Math.min(text.length(), 2048));
		char delimiter = CsvParsing.sniffDelimiter(sample);

		Map<String, String> headerMap = new HashMap<>();
		List<CSVRecord> records;
		try (CSVParser parser = CSVParser.parse(
				new StringReader(text),
				CSVFormat.DEFAULT.withDelimiter(delimiter).withFirstRecordAsHeader()
		)) {
			for (Map.Entry<String, Integer> entry : parser.getHeaderMap().entrySet()) {
				if (entry.getKey() != null && !entry.getKey().isBlank()) {
					headerMap.put(entry.getKey().trim().toLowerCase(Locale.ROOT), entry.getKey());
				}
			}
			records = parser.getRecords();
		} catch (IOException exc) {
			throw new IllegalArgumentException("Failed to read CSV: " + exc.getMessage(), exc);
		}
		if (!headerMap.containsKey("isin")) {
			throw new IllegalArgumentException("CSV header must include 'isin'");
		}

		int skippedEmpty = 0;
		int skippedMissing = 0;
		int imported = 0;

		Set<String> knownIsins = instrumentRepository.findAll().stream()
				.map(Instrument::getIsin)
				.collect(java.util.stream.Collectors.toSet());

		for (CSVRecord record : records) {
			if (record == null || record.toMap().values().stream().allMatch(v -> v == null || v.isBlank())) {
				skippedEmpty += 1;
				continue;
			}
			String isin = normalizeIsin(get(record, headerMap, "isin"));
			if (!knownIsins.contains(isin)) {
				skippedMissing += 1;
				continue;
			}
			String name = trimOrNull(get(record, headerMap, "name"));
			String instrumentType = trimOrNull(get(record, headerMap, "instrument_type"));
			String assetClass = trimOrNull(get(record, headerMap, "asset_class"));
			String subClass = trimOrNull(get(record, headerMap, "sub_class"));
			Integer layer = parseLayer(get(record, headerMap, "layer"));
			LocalDate layerLastChanged = parseDate(get(record, headerMap, "layer_last_changed"), "layer_last_changed");
			String layerNotes = trimOrNull(get(record, headerMap, "layer_notes"));

			if (!hasAny(name, instrumentType, assetClass, subClass, layer, layerLastChanged, layerNotes)) {
				skippedEmpty += 1;
				continue;
			}

			InstrumentOverride override = overrideRepository.findById(isin).orElseGet(() -> {
				InstrumentOverride created = new InstrumentOverride();
				created.setIsin(isin);
				return created;
			});

			updateField(override.getName(), name, "name", override::setName, isin, editedBy, "override_import");
			updateField(override.getInstrumentType(), instrumentType, "instrument_type", override::setInstrumentType,
					isin, editedBy, "override_import");
			updateField(override.getAssetClass(), assetClass, "asset_class", override::setAssetClass,
					isin, editedBy, "override_import");
			updateField(override.getSubClass(), subClass, "sub_class", override::setSubClass, isin, editedBy,
					"override_import");
			if (layer != null) {
				updateField(override.getLayer() == null ? null : override.getLayer().toString(),
						layer.toString(), "layer", val -> override.setLayer(val == null ? null : Integer.valueOf(val)),
						isin, editedBy, "override_import");
				if (layerLastChanged == null) {
					layerLastChanged = LocalDate.now();
				}
			}
			if (layerLastChanged != null) {
				override.setLayerLastChanged(layerLastChanged);
			}
			updateField(override.getLayerNotes(), layerNotes, "layer_notes", override::setLayerNotes, isin, editedBy,
					"override_import");
			override.setUpdatedAt(java.time.LocalDateTime.now());
			overrideRepository.save(override);
			imported += 1;
		}

		return new OverridesImportResultDto(imported, skippedMissing, skippedEmpty);
	}

	@Transactional
	public void upsertOverride(String isin, InstrumentOverrideRequest request, String editedBy) {
		String normalizedIsin = normalizeIsin(isin);
		instrumentRepository.findById(normalizedIsin)
				.orElseThrow(() -> new IllegalArgumentException("Instrument not found"));
		InstrumentOverride override = overrideRepository.findById(normalizedIsin).orElseGet(() -> {
			InstrumentOverride created = new InstrumentOverride();
			created.setIsin(normalizedIsin);
			return created;
		});

		updateField(override.getName(), trimOrNull(request.name()), "name", override::setName, normalizedIsin, editedBy,
				"override_ui");
		updateField(override.getInstrumentType(), trimOrNull(request.instrumentType()), "instrument_type",
				override::setInstrumentType, normalizedIsin, editedBy, "override_ui");
		updateField(override.getAssetClass(), trimOrNull(request.assetClass()), "asset_class",
				override::setAssetClass, normalizedIsin, editedBy, "override_ui");
		updateField(override.getSubClass(), trimOrNull(request.subClass()), "sub_class",
				override::setSubClass, normalizedIsin, editedBy, "override_ui");

		Integer layer = request.layer();
		if (layer != null) {
			updateField(override.getLayer() == null ? null : override.getLayer().toString(),
					layer.toString(), "layer", val -> override.setLayer(val == null ? null : Integer.valueOf(val)),
					normalizedIsin, editedBy, "override_ui");
			if (request.layerLastChanged() == null) {
				override.setLayerLastChanged(LocalDate.now());
			}
		}
		if (request.layerLastChanged() != null) {
			override.setLayerLastChanged(request.layerLastChanged());
		}

		updateField(override.getLayerNotes(), trimOrNull(request.layerNotes()), "layer_notes",
				override::setLayerNotes, normalizedIsin, editedBy, "override_ui");
		override.setUpdatedAt(java.time.LocalDateTime.now());
		overrideRepository.save(override);
	}

	@Transactional
	public void deleteOverride(String isin, String editedBy) {
		String normalizedIsin = normalizeIsin(isin);
		Optional<InstrumentOverride> existing = overrideRepository.findById(normalizedIsin);
		if (existing.isEmpty()) {
			return;
		}
		InstrumentOverride override = existing.get();
		recordDelete(override.getName(), "name", normalizedIsin, editedBy);
		recordDelete(override.getInstrumentType(), "instrument_type", normalizedIsin, editedBy);
		recordDelete(override.getAssetClass(), "asset_class", normalizedIsin, editedBy);
		recordDelete(override.getSubClass(), "sub_class", normalizedIsin, editedBy);
		recordDelete(override.getLayer() == null ? null : override.getLayer().toString(), "layer", normalizedIsin, editedBy);
		recordDelete(override.getLayerNotes(), "layer_notes", normalizedIsin, editedBy);
		overrideRepository.deleteById(normalizedIsin);
	}

	private void updateField(String oldValue, String newValue, String field,
							 java.util.function.Consumer<String> updater,
							 String isin, String editedBy, String source) {
		if (oldValue == null && newValue == null) {
			return;
		}
		if (oldValue != null && oldValue.equals(newValue)) {
			return;
		}
		updater.accept(newValue);
		auditService.recordEdit(isin, field, oldValue, newValue, editedBy, source);
	}

	private String get(CSVRecord record, Map<String, String> headerMap, String key) {
		String header = headerMap.get(key);
		return header == null ? "" : record.get(header);
	}

	private String normalizeIsin(String value) {
		String trimmed = trim(value).toUpperCase(Locale.ROOT);
		if (!ISIN_RE.matcher(trimmed).matches()) {
			throw new IllegalArgumentException("Invalid ISIN: " + trimmed);
		}
		return trimmed;
	}

	private Integer parseLayer(String raw) {
		String value = trim(raw);
		if (value.isBlank()) {
			return null;
		}
		try {
			int parsed = Integer.parseInt(value);
			if (parsed < 1 || parsed > 5) {
				throw new IllegalArgumentException("layer must be between 1 and 5");
			}
			return parsed;
		} catch (NumberFormatException exc) {
			throw new IllegalArgumentException("layer must be an integer");
		}
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

	private boolean hasAny(Object... values) {
		for (Object value : values) {
			if (value == null) {
				continue;
			}
			if (value instanceof String str && str.isBlank()) {
				continue;
			}
			return true;
		}
		return false;
	}

	private String trim(String value) {
		return value == null ? "" : value.trim();
	}

	private String trimOrNull(String value) {
		String trimmed = trim(value);
		return trimmed.isBlank() ? null : trimmed;
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

	private void recordDelete(String oldValue, String field, String isin, String editedBy) {
		if (oldValue == null || oldValue.isBlank()) {
			return;
		}
		auditService.recordEdit(isin, field, oldValue, null, editedBy, "override_delete");
	}
}
