package my.portfoliomanager.app.service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import org.postgresql.util.PGobject;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import my.portfoliomanager.app.dto.BackupImportResultDto;
import my.portfoliomanager.app.dto.LlmConfigBackupDto;
import my.portfoliomanager.app.service.util.BackupContainerCrypto;
import my.portfoliomanager.app.service.util.ZipEntryReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.io.PushbackInputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class BackupService {
	private static final int FORMAT_VERSION = 2;
	private static final String METADATA_ENTRY = "metadata.json";
	private static final String DATA_PREFIX = "data/";
	private static final String LLM_CONFIG_ENTRY = "llm-config.json";
	private static final String TABLE_AUTH_TOKENS = "auth_tokens";
	private static final String TABLE_DEPOTS = "depots";
	private static final String TABLE_INSTRUMENT_DOSSIERS = "instrument_dossiers";
	private static final String TABLE_LLM_CONFIG = "llm_config";
	private static final String COLUMN_DEPOT_ID = "depot_id";
	private static final String COLUMN_DOSSIER_ID = "dossier_id";
	private static final String COLUMN_ACTIVE_SNAPSHOT_ID = "active_snapshot_id";
	private static final String COLUMN_SUPERSEDES_ID = "supersedes_id";
	private static final String TYPE_JSON = "json";
	private static final String TYPE_JSONB = "jsonb";
	private static final Set<String> EXCLUDED_TABLES = Set.of("databasechangelog", "databasechangeloglock", TABLE_AUTH_TOKENS);
	private static final List<String> KNOWN_IMPORT_ORDER = List.of(
			TABLE_DEPOTS,
			"instruments",
			TABLE_INSTRUMENT_DOSSIERS,
			"instrument_blacklists",
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
			new SequenceInfo(TABLE_DEPOTS, COLUMN_DEPOT_ID),
			new SequenceInfo(TABLE_AUTH_TOKENS, "id"),
			new SequenceInfo("sparplans", "sparplan_id"),
			new SequenceInfo("sparplans_history", "hist_id"),
			new SequenceInfo("rulesets", "id"),
			new SequenceInfo("instrument_edits", "id"),
			new SequenceInfo(TABLE_INSTRUMENT_DOSSIERS, COLUMN_DOSSIER_ID),
			new SequenceInfo("instrument_blacklists", "blacklist_id"),
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
	private final LlmRuntimeConfigService llmRuntimeConfigService;
	private final ObjectMapper objectMapper;
	private final String databaseProductName;
	private final Map<String, Map<String, ColumnInfo>> columnInfoCache = new ConcurrentHashMap<>();

	public BackupService(JdbcTemplate jdbcTemplate,
						 NamedParameterJdbcTemplate namedParameterJdbcTemplate,
						 DataSource dataSource,
						 LlmRuntimeConfigService llmRuntimeConfigService) {
		this.jdbcTemplate = jdbcTemplate;
		this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
		this.dataSource = dataSource;
		this.llmRuntimeConfigService = llmRuntimeConfigService;
		this.objectMapper = JsonMapper.builder()
				.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
				.build();
		this.databaseProductName = resolveDatabaseProductName();
	}

	public byte[] exportBackup(String password) {
		String backupPassword = requirePassword(password);
		List<String> tables = fetchTableNames();
		List<TableExport> exports = tables.stream()
				.map(this::exportTable)
				.collect(Collectors.toList());
		LlmConfigBackupDto llmConfig = llmRuntimeConfigService.exportBackupConfig();
		List<String> importOrder = buildImportOrder(tables);
		List<TableMetadata> metadataTables = exports.stream()
				.map(export -> new TableMetadata(export.tableName(), export.rowCount(), export.sha256()))
				.toList();
		BackupMetadata metadata = new BackupMetadata(
				FORMAT_VERSION,
				Instant.now().toString(),
				metadataTables,
				importOrder,
				llmConfig == null ? null : new LlmConfigMetadata(LLM_CONFIG_ENTRY, sha256(writeJson(llmConfig)))
		);
		byte[] zip = writeZip(metadata, exports, llmConfig);
		return BackupContainerCrypto.encrypt(zip, backupPassword);
	}

	public byte[] exportBackup() {
		List<String> tables = fetchTableNames();
		List<TableExport> exports = tables.stream()
				.map(this::exportTable)
				.collect(Collectors.toList());
		LlmConfigBackupDto llmConfig = llmRuntimeConfigService.exportBackupConfig();
		List<String> importOrder = buildImportOrder(tables);
		List<TableMetadata> metadataTables = exports.stream()
				.map(export -> new TableMetadata(export.tableName(), export.rowCount(), export.sha256()))
				.toList();
		BackupMetadata metadata = new BackupMetadata(
				FORMAT_VERSION,
				Instant.now().toString(),
				metadataTables,
				importOrder,
				llmConfig == null ? null : new LlmConfigMetadata(LLM_CONFIG_ENTRY, sha256(writeJson(llmConfig)))
		);
		return writeZip(metadata, exports, llmConfig);
	}

	@Transactional
	public BackupImportResultDto importBackup(MultipartFile file) {
		return importBackup(file, null);
	}

	@Transactional
	public BackupImportResultDto importBackup(MultipartFile file, String password) {
		try {
			Map<String, byte[]> entries = readEntries(file, password);
			byte[] metadataBytes = entries.remove(METADATA_ENTRY);
			if (metadataBytes == null) {
				throw new IllegalArgumentException("Backup is missing metadata.");
			}
			BackupMetadata metadata = objectMapper.readValue(metadataBytes, BackupMetadata.class);
			if (metadata.formatVersion() > FORMAT_VERSION) {
				throw new IllegalArgumentException("Unsupported backup format version: " + metadata.formatVersion());
			}

			LlmConfigBackupDto llmConfig = readLlmConfig(metadata, entries);
			List<TableMetadata> importedTables = filterImportedTables(metadata.tables());
			Map<String, byte[]> tableData = prepareTableData(importedTables, entries);
			List<String> tables = importedTables.stream()
					.map(TableMetadata::name)
					.toList();
			List<String> tablesToReset = new ArrayList<>(tables);
			tablesToReset.add(TABLE_AUTH_TOKENS);
			truncateTables(tablesToReset);
			List<String> importOrder = determineImportOrder(metadata);
			List<DepotActiveSnapshot> depotActiveSnapshots = new ArrayList<>();
			long rowsImported = insertTables(importOrder, importedTables, tableData, depotActiveSnapshots);
			applyDepotActiveSnapshotUpdates(depotActiveSnapshots);
			resetSequences(tablesToReset);
			llmRuntimeConfigService.importBackupConfig(llmConfig);
			return new BackupImportResultDto(tables.size(), rowsImported, metadata.formatVersion(), metadata.exportedAt());
		} catch (IOException e) {
			throw new IllegalArgumentException("Unable to read backup archive.", e);
		}
	}

	private Map<String, byte[]> readEntries(MultipartFile file, String password) throws IOException {
		try (InputStream inputStream = file.getInputStream();
			 PushbackInputStream pushbackInputStream = new PushbackInputStream(inputStream, BackupContainerCrypto.headerLength())) {
			byte[] header = pushbackInputStream.readNBytes(BackupContainerCrypto.headerLength());
			if (BackupContainerCrypto.isEncrypted(header)) {
				String backupPassword = requirePassword(password);
				try {
					return ZipEntryReader.readZipEntries(new java.util.zip.ZipInputStream(
						BackupContainerCrypto.decrypt(pushbackInputStream, backupPassword),
						StandardCharsets.UTF_8));
				} catch (IOException e) {
					throw new IllegalArgumentException("Unable to decrypt backup container.", e);
				}
			}
			pushbackInputStream.unread(header);
			return ZipEntryReader.readZipEntries(new java.util.zip.ZipInputStream(pushbackInputStream, StandardCharsets.UTF_8));
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
				.filter(name -> !TABLE_LLM_CONFIG.equalsIgnoreCase(name))
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

	private byte[] writeJson(Object value) {
		try {
			return objectMapper.writeValueAsBytes(value);
		} catch (JacksonException e) {
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

	private byte[] writeZip(BackupMetadata metadata, List<TableExport> exports, LlmConfigBackupDto llmConfig) {
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
			 ZipOutputStream zip = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
			for (TableExport export : exports) {
				ZipEntry entry = new ZipEntry(DATA_PREFIX + export.tableName() + ".json");
				zip.putNextEntry(entry);
				zip.write(export.bytes());
				zip.closeEntry();
			}
			if (llmConfig != null) {
				ZipEntry llmEntry = new ZipEntry(LLM_CONFIG_ENTRY);
				zip.putNextEntry(llmEntry);
				zip.write(writeJson(llmConfig));
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

	private Map<String, byte[]> prepareTableData(List<TableMetadata> tables, Map<String, byte[]> entries) {
		Map<String, byte[]> tableData = new HashMap<>();
		for (TableMetadata table : tables) {
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

	private List<TableMetadata> filterImportedTables(List<TableMetadata> tables) {
		if (tables == null) {
			return List.of();
		}
		return tables.stream()
				.filter(table -> table != null
						&& !TABLE_LLM_CONFIG.equalsIgnoreCase(table.name())
						&& !TABLE_AUTH_TOKENS.equalsIgnoreCase(table.name()))
				.toList();
	}

	private List<String> determineImportOrder(BackupMetadata metadata) {
		List<String> importOrder = metadata.importOrder();
		if (importOrder != null && !importOrder.isEmpty()) {
			return importOrder.stream()
					.filter(table -> !TABLE_LLM_CONFIG.equalsIgnoreCase(table))
					.filter(table -> !TABLE_AUTH_TOKENS.equalsIgnoreCase(table))
					.toList();
		}
		return buildImportOrder(filterImportedTables(metadata.tables()).stream()
				.map(TableMetadata::name)
				.toList());
	}

	private LlmConfigBackupDto readLlmConfig(BackupMetadata metadata, Map<String, byte[]> entries) throws IOException {
		LlmConfigMetadata llmConfig = metadata.llmConfig();
		if (llmConfig != null) {
			String entryName = llmConfig.entryName() == null || llmConfig.entryName().isBlank()
					? LLM_CONFIG_ENTRY
					: llmConfig.entryName();
			byte[] data = entries.remove(entryName);
			if (data == null) {
				throw new IllegalArgumentException("Missing data for llm_config backup.");
			}
			String computed = sha256(data);
			if (!computed.equals(llmConfig.sha256())) {
				throw new IllegalArgumentException("Backup corrupted for llm_config backup.");
			}
			return objectMapper.readValue(data, LlmConfigBackupDto.class);
		}

			TableMetadata legacyTable = metadata.tables() == null ? null : metadata.tables().stream()
					.filter(table -> table != null && TABLE_LLM_CONFIG.equalsIgnoreCase(table.name()))
					.findFirst()
					.orElse(null);
		if (legacyTable == null) {
			return null;
		}
		String entryName = DATA_PREFIX + legacyTable.name() + ".json";
		byte[] data = entries.remove(entryName);
		if (data == null) {
			throw new IllegalArgumentException("Missing data for legacy llm_config backup.");
		}
		String computed = sha256(data);
		if (!computed.equals(legacyTable.sha256())) {
			throw new IllegalArgumentException("Backup corrupted for legacy llm_config backup.");
		}
		return readLegacyLlmConfig(data);
	}

	private LlmConfigBackupDto readLegacyLlmConfig(byte[] data) throws IOException {
		JsonNode rows = objectMapper.readTree(data);
		if (!rows.isArray() || rows.isEmpty()) {
			return null;
		}
		JsonNode config = rows.get(0).path("config_json");
		if (config.isMissingNode() || config.isNull()) {
			return null;
		}
		return new LlmConfigBackupDto(
				new LlmConfigBackupDto.StandardBackupDto(
						textOrNull(config, "standardProvider"),
						textOrNull(config, "standardBaseUrl"),
						textOrNull(config, "standardModel"),
						legacyApiKey(config, "standardApiKey", "standardApiKeyEncrypted")
				),
				legacyAction(config.path("websearch")),
				legacyAction(config.path("extraction")),
				legacyAction(config.path("narrative"))
		);
	}

	private LlmConfigBackupDto.ActionBackupDto legacyAction(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return new LlmConfigBackupDto.ActionBackupDto("STANDARD", null, null, null, null);
		}
		return new LlmConfigBackupDto.ActionBackupDto(
				textOrNull(node, "mode"),
				textOrNull(node, "provider"),
				textOrNull(node, "baseUrl"),
				textOrNull(node, "model"),
				legacyApiKey(node, "apiKey", "apiKeyEncrypted")
		);
	}

	private String legacyApiKey(JsonNode node, String plaintextField, String encryptedField) {
		return llmRuntimeConfigService.resolveLegacyBackupApiKey(
				textOrNull(node, plaintextField),
				textOrNull(node, encryptedField)
		);
	}

	private String textOrNull(JsonNode node, String field) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asText();
		return text == null || text.isBlank() ? null : text;
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
		validateTableName(tableName);
		List<Map<String, Object>> rows = deserializeRows(jsonData);
		validateExpectedRowCount(tableName, expectedRows, rows.size());
		if (rows.isEmpty()) {
			return 0;
		}
		List<DossierSupersedes> supersedesUpdates = collectSupersedesUpdatesIfNeeded(tableName, rows);
		collectDepotActiveSnapshotsIfNeeded(tableName, rows, depotActiveSnapshots);
		Map<String, ColumnInfo> columnInfos = getColumnInfos(tableName);
		List<String> columns = resolveInsertColumns(tableName, rows, columnInfos);
		String sql = buildInsertSql(tableName, columns);
		insertRows(tableName, rows, columns, columnInfos, sql);
		if (!supersedesUpdates.isEmpty()) {
			applySupersedesUpdates(supersedesUpdates);
		}
		return rows.size();
	}

	private void validateTableName(String tableName) {
		if (!isValidIdentifier(tableName)) {
			throw new IllegalArgumentException("Invalid table name in backup: " + tableName);
		}
	}

	private void validateExpectedRowCount(String tableName, int expectedRows, int actualRows) {
		if (expectedRows != actualRows) {
			throw new IllegalArgumentException("Row count mismatch for table: " + tableName);
		}
	}

	private List<DossierSupersedes> collectSupersedesUpdatesIfNeeded(String tableName, List<Map<String, Object>> rows) {
		if (!TABLE_INSTRUMENT_DOSSIERS.equalsIgnoreCase(tableName)) {
			return List.of();
		}
		return collectSupersedesUpdates(rows);
	}

	private void collectDepotActiveSnapshotsIfNeeded(String tableName,
												 List<Map<String, Object>> rows,
												 List<DepotActiveSnapshot> depotActiveSnapshots) {
		if (!TABLE_DEPOTS.equalsIgnoreCase(tableName)) {
			return;
		}
		for (Map<String, Object> row : rows) {
			Object active = row.get(COLUMN_ACTIVE_SNAPSHOT_ID);
			if (active != null) {
				Long depotId = toLong(row.get(COLUMN_DEPOT_ID));
				Long snapshotId = toLong(active);
				if (depotId != null && snapshotId != null) {
					depotActiveSnapshots.add(new DepotActiveSnapshot(depotId, snapshotId));
				}
			}
			row.put(COLUMN_ACTIVE_SNAPSHOT_ID, null);
		}
	}

	private List<String> resolveInsertColumns(String tableName,
										 List<Map<String, Object>> rows,
										 Map<String, ColumnInfo> columnInfos) {
		List<String> requestedColumns = new ArrayList<>(rows.get(0).keySet());
		List<String> columns = requestedColumns.stream()
				.filter(this::isValidIdentifier)
				.filter(col -> columnInfos.containsKey(col.toLowerCase(Locale.ROOT)))
				.toList();
		if (columns.isEmpty()) {
			throw new IllegalArgumentException("No valid columns found for table: " + tableName);
		}
		if (columns.size() != requestedColumns.size()) {
			throw new IllegalArgumentException("Backup contains unknown or invalid columns for table: " + tableName);
		}
		return columns;
	}

	private String buildInsertSql(String tableName, List<String> columns) {
		String columnList = columns.stream()
				.map(this::quoteIdentifier)
				.collect(Collectors.joining(", "));
		String values = columns.stream()
				.map(column -> ":" + column)
				.collect(Collectors.joining(", "));
		return "INSERT INTO " + quoteIdentifier(tableName) + " (" + columnList + ") VALUES (" + values + ")";
	}

	private void insertRows(String tableName,
						 List<Map<String, Object>> rows,
						 List<String> columns,
						 Map<String, ColumnInfo> columnInfos,
						 String sql) {
		for (Map<String, Object> row : rows) {
			MapSqlParameterSource params = new MapSqlParameterSource();
			for (String column : columns) {
				ColumnInfo info = columnInfos.get(column.toLowerCase(Locale.ROOT));
				params.addValue(column, prepareValue(row.get(column), info));
			}
			try {
				namedParameterJdbcTemplate.update(sql, params);
			} catch (DataAccessException ex) {
				String message = "Backup import failed for table '" + tableName + "' (" + buildRowContext(row)
						+ "): " + extractRootCauseMessage(ex);
				throw new IllegalArgumentException(message, ex);
			}
		}
	}

	private boolean isValidIdentifier(String name) {
		return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*");
	}

	private String buildRowContext(Map<String, Object> row) {
		if (row == null || row.isEmpty()) {
			return "row=unknown";
		}
		Object isin = row.get("isin");
		Object depotId = row.get(COLUMN_DEPOT_ID);
		Object snapshotId = row.get("snapshot_id");
		List<String> parts = new ArrayList<>();
		if (isin != null) {
			parts.add("isin=" + isin);
		}
		if (depotId != null) {
			parts.add("depot_id=" + depotId);
		}
		if (snapshotId != null) {
			parts.add("snapshot_id=" + snapshotId);
		}
		if (!parts.isEmpty()) {
			return String.join(", ", parts);
		}
		return "columns=" + String.join(",", row.keySet().stream().sorted().toList());
	}

	private String extractRootCauseMessage(Throwable throwable) {
		Throwable current = throwable;
		while (current != null && current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		if (current == null || current.getMessage() == null || current.getMessage().isBlank()) {
			return "Unknown database error";
		}
		return current.getMessage();
	}

	private List<Map<String, Object>> deserializeRows(byte[] jsonData) throws IOException {
		return objectMapper.readValue(jsonData, new TypeReference<>() {
		});
	}

	private void applyDepotActiveSnapshotUpdates(List<DepotActiveSnapshot> updates) {
		if (updates.isEmpty()) {
			return;
		}
		String sql = "UPDATE " + quoteIdentifier(TABLE_DEPOTS) + " SET " + quoteIdentifier(COLUMN_ACTIVE_SNAPSHOT_ID) + " = :active WHERE " + quoteIdentifier(COLUMN_DEPOT_ID) + " = :" + COLUMN_DEPOT_ID;
		for (DepotActiveSnapshot update : updates) {
			MapSqlParameterSource params = new MapSqlParameterSource();
			params.addValue("active", update.activeSnapshotId());
			params.addValue(COLUMN_DEPOT_ID, update.depotId());
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
			Long dossierId = toLong(row.get(COLUMN_DOSSIER_ID));
			Long supersedesId = toLong(row.get(COLUMN_SUPERSEDES_ID));
			if (dossierId != null && supersedesId != null) {
				updates.add(new DossierSupersedes(dossierId, supersedesId));
				row.put(COLUMN_SUPERSEDES_ID, null);
			}
		}
		return List.copyOf(updates);
	}

	private void applySupersedesUpdates(List<DossierSupersedes> updates) {
		String sql = "UPDATE " + quoteIdentifier(TABLE_INSTRUMENT_DOSSIERS)
				+ " SET " + quoteIdentifier(COLUMN_SUPERSEDES_ID) + " = :supersedes"
				+ " WHERE " + quoteIdentifier(COLUMN_DOSSIER_ID) + " = :dossier";
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
			} catch (JacksonException e) {
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
		if (!TYPE_JSON.equals(typeText) && !TYPE_JSONB.equals(typeText)) {
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
		if (value instanceof OffsetDateTime offsetDateTime) {
			return java.sql.Date.valueOf(offsetDateTime.toLocalDate());
		}
		if (value instanceof Instant instant) {
			return java.sql.Date.valueOf(instant.atOffset(ZoneOffset.UTC).toLocalDate());
		}
		if (value instanceof Number number) {
			Object converted = prepareDateFromEpoch(number.longValue());
			if (converted != null) {
				return converted;
			}
		}
		if (value instanceof String str) {
			return prepareDateValueFromString(str, value);
		}
		return value;
	}

	private Object prepareDateValueFromString(String raw, Object fallback) {
		String trimmed = raw.trim();
		if (trimmed.isEmpty()) {
			return fallback;
		}
		LocalDate date = parseDateText(trimmed);
		if (date != null) {
			return java.sql.Date.valueOf(date);
		}
		Long epoch = parseLong(trimmed);
		if (epoch == null) {
			return fallback;
		}
		Object converted = prepareDateFromEpoch(epoch);
		return converted == null ? fallback : converted;
	}

	private LocalDate parseDateText(String text) {
		LocalDate localDate = tryParseLocalDate(text);
		if (localDate != null) {
			return localDate;
		}
		LocalDateTime localDateTime = tryParseLocalDateTime(text);
		if (localDateTime != null) {
			return localDateTime.toLocalDate();
		}
		OffsetDateTime offsetDateTime = tryParseOffsetDateTime(text);
		if (offsetDateTime != null) {
			return offsetDateTime.toLocalDate();
		}
		Instant instant = tryParseInstant(text);
		if (instant != null) {
			return instant.atOffset(ZoneOffset.UTC).toLocalDate();
		}
		return null;
	}

	private LocalDate tryParseLocalDate(String text) {
		try {
			return LocalDate.parse(text);
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	private LocalDateTime tryParseLocalDateTime(String text) {
		try {
			return LocalDateTime.parse(text);
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	private OffsetDateTime tryParseOffsetDateTime(String text) {
		try {
			return OffsetDateTime.parse(text);
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	private Instant tryParseInstant(String text) {
		try {
			return Instant.parse(text);
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	private Long parseLong(String value) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private Object prepareDateFromEpoch(long epochValue) {
		long abs = Math.abs(epochValue);
		if (abs < 1_000_000_000L) {
			return null;
		}
		Instant instant = abs >= 1_000_000_000_000L
				? Instant.ofEpochMilli(epochValue)
				: Instant.ofEpochSecond(epochValue);
		return java.sql.Date.valueOf(instant.atOffset(ZoneOffset.UTC).toLocalDate());
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
			pg.setType(kind == ColumnKind.JSONB ? TYPE_JSONB : TYPE_JSON);
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

	private Map<String, byte[]> readZipEntries(byte[] payload) throws IOException {
		return ZipEntryReader.readZipEntries(payload);
	}

	private String requirePassword(String password) {
		if (password == null || password.isBlank()) {
			throw new IllegalStateException("Backup password is required for encrypted backup containers.");
		}
		return password;
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
		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof TableExport other)) {
				return false;
			}
			return rowCount == other.rowCount
					&& tableName.equals(other.tableName)
					&& sha256.equals(other.sha256)
					&& Arrays.equals(bytes, other.bytes);
		}

		@Override
		public int hashCode() {
			int result = tableName.hashCode();
			result = 31 * result + Integer.hashCode(rowCount);
			result = 31 * result + Arrays.hashCode(bytes);
			result = 31 * result + sha256.hashCode();
			return result;
		}

		@Override
		public String toString() {
			return "TableExport[tableName=" + tableName
					+ ", rowCount=" + rowCount
					+ ", bytes=" + (bytes == null ? 0 : bytes.length)
					+ ", sha256=" + sha256 + "]";
		}
	}

	private record BackupMetadata(int formatVersion,
								  String exportedAt,
								  List<TableMetadata> tables,
								  List<String> importOrder,
								  LlmConfigMetadata llmConfig) {
	}

	private record LlmConfigMetadata(String entryName, String sha256) {
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
			if (TYPE_JSON.equals(type) || TYPE_JSON.equals(udt)) {
				return JSON;
			}
			if (TYPE_JSONB.equals(type) || TYPE_JSONB.equals(udt)) {
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
