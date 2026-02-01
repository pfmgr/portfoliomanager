package my.portfoliomanager.app.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.domain.DossierExtractionStatus;
import my.portfoliomanager.app.domain.DossierOrigin;
import my.portfoliomanager.app.domain.DossierStatus;
import my.portfoliomanager.app.domain.DossierAuthoredBy;
import my.portfoliomanager.app.domain.InstrumentDossier;
import my.portfoliomanager.app.domain.InstrumentDossierExtraction;
import my.portfoliomanager.app.domain.InstrumentEdit;
import my.portfoliomanager.app.domain.InstrumentOverride;
import my.portfoliomanager.app.dto.InstrumentDossierCreateRequest;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import my.portfoliomanager.app.llm.LlmClient;
import my.portfoliomanager.app.llm.LlmSuggestion;
import my.portfoliomanager.app.repository.InstrumentDossierExtractionRepository;
import my.portfoliomanager.app.repository.InstrumentDossierRepository;
import my.portfoliomanager.app.repository.InstrumentEditRepository;
import my.portfoliomanager.app.repository.InstrumentOverrideRepository;
import my.portfoliomanager.app.dto.KnowledgeBaseConfigDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import my.portfoliomanager.app.support.TestDatabaseCleaner;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
@Import(KnowledgeBaseServiceTest.TestConfig.class)
class KnowledgeBaseServiceTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private KnowledgeBaseService knowledgeBaseService;

	@Autowired
	private InstrumentDossierRepository dossierRepository;

	@Autowired
	private InstrumentDossierExtractionRepository extractionRepository;

	@Autowired
	private InstrumentOverrideRepository overrideRepository;

	@Autowired
	private InstrumentEditRepository editRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@Autowired
	private KnowledgeBaseConfigService configService;

	private KnowledgeBaseConfigDto baselineConfig;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
		registry.add("app.kb.enabled", () -> "true");
		registry.add("app.kb.llm-enabled", () -> "false");
	}

	@BeforeEach
	void setup() {
		jdbcTemplate.update("update depots set active_snapshot_id = null");
		jdbcTemplate.update("delete from snapshot_positions");
		jdbcTemplate.update("delete from snapshots");
		jdbcTemplate.update("delete from instrument_dossier_extractions");
		jdbcTemplate.update("delete from instrument_dossiers");
		jdbcTemplate.update("delete from instrument_facts");
		jdbcTemplate.update("delete from instrument_overrides");
		jdbcTemplate.update("delete from instrument_edits");
		jdbcTemplate.update("delete from sparplans_history");
		jdbcTemplate.update("delete from sparplans");
		jdbcTemplate.update("delete from instruments");
		jdbcTemplate.update("delete from depots");

		jdbcTemplate.update("insert into depots (depot_id, depot_code, name, provider) values (1, 'tr', 'Test Depot', 'TR')");
		jdbcTemplate.update("""
				insert into instruments (isin, name, depot_code, layer, is_deleted)
				values ('DE0000000001', 'Test Instrument', 'tr', 5, false)
				""");
		baselineConfig = configService.getConfig();
	}

	@AfterEach
	void tearDown() {
		if (baselineConfig != null) {
			configService.updateConfig(baselineConfig);
		}
		databaseCleaner.clean();
	}

	@Test
	void applyExtraction_fillsMissingFields() throws Exception {
		InstrumentOverride override = new InstrumentOverride();
		override.setIsin("DE0000000001");
		override.setAssetClass("Equity");
		override.setUpdatedAt(LocalDateTime.now());
		overrideRepository.save(override);

		InstrumentDossier dossier = createDossier("Name: New Name");
		InstrumentDossierExtraction extraction = createApprovedExtraction(dossier.getDossierId(), payload("DE0000000001", "New Name"));

		knowledgeBaseService.applyExtraction(extraction.getExtractionId(), "tester");

		InstrumentOverride updated = overrideRepository.findById("DE0000000001").orElseThrow();
		assertThat(updated.getName()).isEqualTo("New Name");
		assertThat(updated.getAssetClass()).isEqualTo("Equity");
	}

	@Test
	void applyExtraction_writesInstrumentEdits() throws Exception {
		InstrumentDossier dossier = createDossier("Name: Fresh Name\nAsset Class: Bonds");
		InstrumentDossierExtraction extraction = createApprovedExtraction(dossier.getDossierId(),
				payload("DE0000000001", "Fresh Name", "Bonds"));

		knowledgeBaseService.applyExtraction(extraction.getExtractionId(), "tester");

		List<InstrumentEdit> edits = editRepository.findAll();
		assertThat(edits).isNotEmpty();
		assertThat(edits).extracting(InstrumentEdit::getField)
				.contains("name", "asset_class");
	}

	@Test
	void extractionLifecycle_approve_then_apply() {
		InstrumentDossierCreateRequest request = new InstrumentDossierCreateRequest(
				"DE0000000001",
				null,
				"Name: Lifecycle Test\nLayer: 2",
				DossierOrigin.USER,
				DossierStatus.DRAFT,
				objectMapper.createArrayNode()
		);
		var dossier = knowledgeBaseService.createDossier(request, "tester");

		var extraction = knowledgeBaseService.runExtraction(dossier.dossierId());
		assertThat(extraction.status()).isEqualTo(DossierExtractionStatus.PENDING_REVIEW);

		var approved = knowledgeBaseService.approveExtraction(extraction.extractionId(), "tester");
		assertThat(approved.status()).isEqualTo(DossierExtractionStatus.APPROVED);

		var applied = knowledgeBaseService.applyExtraction(extraction.extractionId(), "tester");
		assertThat(applied.status()).isEqualTo(DossierExtractionStatus.APPLIED);
		assertThat(overrideRepository.findById("DE0000000001")).isPresent();
	}

	@Test
	void applyExtraction_doesNotOverwriteExistingValues_whenOverwriteDisabled() throws Exception {
		InstrumentOverride override = new InstrumentOverride();
		override.setIsin("DE0000000001");
		override.setAssetClass("Equity");
		override.setUpdatedAt(LocalDateTime.now());
		overrideRepository.save(override);

		InstrumentDossier dossier = createDossier("Name: New Name\nAsset Class: Bonds");
		InstrumentDossierExtraction extraction = createApprovedExtraction(dossier.getDossierId(),
				payload("DE0000000001", "New Name", "Bonds"));

		knowledgeBaseService.applyExtraction(extraction.getExtractionId(), "tester");

		InstrumentOverride updated = overrideRepository.findById("DE0000000001").orElseThrow();
		assertThat(updated.getAssetClass()).isEqualTo("Equity");
	}

	@Test
	void applyExtraction_overwritesExistingValues_whenOverwriteEnabled() throws Exception {
		InstrumentOverride override = new InstrumentOverride();
		override.setIsin("DE0000000001");
		override.setAssetClass("Equity");
		override.setUpdatedAt(LocalDateTime.now());
		overrideRepository.save(override);

		KnowledgeBaseConfigDto current = configService.getConfig();
		KnowledgeBaseConfigDto updated = new KnowledgeBaseConfigDto(
				current.enabled(),
				current.refreshIntervalDays(),
				current.autoApprove(),
				current.applyExtractionsToOverrides(),
				true,
				current.batchSizeInstruments(),
				current.batchMaxInputChars(),
				current.maxParallelBulkBatches(),
				current.maxBatchesPerRun(),
				current.pollIntervalSeconds(),
				current.maxInstrumentsPerRun(),
				current.maxRetriesPerInstrument(),
				current.baseBackoffSeconds(),
				current.maxBackoffSeconds(),
				current.dossierMaxChars(),
				current.kbRefreshMinDaysBetweenRunsPerInstrument(),
				current.runTimeoutMinutes(),
				current.websearchReasoningEffort(),
				current.websearchAllowedDomains(),
				current.bulkMinCitations(),
				current.bulkRequirePrimarySource(),
				current.alternativesMinSimilarityScore(),
				current.extractionEvidenceRequired()
		);
		configService.updateConfig(updated);

		InstrumentDossier dossier = createDossier("Name: New Name\nAsset Class: Bonds");
		InstrumentDossierExtraction extraction = createApprovedExtraction(dossier.getDossierId(),
				payload("DE0000000001", "New Name", "Bonds"));

		knowledgeBaseService.applyExtraction(extraction.getExtractionId(), "tester");

		InstrumentOverride changed = overrideRepository.findById("DE0000000001").orElseThrow();
		assertThat(changed.getAssetClass()).isEqualTo("Bonds");
	}

	private InstrumentDossier createDossier(String content) {
		InstrumentDossier dossier = new InstrumentDossier();
		dossier.setIsin("DE0000000001");
		dossier.setCreatedBy("tester");
		dossier.setOrigin(DossierOrigin.USER);
		dossier.setStatus(DossierStatus.DRAFT);
		dossier.setAuthoredBy(DossierAuthoredBy.USER);
		dossier.setVersion(1);
		dossier.setContentMd(content);
		dossier.setCitationsJson(objectMapper.createArrayNode());
		dossier.setContentHash("hash");
		dossier.setCreatedAt(LocalDateTime.now());
		dossier.setUpdatedAt(LocalDateTime.now());
		dossier.setAutoApproved(false);
		return dossierRepository.save(dossier);
	}

	private InstrumentDossierExtraction createApprovedExtraction(Long dossierId, InstrumentDossierExtractionPayload payload) throws Exception {
		JsonNode extracted = objectMapper.valueToTree(payload);
		InstrumentDossierExtraction extraction = new InstrumentDossierExtraction();
		extraction.setDossierId(dossierId);
		extraction.setModel("stub");
		extraction.setExtractedJson(extracted);
		extraction.setMissingFieldsJson(objectMapper.createArrayNode());
		extraction.setWarningsJson(objectMapper.createArrayNode());
		extraction.setStatus(DossierExtractionStatus.APPROVED);
		extraction.setCreatedAt(LocalDateTime.now());
		extraction.setApprovedBy("tester");
		extraction.setApprovedAt(LocalDateTime.now());
		return extractionRepository.save(extraction);
	}

	private InstrumentDossierExtractionPayload payload(String isin, String name) {
		return payload(isin, name, null);
	}

	private InstrumentDossierExtractionPayload payload(String isin, String name, String assetClass) {
		return new InstrumentDossierExtractionPayload(
				isin,
				name,
				null,
				assetClass,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				List.of(),
				List.of(),
				List.of()
		);
	}

	@Configuration
	static class TestConfig {
		@Bean
		LlmClient llmClient() {
			return new LlmClient() {
				@Override
				public LlmSuggestion suggestReclassification(String context) {
					return new LlmSuggestion("", "test");
				}

				@Override
				public LlmSuggestion suggestSavingPlanProposal(String context) {
					return new LlmSuggestion("", "test");
				}
			};
		}
	}
}
