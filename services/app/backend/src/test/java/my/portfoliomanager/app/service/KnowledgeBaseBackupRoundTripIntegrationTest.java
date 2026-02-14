package my.portfoliomanager.app.service;

import my.portfoliomanager.app.dto.KnowledgeBaseImportResultDto;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class KnowledgeBaseBackupRoundTripIntegrationTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private BackupService backupService;

	@Autowired
	private KnowledgeBaseBackupService knowledgeBaseBackupService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void exportAndImportRoundTripPreservesSampleDossier() throws Exception {
		importBackupFixture();

		long dossiers = countTable("instrument_dossiers");
		long dossierExtractions = countTable("instrument_dossier_extractions");
		long kbExtractions = countTable("knowledge_base_extractions");
		assertThat(dossiers).isGreaterThan(0);

		Map<String, Object> sample = jdbcTemplate.queryForMap(
				"select isin, content_hash from instrument_dossiers order by dossier_id limit 1"
		);
		String isin = sample.get("isin").toString();
		String contentHash = sample.get("content_hash").toString();

		byte[] exportPayload = knowledgeBaseBackupService.exportKnowledgeBase();
		assertThat(exportPayload).isNotEmpty();

		clearKnowledgeBase();
		assertThat(countTable("instrument_dossiers")).isZero();

		MockMultipartFile file = new MockMultipartFile("file", "knowledge-base.zip", "application/zip", exportPayload);
		KnowledgeBaseImportResultDto result = knowledgeBaseBackupService.importKnowledgeBase(file);

		assertThat(countTable("instrument_dossiers")).isEqualTo(dossiers);
		assertThat(countTable("instrument_dossier_extractions")).isEqualTo(dossierExtractions);
		assertThat(countTable("knowledge_base_extractions")).isEqualTo(kbExtractions);
		assertThat(result.dossiersImported()).isEqualTo((int) dossiers);

		Integer matching = jdbcTemplate.queryForObject(
				"select count(*) from instrument_dossiers where isin = ? and content_hash = ?",
				Integer.class,
				isin,
				contentHash
		);
		assertThat(matching).isNotNull();
		assertThat(matching).isGreaterThan(0);
	}

	private void importBackupFixture() throws Exception {
		var resource = new ClassPathResource("imports/database-backup_15_01_2059.zip");
		try (InputStream stream = resource.getInputStream()) {
			var file = new MockMultipartFile("file", "database-backup_15_01_2059.zip", "application/zip", stream);
			backupService.importBackup(file);
		}
	}

	private long countTable(String table) {
		Long value = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
		return value == null ? 0L : value;
	}

	private void clearKnowledgeBase() {
		jdbcTemplate.update("delete from knowledge_base_extractions");
		jdbcTemplate.update("delete from instrument_dossier_extractions");
		jdbcTemplate.update("delete from instrument_dossiers");
	}
}
