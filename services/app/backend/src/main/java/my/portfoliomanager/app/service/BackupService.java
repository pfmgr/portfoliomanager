package my.portfoliomanager.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import javax.sql.DataSource;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import my.portfoliomanager.app.dto.BackupImportResultDto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class BackupService {
	private static final int FORMAT_VERSION = 1;
	private static final String METADATA_ENTRY = "metadata.json";
	private static final String DATA_PREFIX = "data/";
	private static final Set<String> EXCLUDED_TABLES = Set.of("databasechangelog", "databasechangeloglock");
	private static final List<String> KNOWN_IMPORT_ORDER = List.of(
			"depots",
			"instruments",
			"instrument_dossiers",
			"instrument_dossier_extractions",
			"knowledge_base_extractions",
			"instrument_facts",
			"kb_config",
			"kb_runs",
			"kb_alternatives",
			"sparplans",
			"sparplans_history",
			"rulesets",
			"instrument_edits",
			"instrument_classifications",
			"instrument_overrides",
			"import_files",
			"snapshots",
			"snapshot_positions",
			"advisor_runs",
			"layer_target_config"
	);
	private static final List<SequenceInfo> SEQUENCE_COLUMNS = List.of(
			new SequenceInfo("depots", "depot_id"),
			new SequenceInfo("sparplans", "sparplan_id"),
			new SequenceInfo("sparplans_history", "hist_id"),
			new SequenceInfo("rulesets", "id"),
			new SequenceInfo("instrument_edits", "id"),
			new SequenceInfo("instrument_dossiers", "dossier_id"),
			new SequenceInfo("instrument_dossier_extractions", "extraction_id"),
			new SequenceInfo("kb_runs", "run_id"),
			new SequenceInfo("kb_alternatives", "alternative_id"),
			new SequenceInfo("import_files", "file_id"),
			new SequenceInfo("snapshots", "snapshot_id"),
			new SequenceInfo("advisor_runs", "run_id")
	);

	private final JdbcTemplate jdbcTemplate;
	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
	private final DataSource dataSource;
	private final ObjectMapper objectMapper;
	private final String databaseProductName;
	private final Map<String, Map<String, ColumnInfo>> columnInfoCache = new ConcurrentHashMap<>();

	public BackupService(JdbcTemplate jdbcTemplate,
						 NamedParameterJdbcTemplate namedParameterJdbcTemplate,
						 DataSource dataSource) {
		this.jdbcTemplate = jdbcTemplate;
		this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
		this.dataSource = dataSource;
		this.objectMapper = new ObjectMapper()
				.registerModule(new JavaTimeModule())
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		this.databaseProductName = resolveDatabaseProductName();
	}

	public byte[] exportBackup() {
		List<String> tables = fetchTableNames();
		List<TableExport> exports = tables.stream()
				.map(this::exportTable)
				.collect(Collectors.toList());
		List<String> importOrder = buildImportOrder(tables);
		List<TableMetadata> metadataTables = exports.stream()
				.map(export -> new TableMetadata(export.tableName(), export.rowCount(), export.sha256()))
				.toList();
		BackupMetadata metadata = new BackupMetadata(FORMAT_VERSION, Instant.now().toString(), metadataTables, importOrder);
		return writeZip(metadata, exports);
	}

	@Transactional
	public BackupImportResultDto importBackup(MultipartFile file) {
		try {
			Map<String, byte[]> entries = readZipEntries(file);
			byte[] metadataBytes = entries.remove(METADATA_ENTRY);
			if (metadataBytes == null) {
				throw new IllegalArgumentException("Backup is missing metadata.");
			}
			BackupMetadata metadata = objectMapper.readValue(metadataBytes, BackupMetadata.class);
			if (metadata.formatVersion() > FORMAT_VERSION) {
				throw new IllegalArgumentException("Unsupported backup format version: " + metadata.formatVersion());
			}

			Map<String, byte[]> tableData = prepareTableData(metadata, entries);
			List<String> tables = metadata.tables().stream()
					.map(TableMetadata::name)
					.toList();
			truncateTables(tables);
			List<String> importOrder = determineImportOrder(metadata);
			List<DepotActiveSnapshot> depotActiveSnapshots = new ArrayList<>();
			long rowsImported = insertTables(importOrder, metadata.tables(), tableData, depotActiveSnapshots);
			applyDepotActiveSnapshotUpdates(depotActiveSnapshots);
			resetSequences(tables);
			return new BackupImportResultDto(tables.size(), rowsImported, metadata.formatVersion(), metadata.exportedAt());
		} catch (IOException e) {
			throw new IllegalArgumentException("Unable to read backup archive.", e);
		}
	}

	private List<String> fetchTableNames() {
		String sql = """
				SELECT table_name
				FROM information_schema.tables
				WHERE table_schema IN ('public','PUBLIC')
				  AND table_type IN ('BASE TABLE','TABLE')
				""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("table_name"))
				.stream()
				.filter(name -> !EXCLUDED_TABLES.contains(name.toLowerCase(Locale.ROOT)))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.toList();
	}

	private TableExport exportTable(String tableName) {
		String selectSql = "SELECT * FROM " + quoteIdentifier(tableName);
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql);
		byte[] bytes = writeJson(rows);
		String sha = sha256(bytes);
		return new TableExport(tableName, rows.size(), bytes, sha);
	}

	private byte[] writeJson(List<Map<String, Object>> rows) {
		try {
			return objectMapper.writeValueAsBytes(rows);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Unable to serialize table data.", e);
		}
	}

	private List<String> buildImportOrder(List<String> tables) {
		List<String> ordered = new ArrayList<>();
		Map<String, String> canonical = tables.stream()
				.collect(Collectors.toMap(name -> name.toLowerCase(Locale.ROOT), name -> name, (a, b) -> a));
		for (String known : KNOWN_IMPORT_ORDER) {
			String actual = canonical.get(known);
			if (actual != null && ordered.stream().noneMatch(existing -> existing.equalsIgnoreCase(actual))) {
				ordered.add(actual);
			}
		}
		tables.stream()
				.filter(table -> ordered.stream().noneMatch(existing -> existing.equalsIgnoreCase(table)))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.forEach(ordered::add);
		return ordered;
	}

	private byte[] writeZip(BackupMetadata metadata, List<TableExport> exports) {
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
			 ZipOutputStream zip = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
			for (TableExport export : exports) {
				ZipEntry entry = new ZipEntry(DATA_PREFIX + export.tableName() + ".json");
				zip.putNextEntry(entry);
				zip.write(export.bytes());
				zip.closeEntry();
			}
			byte[] metadataBytes = objectMapper.writeValueAsBytes(metadata);
			ZipEntry metaEntry = new ZipEntry(METADATA_ENTRY);
			zip.putNextEntry(metaEntry);
			zip.write(metadataBytes);
			zip.closeEntry();
			zip.finish();
			return baos.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("Unable to write backup archive.", e);
		}
	}

	private Map<String, byte[]> prepareTableData(BackupMetadata metadata, Map<String, byte[]> entries) {
		Map<String, byte[]> tableData = new HashMap<>();
		for (TableMetadata table : metadata.tables()) {
			String entryName = DATA_PREFIX + table.name() + ".json";
			byte[] data = entries.remove(entryName);
			if (data == null) {
				throw new IllegalArgumentException("Missing data for table: " + table.name());
			}
			String computed = sha256(data);
			if (!computed.equals(table.sha256())) {
				throw new IllegalArgumentException("Backup corrupted for table: " + table.name());
			}
			tableData.put(table.name(), data);
		}
		return tableData;
	}

	private List<String> determineImportOrder(BackupMetadata metadata) {
		List<String> importOrder = metadata.importOrder();
		if (importOrder != null && !importOrder.isEmpty()) {
			return importOrder;
		}
		return buildImportOrder(metadata.tables().stream()
				.map(TableMetadata::name)
				.toList());
	}

	private long insertTables(List<String> importOrder,
							  List<TableMetadata> metadata,
							  Map<String, byte[]> tableData,
							  List<DepotActiveSnapshot> depotActiveSnapshots) throws IOException {
		long rowsImported = 0;
		Map<String, TableMetadata> metadataByName = metadata.stream()
				.collect(Collectors.toMap(TableMetadata::name, meta -> meta));
		for (String table : importOrder) {
			TableMetadata meta = metadataByName.get(table);
			if (meta == null) {
				continue;
			}
			byte[] dataBytes = tableData.get(table);
			if (dataBytes == null) {
				continue;
			}
			rowsImported += insertTableRows(table, dataBytes, meta.rowCount(), depotActiveSnapshots);
		}
		return rowsImported;
	}

	private long insertTableRows(String tableName,
								 byte[] jsonData,
								 int expectedRows,
								 List<DepotActiveSnapshot> depotActiveSnapshots) throws IOException {
		List<Map<String, Object>> rows = deserializeRows(jsonData);
		if (expectedRows != rows.size()) {
			throw new IllegalArgumentException("Row count mismatch for table: " + tableName);
		}
		if (rows.isEmpty()) {
			return 0;
		}
		List<DossierSupersedes> supersedesUpdates = List.of();
		boolean needsSupersedesUpdate = "instrument_dossiers".equalsIgnoreCase(tableName);
		if (needsSupersedesUpdate) {
			supersedesUpdates = collectSupersedesUpdates(rows);
		}
		if ("depots".equalsIgnoreCase(tableName)) {
			for (Map<String, Object> row : rows) {
				Object active = row.get("active_snapshot_id");
				if (active != null) {
					Long depotId = toLong(row.get("depot_id"));
					Long snapshotId = toLong(active);
					if (depotId != null && snapshotId != null) {
						depotActiveSnapshots.add(new DepotActiveSnapshot(depotId, snapshotId));
					}
				}
				row.put("active_snapshot_id", null);
			}
		}
		Map<String, ColumnInfo> columnInfos = getColumnInfos(tableName);
		// Determine which columns from the backup are actually known in the database schema.
		List<String> requestedColumns = new ArrayList<>(rows.get(0).keySet());
		List<String> columns = requestedColumns.stream()
				.filter(col -> columnInfos.containsKey(col.toLowerCase(Locale.ROOT)))
				.collect(Collectors.toList());
		if (columns.isEmpty()) {
			throw new IllegalArgumentException("No valid columns found for table: " + tableName);
		}
		if (columns.size() != requestedColumns.size()) {
			throw new IllegalArgumentException("Backup contains unknown columns for table: " + tableName);
		}
		String columnList = columns.stream()
				.map(this::quoteIdentifier)
				.collect(Collectors.joining(", "));
		String values = columns.stream()
				.map(column -> ":" + column)
				.collect(Collectors.joining(", "));
		String sql = "INSERT INTO " + quoteIdentifier(tableName) + " (" + columnList + ") VALUES (" + values + ")";
		for (Map<String, Object> row : rows) {
			MapSqlParameterSource params = new MapSqlParameterSource();
			for (String column : columns) {
				ColumnInfo info = columnInfos.get(column.toLowerCase(Locale.ROOT));
				params.addValue(column, prepareValue(row.get(column), info));
			}
			namedParameterJdbcTemplate.update(sql, params);
		}
		if (needsSupersedesUpdate && !supersedesUpdates.isEmpty()) {
			applySupersedesUpdates(supersedesUpdates);
		}
		return rows.size();
	}

	private List<Map<String, Object>> deserializeRows(byte[] jsonData) throws IOException {
		return objectMapper.readValue(jsonData, new TypeReference<>() {
		});
	}

	private void applyDepotActiveSnapshotUpdates(List<DepotActiveSnapshot> updates) {
		if (updates.isEmpty()) {
			return;
		}
		String sql = "UPDATE " + quoteIdentifier("depots") + " SET " + quoteIdentifier("active_snapshot_id") + " = :active WHERE " + quoteIdentifier("depot_id") + " = :depot_id";
		for (DepotActiveSnapshot update : updates) {
			MapSqlParameterSource params = new MapSqlParameterSource();
			params.addValue("active", update.activeSnapshotId());
			params.addValue("depot_id", update.depotId());
			namedParameterJdbcTemplate.update(sql, params);
		}
	}

	private List<DossierSupersedes> collectSupersedesUpdates(List<Map<String, Object>> rows) {
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}
		List<DossierSupersedes> updates = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			if (row == null) {
				continue;
			}
			Long dossierId = toLong(row.get("dossier_id"));
			Long supersedesId = toLong(row.get("supersedes_id"));
			if (dossierId != null && supersedesId != null) {
				updates.add(new DossierSupersedes(dossierId, supersedesId));
				row.put("supersedes_id", null);
			}
		}
		return List.copyOf(updates);
	}

	private void applySupersedesUpdates(List<DossierSupersedes> updates) {
		String sql = "UPDATE " + quoteIdentifier("instrument_dossiers")
				+ " SET " + quoteIdentifier("supersedes_id") + " = :supersedes"
				+ " WHERE " + quoteIdentifier("dossier_id") + " = :dossier";
		for (DossierSupersedes update : updates) {
			MapSqlParameterSource params = new MapSqlParameterSource();
			params.addValue("supersedes", update.supersedesId());
			params.addValue("dossier", update.dossierId());
			namedParameterJdbcTemplate.update(sql, params);
		}
	}

	private Object prepareValue(Object value, ColumnInfo info) {
		if (value == null) {
			return null;
		}
		if (info != null) {
			return switch (info.kind()) {
				case DATE -> prepareDateValue(value);
				case TIMESTAMP -> prepareTimestampValue(value);
				case TIMESTAMP_TZ -> prepareTimestampZoneValue(value);
				case JSON, JSONB -> prepareJsonValue(value, info.kind());
				case OTHER -> stripJsonLike(value);
			};
		}
		if (value instanceof Map<?, ?> || value instanceof List<?> || value instanceof JsonNode) {
			return prepareJsonValue(value);
		}
		return stripJsonLike(value);
	}

	private Object stripJsonLike(Object value) {
		if (value instanceof Map<?, ?> || value instanceof List<?> || value instanceof JsonNode) {
			return prepareJsonValue(value);
		}
		return value;
	}

	private Object prepareJsonValue(Object value) {
		return prepareJsonValue(value, ColumnKind.JSON);
	}

	private Object prepareJsonValue(Object value, ColumnKind kind) {
		Object normalized = unwrapJsonContainer(value);
		if (normalized == null) {
			return null;
		}
		String jsonValue;
		if (normalized instanceof String || normalized instanceof Number || normalized instanceof Boolean) {
			jsonValue = normalized.toString();
		} else {
			try {
				jsonValue = objectMapper.writeValueAsString(normalized);
			} catch (JsonProcessingException e) {
				throw new IllegalStateException("Unable to prepare JSON value.", e);
			}
		}
		return createJsonObject(jsonValue, kind);
	}

	private Object unwrapJsonContainer(Object value) {
		if (!(value instanceof Map<?, ?> map)) {
			return value;
		}
		Object type = map.get("type");
		Object inner = map.get("value");
		Object isNull = map.get("null");
		if (type == null && inner == null) {
			return value;
		}
		String typeText = type == null ? "" : type.toString().toLowerCase(Locale.ROOT);
		if (!"json".equals(typeText) && !"jsonb".equals(typeText)) {
			return value;
		}
		if (Boolean.TRUE.equals(isNull)) {
			return null;
		}
		return inner;
	}

	private Object prepareDateValue(Object value) {
		if (value instanceof LocalDate localDate) {
			return java.sql.Date.valueOf(localDate);
		}
		if (value instanceof java.sql.Date) {
			return value;
		}
		if (value instanceof String str) {
			try {
				return java.sql.Date.valueOf(LocalDate.parse(str));
			} catch (DateTimeParseException ex) {
				try {
					return java.sql.Date.valueOf(LocalDateTime.parse(str).toLocalDate());
				} catch (DateTimeParseException ex2) {
					return value;
				}
			}
		}
		return value;
	}

	private Object prepareTimestampValue(Object value) {
		if (value instanceof Timestamp timestamp) {
			return timestamp;
		}
		if (value instanceof LocalDateTime localDateTime) {
			return Timestamp.valueOf(localDateTime);
		}
		if (value instanceof String str) {
			try {
				return Timestamp.valueOf(LocalDateTime.parse(str));
			} catch (DateTimeParseException ex) {
				try {
					return Timestamp.from(OffsetDateTime.parse(str).toInstant());
				} catch (DateTimeParseException ex2) {
					return value;
				}
			}
		}
		return value;
	}

	private Object prepareTimestampZoneValue(Object value) {
		if (value instanceof Timestamp timestamp) {
			return timestamp;
		}
		if (value instanceof OffsetDateTime offsetDateTime) {
			return Timestamp.from(offsetDateTime.toInstant());
		}
		if (value instanceof Instant instant) {
			return Timestamp.from(instant);
		}
		if (value instanceof String str) {
			try {
				return Timestamp.from(OffsetDateTime.parse(str).toInstant());
			} catch (DateTimeParseException ex) {
				try {
					return Timestamp.from(Instant.parse(str));
				} catch (DateTimeParseException ex2) {
					return value;
				}
			}
		}
		return value;
	}

	private Object createJsonObject(String json, ColumnKind kind) {
		if (!isPostgres()) {
			return json;
		}
		try {
			PGobject pg = new PGobject();
			pg.setType(kind == ColumnKind.JSONB ? "jsonb" : "json");
			pg.setValue(json);
			return pg;
		} catch (SQLException e) {
			throw new IllegalStateException("Unable to create PG JSON object.", e);
		}
	}

	private void truncateTables(Collection<String> tables) {
		if (tables.isEmpty()) {
			return;
		}
		String joined = tables.stream()
				.map(this::quoteIdentifier)
				.collect(Collectors.joining(", "));
		String base = "TRUNCATE TABLE " + joined;
		String sql = base + " RESTART IDENTITY CASCADE";
		try {
			jdbcTemplate.execute(sql);
		} catch (Exception ex) {
			jdbcTemplate.execute(base);
		}
	}

	private void resetSequences(Collection<String> tables) {
		for (SequenceInfo sequence : SEQUENCE_COLUMNS) {
			String table = findTableIgnoreCase(tables, sequence.table);
			if (table != null) {
				resetSequence(table, sequence.column);
			}
		}
	}

	private void resetSequence(String tableName, String columnName) {
		Long maxValue = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(" + quoteIdentifier(columnName) + "), 0) FROM " + quoteIdentifier(tableName), Long.class);
		long lastValue = maxValue == null ? 0L : maxValue;
		if (lastValue < 1) {
			return;
		}
		if (isPostgres()) {
			String seqName = jdbcTemplate.queryForObject("SELECT pg_get_serial_sequence(?, ?)", new Object[]{tableName, columnName}, String.class);
			if (seqName != null) {
				jdbcTemplate.queryForObject("SELECT setval(?, ?, true)", new Object[]{seqName, lastValue}, Long.class);
			}
		}
	}

	private String findTableIgnoreCase(Collection<String> tables, String candidate) {
		return tables.stream()
				.filter(table -> table.equalsIgnoreCase(candidate))
				.findFirst()
				.orElse(null);
	}

	private Map<String, byte[]> readZipEntries(ZipInputStream zip) throws IOException {
		Map<String, byte[]> entries = new HashMap<>();
		ZipEntry entry;
		while ((entry = zip.getNextEntry()) != null) {
			if (entry.isDirectory()) {
				continue;
			}
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			zip.transferTo(buffer);
			entries.put(entry.getName(), buffer.toByteArray());
		}
		return entries;
	}

	private Map<String, ColumnInfo> getColumnInfos(String tableName) {
		return columnInfoCache.computeIfAbsent(tableName.toLowerCase(Locale.ROOT), this::loadColumnInfos);
	}

	private Map<String, ColumnInfo> loadColumnInfos(String tableName) {
		String sql = """
				SELECT column_name, data_type, udt_name
				FROM information_schema.columns
				WHERE table_schema = 'public'
				  AND lower(table_name) = ?
				""";
		Object[] params = new Object[]{tableName.toLowerCase(Locale.ROOT)};
		List<ColumnInfo> infos = jdbcTemplate.query(sql, params, (rs, rowNum) -> {
			String columnName = rs.getString("column_name");
			String dataType = rs.getString("data_type");
			String udtName = rs.getString("udt_name");
			return new ColumnInfo(columnName, ColumnKind.from(dataType, udtName));
		});
		return infos.stream()
				.collect(Collectors.toMap(info -> info.columnName().toLowerCase(Locale.ROOT), info -> info));
	}

	private String quoteIdentifier(String input) {
		String escaped = input.replace("\"", "\"\"");
		return "\"" + escaped + "\"";
	}

	private String sha256(byte[] data) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return toHex(digest.digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available.", e);
		}
	}

	private Long toLong(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return Long.valueOf(value.toString());
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private String toHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder();
		for (byte b : bytes) {
			builder.append(String.format("%02x", b));
		}
		return builder.toString();
	}

	private Map<String, byte[]> readZipEntries(MultipartFile file) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
			return readZipEntries(zip);
		}
	}

	private boolean isPostgres() {
		return databaseProductName != null && databaseProductName.toLowerCase(Locale.ROOT).contains("postgres");
	}

	private String resolveDatabaseProductName() {
		try (var connection = dataSource.getConnection()) {
			return connection.getMetaData().getDatabaseProductName();
		} catch (SQLException e) {
			throw new IllegalStateException("Unable to determine database product name.", e);
		}
	}

	private record TableExport(String tableName, int rowCount, byte[] bytes, String sha256) {
	}

	private record BackupMetadata(int formatVersion,
								  String exportedAt,
								  List<TableMetadata> tables,
								  List<String> importOrder) {
	}

	private record TableMetadata(String name, int rowCount, String sha256) {
	}

	private record SequenceInfo(String table, String column) {
	}

	private enum ColumnKind {
		DATE,
		TIMESTAMP,
		TIMESTAMP_TZ,
		JSON,
		JSONB,
		OTHER;

		static ColumnKind from(String dataType, String udtName) {
			String type = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
			String udt = udtName == null ? "" : udtName.toLowerCase(Locale.ROOT);
			if ("date".equals(type) || "date".equals(udt)) {
				return DATE;
			}
			if ("timestamp without time zone".equals(type) || "timestamp".equals(type) || "timestamp".equals(udt)) {
				return TIMESTAMP;
			}
			if ("timestamp with time zone".equals(type) || "timestamptz".equals(udt)) {
				return TIMESTAMP_TZ;
			}
			if ("json".equals(type) || "json".equals(udt)) {
				return JSON;
			}
			if ("jsonb".equals(type) || "jsonb".equals(udt)) {
				return JSONB;
			}
			return OTHER;
		}
	}

	private record ColumnInfo(String columnName, ColumnKind kind) {
	}

	private record DepotActiveSnapshot(long depotId, long activeSnapshotId) {
	}

	private record DossierSupersedes(long dossierId, long supersedesId) {
	}

}
