package my.portfoliomanager.app.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import my.portfoliomanager.app.domain.DossierExtractionStatus;
import my.portfoliomanager.app.domain.DossierOrigin;
import my.portfoliomanager.app.domain.DossierStatus;
import my.portfoliomanager.app.domain.DossierAuthoredBy;
import my.portfoliomanager.app.domain.InstrumentDossier;
import my.portfoliomanager.app.domain.InstrumentDossierExtraction;
import my.portfoliomanager.app.domain.InstrumentBlacklistScope;
import my.portfoliomanager.app.domain.InstrumentEdit;
import my.portfoliomanager.app.domain.InstrumentOverride;
import my.portfoliomanager.app.dto.InstrumentDossierCreateRequest;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmClient;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmDossierDraft;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

	@Autowired
	private KnowledgeBaseQualityGateService qualityGateService;

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
		TestConfig.reset();
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
	void generateDossierDraftWithQualityRetries_retriesWithPrimaryDomainsOnly() {
		String isin = "IE00B4L5Y983";
		configService.updateConfig(new KnowledgeBaseConfigDto(
				baselineConfig.enabled(),
				baselineConfig.refreshIntervalDays(),
				baselineConfig.autoApprove(),
				baselineConfig.applyExtractionsToOverrides(),
				baselineConfig.overwriteExistingOverrides(),
				baselineConfig.batchSizeInstruments(),
				baselineConfig.batchMaxInputChars(),
				baselineConfig.maxParallelBulkBatches(),
				baselineConfig.maxBatchesPerRun(),
				baselineConfig.pollIntervalSeconds(),
				baselineConfig.maxInstrumentsPerRun(),
				baselineConfig.maxRetriesPerInstrument(),
				baselineConfig.baseBackoffSeconds(),
				baselineConfig.maxBackoffSeconds(),
				baselineConfig.dossierMaxChars(),
				baselineConfig.kbRefreshMinDaysBetweenRunsPerInstrument(),
				baselineConfig.runTimeoutMinutes(),
				baselineConfig.websearchReasoningEffort(),
				List.of("generic.example", "markets.example"),
				2,
				true,
				baselineConfig.alternativesMinSimilarityScore(),
				baselineConfig.extractionEvidenceRequired(),
				1,
				baselineConfig.qualityGateProfiles()
		));

		KnowledgeBaseService.DossierDraftResult result = knowledgeBaseService.generateDossierDraftWithQualityRetries(
				isin,
				null,
				configService.getSnapshot(),
				true
		);

		assertThat(result.quality()).isNotNull();
		assertThat(result.quality().passed()).isTrue();
		assertThat(result.warnings()).anyMatch(message -> message.contains("primary-source domains"));
		assertThat(TestConfig.generateAllowedDomainsByIsin.get(isin))
				.containsExactly(
						List.of("generic.example", "markets.example"),
						List.of("retry.example")
				);
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
	void approveExtractionAutoAppliesForDeletedBaseInstrumentWhenConfigured() throws Exception {
		setApplyExtractionsToOverrides(true);
		jdbcTemplate.update("update instruments set is_deleted = true where isin = 'DE0000000001'");

		InstrumentDossier dossier = createDossier("Name: Reactivated by KB\nAsset Class: Equity");
		InstrumentDossierExtraction extraction = createPendingExtraction(
				dossier.getDossierId(),
				objectMapper.valueToTree(payload("DE0000000001", "Reactivated by KB", "Equity"))
		);

		var approved = knowledgeBaseService.approveExtraction(extraction.getExtractionId(), "tester", false, true);

		assertThat(approved.status()).isEqualTo(DossierExtractionStatus.APPLIED);
		assertThat(overrideRepository.findById("DE0000000001")).isPresent();
		assertThat(overrideRepository.findById("DE0000000001").orElseThrow().getName()).isEqualTo("Reactivated by KB");
	}

	@Test
	void approveDossierActivatesPendingBlacklist() {
		InstrumentDossierCreateRequest request = new InstrumentDossierCreateRequest(
				"DE0000000001",
				null,
				"Name: Blacklist Draft\nLayer: 2",
				DossierOrigin.USER,
				DossierStatus.PENDING_REVIEW,
				objectMapper.createArrayNode(),
				InstrumentBlacklistScope.ALL_PROPOSALS
		);
		var dossier = knowledgeBaseService.createDossier(request, "tester");

		var pending = knowledgeBaseService.getDossierDetail("DE0000000001");
		assertThat(pending.blacklist().requestedScope()).isEqualTo(InstrumentBlacklistScope.ALL_PROPOSALS);
		assertThat(pending.blacklist().effectiveScope()).isEqualTo(InstrumentBlacklistScope.NONE);
		assertThat(pending.blacklist().pendingChange()).isTrue();

		knowledgeBaseService.approveDossier(dossier.dossierId(), "tester");

		var approved = knowledgeBaseService.getDossierDetail("DE0000000001");
		assertThat(approved.blacklist().requestedScope()).isEqualTo(InstrumentBlacklistScope.ALL_PROPOSALS);
		assertThat(approved.blacklist().effectiveScope()).isEqualTo(InstrumentBlacklistScope.ALL_PROPOSALS);
		assertThat(approved.blacklist().pendingChange()).isFalse();
	}

	@Test
	void autoApproveDossierAlsoActivatesBlacklist() {
		configService.updateConfig(new KnowledgeBaseConfigDto(
				baselineConfig.enabled(),
				baselineConfig.refreshIntervalDays(),
				baselineConfig.autoApprove(),
				baselineConfig.applyExtractionsToOverrides(),
				baselineConfig.overwriteExistingOverrides(),
				baselineConfig.batchSizeInstruments(),
				baselineConfig.batchMaxInputChars(),
				baselineConfig.maxParallelBulkBatches(),
				baselineConfig.maxBatchesPerRun(),
				baselineConfig.pollIntervalSeconds(),
				baselineConfig.maxInstrumentsPerRun(),
				baselineConfig.maxRetriesPerInstrument(),
				baselineConfig.baseBackoffSeconds(),
				baselineConfig.maxBackoffSeconds(),
				baselineConfig.dossierMaxChars(),
				baselineConfig.kbRefreshMinDaysBetweenRunsPerInstrument(),
				baselineConfig.runTimeoutMinutes(),
				baselineConfig.websearchReasoningEffort(),
				baselineConfig.websearchAllowedDomains(),
				1,
				false,
				baselineConfig.alternativesMinSimilarityScore(),
				baselineConfig.extractionEvidenceRequired(),
				baselineConfig.qualityGateRetryLimit(),
				baselineConfig.qualityGateProfiles()
		));
		var citations = objectMapper.createArrayNode();
		citations.addObject()
				.put("url", "https://issuer.com/factsheet")
				.put("title", "Issuer factsheet");
		InstrumentDossierCreateRequest request = new InstrumentDossierCreateRequest(
				"DE0000000001",
				null,
				"# DE0000000001 - Auto Approved Blacklist\n\n"
						+ "## Quick profile\n- Name: Auto Approved Blacklist\n\n"
						+ "## Classification\n- Layer: 2\n\n"
						+ "## Risk\n- SRI: 3\n\n"
						+ "## Costs & structure\n- TER: 0.20\n\n"
						+ "## Exposures\n- Benchmark: MSCI World\n\n"
						+ "## Valuation & profitability\n- pe_current: 15\n\n"
						+ "## Sources\n1) https://issuer.com/factsheet\n",
				DossierOrigin.USER,
				DossierStatus.PENDING_REVIEW,
				citations,
				InstrumentBlacklistScope.SAVING_PLAN_ONLY
		);
		var dossier = knowledgeBaseService.createDossier(request, "tester");

		knowledgeBaseService.approveDossier(dossier.dossierId(), "tester", true);

		var approved = knowledgeBaseService.getDossierDetail("DE0000000001");
		assertThat(approved.latestDossier().autoApproved()).isTrue();
		assertThat(approved.blacklist().effectiveScope()).isEqualTo(InstrumentBlacklistScope.SAVING_PLAN_ONLY);
		assertThat(approved.blacklist().pendingChange()).isFalse();
	}

	@Test
	void rejectDossierClearsPendingBlacklistChange() {
		var approved = knowledgeBaseService.createDossier(new InstrumentDossierCreateRequest(
				"DE0000000001",
				null,
				"Name: Approved Base\nLayer: 2",
				DossierOrigin.USER,
				DossierStatus.APPROVED,
				objectMapper.createArrayNode(),
				InstrumentBlacklistScope.NONE
		), "tester");
		assertThat(approved.dossierId()).isNotNull();

		var draft = knowledgeBaseService.createDossier(new InstrumentDossierCreateRequest(
				"DE0000000001",
				null,
				"Name: Pending Blacklist\nLayer: 2",
				DossierOrigin.USER,
				DossierStatus.PENDING_REVIEW,
				objectMapper.createArrayNode(),
				InstrumentBlacklistScope.ALL_PROPOSALS
		), "tester");

		var pending = knowledgeBaseService.getDossierDetail("DE0000000001");
		assertThat(pending.blacklist().pendingChange()).isTrue();

		knowledgeBaseService.rejectDossier(draft.dossierId(), "tester");

		var rejected = knowledgeBaseService.getDossierDetail("DE0000000001");
		assertThat(rejected.blacklist().requestedScope()).isEqualTo(InstrumentBlacklistScope.NONE);
		assertThat(rejected.blacklist().effectiveScope()).isEqualTo(InstrumentBlacklistScope.NONE);
		assertThat(rejected.blacklist().pendingChange()).isFalse();
	}

	@Test
	void updatingApprovedDossierBlacklistRequiresFreshApproval() {
		var approved = knowledgeBaseService.createDossier(new InstrumentDossierCreateRequest(
				"DE0000000001",
				null,
				"# DE0000000001\n\n## Quick profile\n- Name: Approved Base\n\n## Classification\n- Layer: 2\n\n## Risk\n- SRI: 3\n\n## Costs & structure\n- TER: 0.20\n\n## Exposures\n- Benchmark: MSCI World\n\n## Valuation & profitability\n- pe_current: 15\n\n## Sources\n1) https://issuer.com/factsheet",
				DossierOrigin.USER,
				DossierStatus.APPROVED,
				objectMapper.createArrayNode(),
				InstrumentBlacklistScope.NONE
		), "tester");

		var updated = knowledgeBaseService.updateDossier(
				approved.dossierId(),
				new my.portfoliomanager.app.dto.InstrumentDossierUpdateRequest(
						null,
						"# DE0000000001\n\n## Quick profile\n- Name: Pending Review\n\n## Classification\n- Layer: 2\n\n## Risk\n- SRI: 3\n\n## Costs & structure\n- TER: 0.20\n\n## Exposures\n- Benchmark: MSCI World\n\n## Valuation & profitability\n- pe_current: 15\n\n## Sources\n1) https://issuer.com/factsheet",
						DossierStatus.APPROVED,
						objectMapper.createArrayNode(),
						InstrumentBlacklistScope.ALL_PROPOSALS
				),
				"tester"
		);

		assertThat(updated.status()).isEqualTo(DossierStatus.PENDING_REVIEW);
		var detail = knowledgeBaseService.getDossierDetail("DE0000000001");
		assertThat(detail.blacklist().effectiveScope()).isEqualTo(InstrumentBlacklistScope.NONE);
		assertThat(detail.blacklist().requestedScope()).isEqualTo(InstrumentBlacklistScope.ALL_PROPOSALS);
		assertThat(detail.blacklist().pendingChange()).isTrue();
	}

	@Test
	void dossierResponsesSurfaceLatestExtractionWarnings() throws Exception {
		InstrumentDossier dossier = createDossier("Name: Warning Visibility");
		InstrumentDossierExtraction extraction = createPendingExtraction(
				dossier.getDossierId(),
				objectMapper.readTree("""
						{
						  "isin": "DE0000000001",
						  "warnings": []
						}
						""")
		);
		extraction.setWarningsJson(objectMapper.readTree("""
				[
				  {"message":"LLM output ISIN (US0000000001) does not match dossier ISIN (DE0000000001); dossier ISIN wins."},
				  {"message":"Retry plan restricted to cited primary-source domains: issuer.example"}
				]
				"""));
		extractionRepository.save(extraction);

		var dossierResponse = knowledgeBaseService.getDossier(dossier.getDossierId());
		assertThat(dossierResponse.warnings())
				.containsExactly(
						"LLM output ISIN (US0000000001) does not match dossier ISIN (DE0000000001); dossier ISIN wins.",
						"Retry plan restricted to cited primary-source domains: issuer.example"
				);

		var detail = knowledgeBaseService.getDossierDetail("DE0000000001");
		assertThat(detail.latestDossier().warnings()).containsExactlyElementsOf(dossierResponse.warnings());
	}

	@Test
	void deleteDossiersRemovesBlacklistEntries() {
		knowledgeBaseService.createDossier(new InstrumentDossierCreateRequest(
				"DE0000000001",
				null,
				"# DE0000000001\n\n## Quick profile\n- Name: Delete Me\n\n## Classification\n- Layer: 2\n\n## Risk\n- SRI: 3\n\n## Costs & structure\n- TER: 0.20\n\n## Exposures\n- Benchmark: MSCI World\n\n## Valuation & profitability\n- pe_current: 15\n\n## Sources\n1) https://issuer.com/factsheet",
				DossierOrigin.USER,
				DossierStatus.APPROVED,
				objectMapper.createArrayNode(),
				InstrumentBlacklistScope.ALL_PROPOSALS
		), "tester");

		assertThat(jdbcTemplate.queryForObject("select count(*) from instrument_blacklists where isin = 'DE0000000001'", Integer.class))
				.isEqualTo(1);
		knowledgeBaseService.deleteDossiers(List.of("DE0000000001"));
		assertThat(jdbcTemplate.queryForObject("select count(*) from instrument_blacklists where isin = 'DE0000000001'", Integer.class))
				.isEqualTo(0);
	}

	@Test
	void createDossier_stripsMarkersAndAddsIsinHeader() {
		String content = "---BEGIN DOSSIER MARKDOWN---\n"
				+ "DE0000000001 - Sample Dossier\n"
				+ "\n## Quick profile (table)\n"
				+ "\n---END DOSSIER MARKDOWN---";
		InstrumentDossierCreateRequest request = new InstrumentDossierCreateRequest(
				"DE0000000001",
				null,
				content,
				DossierOrigin.USER,
				DossierStatus.DRAFT,
				objectMapper.createArrayNode()
		);
		var dossier = knowledgeBaseService.createDossier(request, "tester");
		InstrumentDossier saved = dossierRepository.findById(dossier.dossierId()).orElseThrow();
		assertThat(saved.getContentMd()).doesNotContain("BEGIN DOSSIER MARKDOWN");
		assertThat(saved.getContentMd()).startsWith("# DE0000000001 - Sample Dossier");
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
				current.extractionEvidenceRequired(),
				current.qualityGateRetryLimit(),
				current.qualityGateProfiles()
		);
		configService.updateConfig(updated);

		InstrumentDossier dossier = createDossier("Name: New Name\nAsset Class: Bonds");
		InstrumentDossierExtraction extraction = createApprovedExtraction(dossier.getDossierId(),
				payload("DE0000000001", "New Name", "Bonds"));

		knowledgeBaseService.applyExtraction(extraction.getExtractionId(), "tester");

		InstrumentOverride changed = overrideRepository.findById("DE0000000001").orElseThrow();
		assertThat(changed.getAssetClass()).isEqualTo("Bonds");
	}

	@Test
	void approveExtraction_withLargeValuationFacts_appliesWithoutNumericOverflow() throws Exception {
		setApplyExtractionsToOverrides(true);

		InstrumentDossier dossier = createDossier("Name: NVIDIA Corporation");
		InstrumentDossierExtraction extraction = createPendingExtraction(
				dossier.getDossierId(),
				largeValuationPayload("DE0000000001")
		);

		var approved = knowledgeBaseService.approveExtraction(extraction.getExtractionId(), "tester");

		assertThat(approved.status()).isEqualTo(DossierExtractionStatus.APPLIED);
		assertThat(readFactValueNum("DE0000000001", "valuation.market_cap"))
				.isEqualByComparingTo("4430000000000");
		assertThat(readFactValueNum("DE0000000001", "valuation.enterprise_value"))
				.isEqualByComparingTo("4480000000000");
	}

	@Test
	void approveExtraction_autoApproveWithLargeValuationFacts_appliesWithoutNumericOverflow() throws Exception {
		InstrumentDossier dossier = createDossier("Name: NVIDIA Corporation");
		InstrumentDossierExtraction extraction = createPendingExtraction(
				dossier.getDossierId(),
				largeValuationPayload("DE0000000001")
		);

		var approved = knowledgeBaseService.approveExtraction(extraction.getExtractionId(), "tester", true, true);

		assertThat(approved.status()).isEqualTo(DossierExtractionStatus.APPLIED);
		assertThat(extractionRepository.findById(extraction.getExtractionId()).orElseThrow().isAutoApproved()).isTrue();
		assertThat(readFactValueNum("DE0000000001", "valuation.market_cap"))
				.isEqualByComparingTo("4430000000000");
	}

	@Test
	void applyExtraction_withLargeValuationFacts_persistsWithoutNumericOverflow() throws Exception {
		InstrumentDossier dossier = createDossier("Name: NVIDIA Corporation");
		InstrumentDossierExtraction extraction = createPendingExtraction(
				dossier.getDossierId(),
				largeValuationPayload("DE0000000001")
		);
		extraction.setStatus(DossierExtractionStatus.APPROVED);
		extraction.setApprovedBy("tester");
		extraction.setApprovedAt(LocalDateTime.now());
		extraction = extractionRepository.save(extraction);

		var applied = knowledgeBaseService.applyExtraction(extraction.getExtractionId(), "tester");

		assertThat(applied.status()).isEqualTo(DossierExtractionStatus.APPLIED);
		assertThat(readFactValueNum("DE0000000001", "valuation.market_cap"))
				.isEqualByComparingTo("4430000000000");
		assertThat(readFactValueNum("DE0000000001", "valuation.enterprise_value"))
				.isEqualByComparingTo("4480000000000");
	}

	@Test
	void runExtraction_keepsPriceStableWhenContextContainsBWord() throws Exception {
		InstrumentDossier dossier = createDossier(
				"# DE0000000001 - Test\n"
						+ "instrument_type: ETF\n"
						+ "layer: 2\n"
						+ "price: 227.50 EUR (as of 2026-02-13, Bourse Hamburg listing)"
		);

		var extraction = knowledgeBaseService.runExtraction(dossier.getDossierId());

		assertThat(extraction.status()).isEqualTo(DossierExtractionStatus.PENDING_REVIEW);
		InstrumentDossierExtractionPayload payload = objectMapper.treeToValue(
				extraction.extractedJson(),
				InstrumentDossierExtractionPayload.class
		);
		assertThat(payload.valuation()).isNotNull();
		assertThat(payload.valuation().price()).isEqualByComparingTo("227.50");

		KnowledgeBaseQualityGateService.EvidenceResult evidence = qualityGateService.evaluateExtractionEvidence(
				dossier.getContentMd(),
				payload,
				configService.getSnapshot()
		);
		assertThat(evidence.missingEvidence()).doesNotContain("price");
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

	private InstrumentDossierExtraction createPendingExtraction(Long dossierId, JsonNode extractedJson) {
		InstrumentDossierExtraction extraction = new InstrumentDossierExtraction();
		extraction.setDossierId(dossierId);
		extraction.setModel("stub");
		extraction.setExtractedJson(extractedJson);
		extraction.setMissingFieldsJson(objectMapper.createArrayNode());
		extraction.setWarningsJson(objectMapper.createArrayNode());
		extraction.setStatus(DossierExtractionStatus.PENDING_REVIEW);
		extraction.setCreatedAt(LocalDateTime.now());
		return extractionRepository.save(extraction);
	}

	private JsonNode largeValuationPayload(String isin) throws Exception {
		return objectMapper.readTree("""
				{
				  "isin": "%s",
				  "name": "NVIDIA Corporation",
				  "instrument_type": "Single Stock",
				  "asset_class": "Equity",
				  "valuation": {
				    "market_cap": 4430000000000,
				    "enterprise_value": 4480000000000
				  },
				  "sources": [],
				  "missing_fields": [],
				  "warnings": []
				}
				""".formatted(isin));
	}

	private BigDecimal readFactValueNum(String isin, String factKey) {
		return jdbcTemplate.queryForObject(
				"""
						select fact_value_num
						from instrument_facts
						where isin = ?
						  and fact_key = ?
						order by updated_at desc
						limit 1
						""",
				BigDecimal.class,
				isin,
				factKey
		);
	}

	private void setApplyExtractionsToOverrides(boolean enabled) {
		KnowledgeBaseConfigDto current = configService.getConfig();
		KnowledgeBaseConfigDto updated = new KnowledgeBaseConfigDto(
				current.enabled(),
				current.refreshIntervalDays(),
				current.autoApprove(),
				enabled,
				current.overwriteExistingOverrides(),
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
				current.extractionEvidenceRequired(),
				current.qualityGateRetryLimit(),
				current.qualityGateProfiles()
		);
		configService.updateConfig(updated);
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
				null,
				null,
				List.of(),
				null,
				List.of(),
				null,
				List.of(),
				List.of()
		);
	}

	@Configuration
	static class TestConfig {
		private static final Map<String, List<List<String>>> generateAllowedDomainsByIsin = new ConcurrentHashMap<>();

		static void reset() {
			generateAllowedDomainsByIsin.clear();
		}

		@Bean
		@org.springframework.context.annotation.Primary
		KnowledgeBaseLlmClient knowledgeBaseLlmClient(ObjectMapper objectMapper) {
			return new KnowledgeBaseLlmClient() {
				@Override
				public KnowledgeBaseLlmDossierDraft generateDossier(String isin,
										 String context,
										 List<String> allowedDomains,
										 int maxChars) {
					generateAllowedDomainsByIsin
							.computeIfAbsent(isin, key -> new ArrayList<>())
							.add(List.copyOf(allowedDomains));
					if ("IE00B4L5Y983".equalsIgnoreCase(isin)) {
						int attempt = generateAllowedDomainsByIsin.get(isin).size();
						ArrayNode citations = objectMapper.createArrayNode();
						citations.add(objectMapper.createObjectNode()
								.put("id", "1")
								.put("title", "Issuer factsheet")
								.put("url", "https://retry.example/factsheet")
								.put("publisher", "Issuer")
								.put("accessed_at", "2026-04-10"));
						if (attempt > 1) {
							citations.add(objectMapper.createObjectNode()
									.put("id", "2")
									.put("title", "Issuer KID")
									.put("url", "https://retry.example/kid")
									.put("publisher", "Issuer")
									.put("accessed_at", "2026-04-10"));
						}
						String content = "# " + isin + " — Retry ETF\n\n"
								+ "## Quick profile\n- ISIN: " + isin + "\n\n"
								+ "## Classification\n- Instrument type: ETF\n- Asset class: Equity\n- Layer: 2\n\n"
								+ "## Risk\n- SRI: 4\n\n"
								+ "## Costs & structure\n- TER: 0.20\n\n"
								+ "## Exposures\n- Benchmark: MSCI World\n\n"
								+ "## Valuation & profitability\n- price: 100\n\n"
								+ "## Sources\n1. https://retry.example/factsheet\n";
						return new KnowledgeBaseLlmDossierDraft(content, "Retry ETF", citations, "test-model");
					}
					ArrayNode citations = objectMapper.createArrayNode();
					citations.add(objectMapper.createObjectNode()
							.put("id", "1")
							.put("title", "Test")
							.put("url", "https://example.com")
							.put("publisher", "Example")
							.put("accessed_at", "2024-01-01"));
					return new KnowledgeBaseLlmDossierDraft("# " + isin + "\n\n## Quick profile\n- ISIN: " + isin,
							"Test",
							citations,
							"test-model");
				}

				@Override
				public KnowledgeBaseLlmDossierDraft patchDossierMissingFields(String isin,
														 String contentMd,
														 JsonNode existingCitations,
														 List<String> missingFields,
														 String context,
														 List<String> allowedDomains,
														 int maxChars) {
					JsonNode citations = existingCitations == null ? objectMapper.createArrayNode() : existingCitations;
					return new KnowledgeBaseLlmDossierDraft(contentMd, "Test Instrument", citations, "test-model");
				}

				@Override
				public my.portfoliomanager.app.llm.KnowledgeBaseLlmExtractionDraft extractMetadata(String dossierText) {
					throw new UnsupportedOperationException();
				}

				@Override
				public my.portfoliomanager.app.llm.KnowledgeBaseLlmAlternativesDraft findAlternatives(String isin,
																		 List<String> allowedDomains) {
					return new my.portfoliomanager.app.llm.KnowledgeBaseLlmAlternativesDraft(List.of());
				}
			};
		}

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
