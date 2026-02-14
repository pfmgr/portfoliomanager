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

import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BackupServiceTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private BackupService backupService;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
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

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}
}
