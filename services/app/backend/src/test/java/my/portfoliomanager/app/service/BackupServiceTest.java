package my.portfoliomanager.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import my.portfoliomanager.app.service.util.BackupContainerCrypto;

import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class BackupServiceTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private BackupService backupService;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@Autowired
	private LlmRuntimeConfigService llmRuntimeConfigService;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
		registry.add("app.llm-config-encryption-password", () -> "backup-test-password");
	}

	@Test
	@Transactional
	void canImportDatabaseBackupFixture() throws Exception {
		var resource = new ClassPathResource("imports/database-backup.zip");
		try (InputStream stream = resource.getInputStream()) {
			var file = new MockMultipartFile("file", "database-backup.zip", "application/zip", stream);
			var result = backupService.importBackup(file);
			assertThat(result).isNotNull();
			assertThat(result.tablesImported()).isGreaterThan(0);
			var llmConfig = llmRuntimeConfigService.getConfig();
			assertThat(llmConfig.standard().apiKeySet()).isTrue();
			assertThat(llmConfig.websearch().mode()).isEqualTo("CUSTOM");
		}
	}

	@Test
	@Transactional
	void canImport2059BackupFixture() throws Exception {
		var resource = new ClassPathResource("imports/database-backup_15_01_2059.zip");
		try (InputStream stream = resource.getInputStream()) {
			var file = new MockMultipartFile("file", "database-backup_15_01_2059.zip", "application/zip", stream);
			var result = backupService.importBackup(file);
			assertThat(result).isNotNull();
			assertThat(result.tablesImported()).isGreaterThan(0);
		}
	}

	@Test
	@Transactional
	void canImportSanitizedBackupFixtureWithZuluDates() throws Exception {
		var resource = new ClassPathResource("imports/database-backup_14_02_2026_sanitized.zip");
		try (InputStream stream = resource.getInputStream()) {
			var file = new MockMultipartFile("file", "database-backup_14_02_2026_sanitized.zip", "application/zip", stream);
			var result = backupService.importBackup(file);
			assertThat(result).isNotNull();
			assertThat(result.tablesImported()).isGreaterThan(0);
		}
	}

	@Test
	void canImportEncryptedBackupWithPassword() throws Exception {
		byte[] exported = backupService.exportBackup("backup-test-password");
		assertThat(BackupContainerCrypto.isEncrypted(exported)).isTrue();
		byte[] zipBytes = BackupContainerCrypto.decrypt(exported, "backup-test-password");
		var file = new MockMultipartFile("file", "backup.pmbk", "application/octet-stream", exported);

		databaseCleaner.clean();
		var result = backupService.importBackup(file, "backup-test-password");

		assertThat(result).isNotNull();
		assertThat(zipBytes).isNotEmpty();
	}

	@Test
	void encryptedBackupFailsWithoutOrWithWrongPassword() throws Exception {
		byte[] exported = backupService.exportBackup("backup-test-password");
		assertThat(BackupContainerCrypto.isEncrypted(exported)).isTrue();
		var file = new MockMultipartFile("file", "backup.pmbk", "application/octet-stream", exported);

		assertThatThrownBy(() -> backupService.importBackup(file))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Backup password is required");

		assertThatThrownBy(() -> backupService.importBackup(file, "wrong-password"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unable to decrypt backup container");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}
}
