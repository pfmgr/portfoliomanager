package my.portfoliomanager.app.api;

import my.portfoliomanager.app.domain.DossierAuthoredBy;
import my.portfoliomanager.app.domain.DossierExtractionStatus;
import my.portfoliomanager.app.domain.DossierOrigin;
import my.portfoliomanager.app.domain.DossierStatus;
import my.portfoliomanager.app.domain.InstrumentDossier;
import my.portfoliomanager.app.domain.InstrumentDossierExtraction;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import my.portfoliomanager.app.repository.InstrumentDossierExtractionRepository;
import my.portfoliomanager.app.repository.InstrumentDossierRepository;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
class SavingPlanApiIntegrationTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	private MockMvc mockMvc;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private InstrumentDossierRepository dossierRepository;

	@Autowired
	private InstrumentDossierExtractionRepository extractionRepository;

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
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();

		jdbcTemplate.update("update depots set active_snapshot_id = null");
		jdbcTemplate.update("delete from snapshot_positions");
		jdbcTemplate.update("delete from snapshots");
		jdbcTemplate.update("delete from instrument_blacklists");
		jdbcTemplate.update("delete from instrument_dossier_extractions");
		jdbcTemplate.update("delete from instrument_dossiers");
		jdbcTemplate.update("delete from instrument_facts");
		jdbcTemplate.update("delete from instrument_overrides");
		jdbcTemplate.update("delete from instrument_edits");
		jdbcTemplate.update("delete from sparplans_history");
		jdbcTemplate.update("delete from sparplans");
		jdbcTemplate.update("delete from instruments");
		jdbcTemplate.update("delete from depots");

		jdbcTemplate.update("insert into depots (depot_id, depot_code, name, provider) values (1, 'tr', 'Trade Republic', 'TR')");
		jdbcTemplate.update("insert into depots (depot_id, depot_code, name, provider) values (2, 'sc', 'Scalable Capital', 'SC')");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void createSavingPlanMaterializesInstrumentFromKnowledgeBase() throws Exception {
		createApprovedExtraction("LU0000000001", "KB Global ETF", 2);

		mockMvc.perform(post("/api/sparplans")
						.with(adminJwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "depotId": 1,
								  "isin": "LU0000000001",
								  "amountEur": 25.00
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isin").value("LU0000000001"))
				.andExpect(jsonPath("$.name").value("KB Global ETF"))
				.andExpect(jsonPath("$.layer").value(2));

		Integer deleted = jdbcTemplate.queryForObject(
				"select case when is_deleted then 1 else 0 end from instruments where isin = ?",
				Integer.class,
				"LU0000000001"
		);
		assertThat(deleted).isZero();
	}

	@Test
	void createSavingPlanReactivatesDeletedInstrument() throws Exception {
		createApprovedExtraction("LU0000000002", "Reactivated ETF", 4);
		jdbcTemplate.update(
				"insert into instruments (isin, name, depot_code, layer, is_deleted) values ('LU0000000002', 'Old Name', 'tr', 5, true)"
		);

		mockMvc.perform(post("/api/sparplans")
						.with(adminJwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "depotId": 1,
								  "isin": "LU0000000002",
								  "amountEur": 35.00
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.layer").value(4));

		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from instruments where isin = 'LU0000000002'",
				Integer.class
		)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"select is_deleted from instruments where isin = 'LU0000000002'",
				Boolean.class
		)).isFalse();
		assertThat(jdbcTemplate.queryForObject(
				"select layer from instruments_effective where isin = 'LU0000000002'",
				Integer.class
		)).isEqualTo(4);
	}

	@Test
	void applyApprovalsUsesProposalLayerForNewAssessorSavingPlans() throws Exception {
		createApprovedExtraction("LU0000000003", "Assessor Growth ETF", 2);

		mockMvc.perform(post("/api/sparplans/apply-approvals")
						.with(adminJwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "source": "assessor",
								  "items": [
								    {
								      "depotId": 1,
								      "isin": "LU0000000003",
								      "instrumentName": "Assessor Growth ETF",
								      "layer": 3,
								      "targetAmountEur": 40.00
								    }
								  ]
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(1))
				.andExpect(jsonPath("$.instrumentsCreated").value(1));

		assertThat(jdbcTemplate.queryForObject(
				"select layer from instruments_effective where isin = 'LU0000000003'",
				Integer.class
		)).isEqualTo(3);
		assertThat(jdbcTemplate.queryForObject(
				"select amount_eur from sparplans where isin = 'LU0000000003' and depot_id = 1",
				BigDecimal.class
		)).isEqualByComparingTo("40.00");
	}

	@Test
	void applyApprovalsReactivatesDeletedInstrumentAndUsesProposalLayer() throws Exception {
		createApprovedExtraction("LU0000000004", "Rebalancer Value ETF", 1);
		jdbcTemplate.update(
				"insert into instruments (isin, name, depot_code, layer, is_deleted) values ('LU0000000004', 'Dormant ETF', 'sc', 5, true)"
		);

		mockMvc.perform(post("/api/sparplans/apply-approvals")
						.with(adminJwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "source": "rebalancer",
								  "items": [
								    {
								      "depotId": 2,
								      "isin": "LU0000000004",
								      "instrumentName": "Rebalancer Value ETF",
								      "layer": 4,
								      "targetAmountEur": 55.00
								    }
								  ]
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(1))
				.andExpect(jsonPath("$.instrumentsReactivated").value(1));

		assertThat(jdbcTemplate.queryForObject(
				"select is_deleted from instruments where isin = 'LU0000000004'",
				Boolean.class
		)).isFalse();
		assertThat(jdbcTemplate.queryForObject(
				"select layer from instruments_effective where isin = 'LU0000000004'",
				Integer.class
		)).isEqualTo(4);
	}

	@Test
	void applyApprovalsBlocksAmbiguousRebalancerIsinAcrossDepots() throws Exception {
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, is_deleted) values ('LU0000000005', 'Shared ETF', 'tr', 2, false)");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (11, 1, 'LU0000000005', 20.00, 'monthly', true)");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (12, 2, 'LU0000000005', 30.00, 'monthly', true)");

		mockMvc.perform(post("/api/sparplans/apply-approvals")
						.with(adminJwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "source": "rebalancer",
								  "items": [
								    {
								      "isin": "LU0000000005",
								      "instrumentName": "Shared ETF",
								      "layer": 2,
								      "targetAmountEur": 45.00
								    }
								  ]
								}
								"""))
				.andExpect(status().isBadRequest());
	}

	private RequestPostProcessor adminJwt() {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private void createApprovedExtraction(String isin, String name, Integer layer) throws Exception {
		InstrumentDossier dossier = new InstrumentDossier();
		dossier.setIsin(isin);
		dossier.setCreatedBy("tester");
		dossier.setOrigin(DossierOrigin.USER);
		dossier.setStatus(DossierStatus.DRAFT);
		dossier.setAuthoredBy(DossierAuthoredBy.USER);
		dossier.setVersion(1);
		dossier.setContentMd("Name: " + name + "\nLayer: " + layer);
		dossier.setCitationsJson(objectMapper.createArrayNode());
		dossier.setContentHash("hash-" + isin);
		dossier.setCreatedAt(LocalDateTime.now());
		dossier.setUpdatedAt(LocalDateTime.now());
		dossier.setAutoApproved(false);
		InstrumentDossier savedDossier = dossierRepository.save(dossier);

		InstrumentDossierExtractionPayload payload = new InstrumentDossierExtractionPayload(
				isin,
				name,
				"ETF",
				"Equity",
				"Global",
				null,
				null,
				null,
				null,
				layer,
				"KB seeded",
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null
		);
		InstrumentDossierExtraction extraction = new InstrumentDossierExtraction();
		extraction.setDossierId(savedDossier.getDossierId());
		extraction.setModel("stub");
		extraction.setExtractedJson(objectMapper.valueToTree(payload));
		extraction.setMissingFieldsJson(objectMapper.createArrayNode());
		extraction.setWarningsJson(objectMapper.createArrayNode());
		extraction.setStatus(DossierExtractionStatus.APPROVED);
		extraction.setCreatedAt(LocalDateTime.now());
		extraction.setApprovedBy("tester");
		extraction.setApprovedAt(LocalDateTime.now());
		extractionRepository.save(extraction);
	}
}
