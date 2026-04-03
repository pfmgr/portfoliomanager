package my.portfoliomanager.app.service;

import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class BackupServiceLlmConfigNoPasswordIntegrationTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private BackupService backupService;

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
		registry.add("app.llm-config-encryption-password", () -> "");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void importFailsWithoutEncryptionPasswordWhenBackupContainsApiKeys() throws Exception {
		byte[] llmConfig = objectMapper.writeValueAsBytes(Map.of(
				"standard", Map.of(
						"provider", "openai",
						"base_url", "https://api.openai.com/v1",
						"model", "gpt-5-mini",
						"api_key", "plaintext-key"
				),
				"websearch", Map.of("mode", "STANDARD"),
				"extraction", Map.of("mode", "STANDARD"),
				"narrative", Map.of("mode", "STANDARD")
		));
		byte[] metadata = objectMapper.writeValueAsBytes(Map.of(
				"formatVersion", 2,
				"exportedAt", "2026-04-03T00:00:00Z",
				"tables", List.of(),
				"importOrder", List.of(),
				"llmConfig", Map.of(
						"entryName", "llm-config.json",
						"sha256", sha256(llmConfig)
				)
		));

		byte[] backup = zip(Map.of(
				"metadata.json", metadata,
				"llm-config.json", llmConfig
		));

		assertThatThrownBy(() -> backupService.importBackup(new MockMultipartFile("file", "backup.zip", "application/zip", backup)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("LLM_CONFIG_ENCRYPTION_PASSWORD");
	}

	@Test
	void legacyLlmConfigImportFailsWithoutEncryptionPassword() throws Exception {
		var resource = new ClassPathResource("imports/database-backup.zip");
		try (InputStream stream = resource.getInputStream()) {
			var file = new MockMultipartFile("file", "database-backup.zip", "application/zip", stream);
			assertThatThrownBy(() -> backupService.importBackup(file))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("LLM_CONFIG_ENCRYPTION_PASSWORD");
		}
	}

	private byte[] zip(Map<String, byte[]> entries) throws Exception {
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

	private String sha256(byte[] data) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		StringBuilder builder = new StringBuilder();
		for (byte b : digest.digest(data)) {
			builder.append(String.format("%02x", b));
		}
		return builder.toString();
	}
}
