package my.portfoliomanager.app.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.domain.DossierAuthoredBy;
import my.portfoliomanager.app.domain.DossierOrigin;
import my.portfoliomanager.app.domain.DossierStatus;
import my.portfoliomanager.app.domain.InstrumentDossier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import my.portfoliomanager.app.support.TestDatabaseCleaner;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
class InstrumentDossierRepositoryTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private InstrumentDossierRepository dossierRepository;

	@Autowired
	private ObjectMapper objectMapper;

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

	@BeforeEach
	void setup() {
		jdbcTemplate.update("delete from instrument_dossier_extractions");
		jdbcTemplate.update("delete from instrument_dossiers");
		jdbcTemplate.update("delete from instrument_facts");
		jdbcTemplate.update("delete from instrument_overrides");
		jdbcTemplate.update("delete from instrument_classifications");
		jdbcTemplate.update("update depots set active_snapshot_id = null");
		jdbcTemplate.update("delete from snapshot_positions");
		jdbcTemplate.update("delete from snapshots");
		jdbcTemplate.update("delete from sparplans_history");
		jdbcTemplate.update("delete from sparplans");
		jdbcTemplate.update("delete from instruments");
		jdbcTemplate.update("delete from depots");
		jdbcTemplate.update("insert into depots (depot_id, depot_code, name, provider) values (1, 'tr', 'Test Depot', 'TR')");
		jdbcTemplate.update("""
				insert into instruments (isin, name, depot_code, layer, is_deleted)
				values ('DE0000000001', 'Test Instrument', 'tr', 5, false)
				""");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void saveAndLoadDossier() {
		InstrumentDossier dossier = new InstrumentDossier();
		dossier.setIsin("DE0000000001");
		dossier.setCreatedBy("tester");
		dossier.setOrigin(DossierOrigin.USER);
		dossier.setStatus(DossierStatus.DRAFT);
		dossier.setAuthoredBy(DossierAuthoredBy.USER);
		dossier.setVersion(1);
		dossier.setContentMd("Sample dossier");
		dossier.setCitationsJson(objectMapper.createArrayNode());
		dossier.setContentHash("hash");
		dossier.setCreatedAt(LocalDateTime.now());
		dossier.setUpdatedAt(LocalDateTime.now());
		dossier.setAutoApproved(false);

		InstrumentDossier saved = dossierRepository.save(dossier);

		InstrumentDossier loaded = dossierRepository.findById(saved.getDossierId()).orElseThrow();
		assertThat(loaded.getIsin()).isEqualTo("DE0000000001");
		assertThat(loaded.getOrigin()).isEqualTo(DossierOrigin.USER);
		assertThat(loaded.getStatus()).isEqualTo(DossierStatus.DRAFT);
		assertThat(loaded.getContentMd()).contains("Sample");
	}
}
