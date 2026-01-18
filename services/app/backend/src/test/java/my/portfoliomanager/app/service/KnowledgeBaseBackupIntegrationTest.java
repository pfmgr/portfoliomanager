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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class KnowledgeBaseBackupIntegrationTest {
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
	void exportAndImportReplacesExistingKnowledgeBase() throws Exception {
		importBackupFixture();

		long dossiers = countTable("instrument_dossiers");
		long dossierExtractions = countTable("instrument_dossier_extractions");
		long kbExtractions = countTable("knowledge_base_extractions");
		assertThat(dossiers).isGreaterThan(0);

		byte[] exportPayload = knowledgeBaseBackupService.exportKnowledgeBase();
		assertThat(exportPayload).isNotEmpty();

		insertExtraKnowledgeBaseData();
		assertThat(countTable("instrument_dossiers")).isGreaterThan(dossiers);
		assertThat(countTable("instrument_dossier_extractions")).isGreaterThan(dossierExtractions);
		assertThat(countTable("knowledge_base_extractions")).isGreaterThan(kbExtractions);

		MockMultipartFile file = new MockMultipartFile("file", "knowledge-base.zip", "application/zip", exportPayload);
		KnowledgeBaseImportResultDto result = knowledgeBaseBackupService.importKnowledgeBase(file);

		assertThat(countTable("instrument_dossiers")).isEqualTo(dossiers);
		assertThat(countTable("instrument_dossier_extractions")).isEqualTo(dossierExtractions);
		assertThat(countTable("knowledge_base_extractions")).isEqualTo(kbExtractions);
		assertThat(result.dossiersImported()).isEqualTo((int) dossiers);
	}

	@Test
	void importRestoresKnowledgeBaseWhenEmpty() throws Exception {
		importBackupFixture();

		long dossiers = countTable("instrument_dossiers");
		long dossierExtractions = countTable("instrument_dossier_extractions");
		long kbExtractions = countTable("knowledge_base_extractions");

		byte[] exportPayload = knowledgeBaseBackupService.exportKnowledgeBase();
		assertThat(exportPayload).isNotEmpty();

		clearKnowledgeBase();
		assertThat(countTable("instrument_dossiers")).isZero();
		assertThat(countTable("instrument_dossier_extractions")).isZero();
		assertThat(countTable("knowledge_base_extractions")).isZero();

		MockMultipartFile file = new MockMultipartFile("file", "knowledge-base.zip", "application/zip", exportPayload);
		knowledgeBaseBackupService.importKnowledgeBase(file);

		assertThat(countTable("instrument_dossiers")).isEqualTo(dossiers);
		assertThat(countTable("instrument_dossier_extractions")).isEqualTo(dossierExtractions);
		assertThat(countTable("knowledge_base_extractions")).isEqualTo(kbExtractions);
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

	private void insertExtraKnowledgeBaseData() {
		String depot = jdbcTemplate.queryForObject("select depot_code from depots limit 1", String.class);
		String isin = "TESTKB000001";
		Integer existing = jdbcTemplate.queryForObject("select count(*) from instruments where isin = ?", Integer.class, isin);
		if (existing == null || existing == 0) {
			jdbcTemplate.update("""
					insert into instruments (isin, name, depot_code, layer, is_deleted)
					values (?, 'Test KB Instrument', ?, 5, false)
					""", isin, depot);
		}

		jdbcTemplate.update("""
				insert into instrument_dossiers
					(isin, created_by, origin, status, content_md, citations_json, content_hash, created_at, updated_at)
				values (?, 'test', 'IMPORT', 'CREATED', 'content', '[]'::jsonb, 'hash', now(), now())
				""", isin);
		Long dossierId = jdbcTemplate.queryForObject(
				"select dossier_id from instrument_dossiers where isin = ? order by dossier_id desc limit 1",
				Long.class,
				isin
		);
		jdbcTemplate.update("""
				insert into instrument_dossier_extractions
					(dossier_id, model, extracted_json, status, created_at)
				values (?, 'test-model', '{}'::jsonb, 'CREATED', now())
				""", dossierId);
		jdbcTemplate.update("""
				insert into knowledge_base_extractions (isin, status, extracted_json, updated_at)
				values (?, 'CREATED', '{}'::jsonb, now())
				""", isin);
	}
}
