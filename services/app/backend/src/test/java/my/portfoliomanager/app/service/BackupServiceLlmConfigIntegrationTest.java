package my.portfoliomanager.app.service;

import my.portfoliomanager.app.dto.LlmRuntimeConfigUpdateDto;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BackupServiceLlmConfigIntegrationTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();
	private static final String ENCRYPTION_PASSWORD = "test-backup-password";

	@Autowired
	private BackupService backupService;

	@Autowired
	private LlmRuntimeConfigService llmRuntimeConfigService;

	@Autowired
	private LlmConfigCryptoService cryptoService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
		registry.add("app.llm-config-encryption-password", () -> ENCRYPTION_PASSWORD);
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void exportContainsDedicatedPlaintextLlmConfigShape() throws Exception {
		storeConfig("standard-plain-key", "websearch-plain-key");

		Map<String, byte[]> entries = readZipEntries(backupService.exportBackup());

		assertThat(entries).containsKey("llm-config.json");
		assertThat(entries).doesNotContainKey("data/llm_config.json");

		JsonNode metadata = objectMapper.readTree(entries.get("metadata.json"));
		assertThat(metadata.path("formatVersion").asInt()).isEqualTo(2);
		assertThat(metadata.path("llmConfig").path("entryName").asText()).isEqualTo("llm-config.json");

		JsonNode llmConfig = objectMapper.readTree(entries.get("llm-config.json"));
		assertThat(llmConfig.path("standard").path("api_key").asText()).isEqualTo("standard-plain-key");
		assertThat(llmConfig.path("websearch").path("mode").asText()).isEqualTo("CUSTOM");
		assertThat(llmConfig.path("websearch").path("api_key").asText()).isEqualTo("websearch-plain-key");
	}

	@Test
	void importReencryptsPlaintextLlmConfigKeys() throws Exception {
		storeConfig("standard-plain-key", "websearch-plain-key");
		byte[] backup = backupService.exportBackup();

		jdbcTemplate.update("delete from llm_config");

		backupService.importBackup(new MockMultipartFile("file", "backup.zip", "application/zip", backup));

		String configJson = jdbcTemplate.queryForObject("select config_json::text from llm_config where id = 1", String.class);
		assertThat(configJson).doesNotContain("standard-plain-key");
		assertThat(configJson).doesNotContain("websearch-plain-key");

		JsonNode stored = objectMapper.readTree(configJson);
		assertThat(cryptoService.decrypt(stored.path("standardApiKeyEncrypted").asText())).isEqualTo("standard-plain-key");
		assertThat(cryptoService.decrypt(stored.path("websearch").path("apiKeyEncrypted").asText())).isEqualTo("websearch-plain-key");
	}

	@Test
	void importWithoutLlmConfigLeavesExistingLlmConfigUnchanged() throws Exception {
		storeConfig("source-plain-key", "source-websearch-key");
		byte[] backupWithoutLlmConfig = removeLlmConfig(backupService.exportBackup());

		storeConfig("target-plain-key", "target-websearch-key");

		backupService.importBackup(new MockMultipartFile("file", "backup.zip", "application/zip", backupWithoutLlmConfig));

		String configJson = jdbcTemplate.queryForObject("select config_json::text from llm_config where id = 1", String.class);
		JsonNode stored = objectMapper.readTree(configJson);
		assertThat(cryptoService.decrypt(stored.path("standardApiKeyEncrypted").asText())).isEqualTo("target-plain-key");
		assertThat(cryptoService.decrypt(stored.path("websearch").path("apiKeyEncrypted").asText())).isEqualTo("target-websearch-key");
	}

	@Test
	void importLegacyCiphertextOnlyLlmConfigReencryptsToCurrentStorage() throws Exception {
		byte[] backup = legacyCiphertextOnlyBackup();

		backupService.importBackup(new MockMultipartFile("file", "legacy-backup.zip", "application/zip", backup));

		String configJson = jdbcTemplate.queryForObject("select config_json::text from llm_config where id = 1", String.class);
		assertThat(configJson).doesNotContain("legacy-standard-key");
		assertThat(configJson).doesNotContain("legacy-websearch-key");

		JsonNode stored = objectMapper.readTree(configJson);
		assertThat(cryptoService.decrypt(stored.path("standardApiKeyEncrypted").asText())).isEqualTo("legacy-standard-key");
		assertThat(cryptoService.decrypt(stored.path("websearch").path("apiKeyEncrypted").asText())).isEqualTo("legacy-websearch-key");
	}

	private void storeConfig(String standardApiKey, String websearchApiKey) {
		llmRuntimeConfigService.updateConfig(new LlmRuntimeConfigUpdateDto(
				new LlmRuntimeConfigUpdateDto.StandardUpdateDto("openai", "https://api.openai.com/v1", "gpt-5-mini", standardApiKey),
				new LlmRuntimeConfigUpdateDto.ActionUpdateDto("CUSTOM", "openai", "https://api.openai.com/v1", "gpt-5-mini", websearchApiKey),
				null,
				null
		));
	}

	private Map<String, byte[]> readZipEntries(byte[] payload) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(payload), StandardCharsets.UTF_8)) {
			ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				entries.put(entry.getName(), zipInputStream.readAllBytes());
			}
		}
		return entries;
	}

	private byte[] removeLlmConfig(byte[] payload) throws Exception {
		Map<String, byte[]> entries = readZipEntries(payload);
		entries.remove("llm-config.json");
		JsonNode metadata = objectMapper.readTree(entries.get("metadata.json"));
		((tools.jackson.databind.node.ObjectNode) metadata).remove("llmConfig");
		entries.put("metadata.json", objectMapper.writeValueAsBytes(metadata));

		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			 ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zipOutputStream.putNextEntry(new ZipEntry(entry.getKey()));
				zipOutputStream.write(entry.getValue());
				zipOutputStream.closeEntry();
			}
			zipOutputStream.finish();
			return outputStream.toByteArray();
		}
	}

	private byte[] legacyCiphertextOnlyBackup() throws Exception {
		String standardEncrypted = cryptoService.encrypt("legacy-standard-key");
		String websearchEncrypted = cryptoService.encrypt("legacy-websearch-key");
		byte[] llmConfigRows = objectMapper.writeValueAsBytes(java.util.List.of(java.util.Map.of(
				"id", 1,
				"config_json", java.util.Map.of(
						"standardProvider", "openai",
						"standardBaseUrl", "https://api.openai.com/v1",
						"standardModel", "gpt-5-mini",
						"standardApiKeyEncrypted", standardEncrypted,
						"websearch", java.util.Map.of(
								"mode", "CUSTOM",
								"provider", "openai",
								"baseUrl", "https://api.openai.com/v1",
								"model", "gpt-5-mini",
								"apiKeyEncrypted", websearchEncrypted
						),
						"extraction", java.util.Map.of("mode", "STANDARD"),
						"narrative", java.util.Map.of("mode", "STANDARD")
				),
				"updated_at", "2026-04-03T00:00:00Z"
		)));
		byte[] metadata = objectMapper.writeValueAsBytes(java.util.Map.of(
				"formatVersion", 1,
				"exportedAt", "2026-04-03T00:00:00Z",
				"tables", java.util.List.of(java.util.Map.of(
						"name", "llm_config",
						"rowCount", 1,
						"sha256", sha256(llmConfigRows)
				)),
				"importOrder", java.util.List.of("llm_config")
		));
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			 ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
			zipOutputStream.putNextEntry(new ZipEntry("metadata.json"));
			zipOutputStream.write(metadata);
			zipOutputStream.closeEntry();
			zipOutputStream.putNextEntry(new ZipEntry("data/llm_config.json"));
			zipOutputStream.write(llmConfigRows);
			zipOutputStream.closeEntry();
			zipOutputStream.finish();
			return outputStream.toByteArray();
		}
	}

	private String sha256(byte[] data) throws Exception {
		java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
		StringBuilder builder = new StringBuilder();
		for (byte b : digest.digest(data)) {
			builder.append(String.format("%02x", b));
		}
		return builder.toString();
	}
}
