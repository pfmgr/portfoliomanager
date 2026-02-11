package my.portfoliomanager.app.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import my.portfoliomanager.app.domain.DossierExtractionStatus;
import my.portfoliomanager.app.domain.DossierStatus;
import my.portfoliomanager.app.dto.KnowledgeBaseBulkResearchResponseDto;
import my.portfoliomanager.app.dto.KnowledgeBaseConfigDto;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmAlternativesDraft;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmClient;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmDossierDraft;
import my.portfoliomanager.app.repository.InstrumentDossierExtractionRepository;
import my.portfoliomanager.app.repository.InstrumentDossierRepository;
import my.portfoliomanager.app.repository.InstrumentEditRepository;
import my.portfoliomanager.app.repository.InstrumentOverrideRepository;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
@Import(KnowledgeBaseMaintenanceServiceTest.TestConfig.class)
class KnowledgeBaseMaintenanceServiceTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private KnowledgeBaseMaintenanceService maintenanceService;

	@Autowired
	private KnowledgeBaseConfigService configService;

	@Autowired
	private InstrumentDossierRepository dossierRepository;

	@Autowired
	private InstrumentDossierExtractionRepository extractionRepository;

	@Autowired
	private InstrumentOverrideRepository overrideRepository;

	@Autowired
	private InstrumentEditRepository editRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

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
		jdbcTemplate.update("delete from instrument_dossier_extractions");
		jdbcTemplate.update("delete from instrument_dossiers");
		jdbcTemplate.update("delete from instrument_overrides");
		jdbcTemplate.update("delete from instrument_edits");
		jdbcTemplate.update("delete from instruments");
		jdbcTemplate.update("delete from depots");
		jdbcTemplate.update("insert into depots (depot_id, depot_code, name, provider) values (1, 'tr', 'Test Depot', 'TR')");
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, is_deleted) values ('DE0000000001', 'Test Instrument', 'tr', 5, false)");
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
	void bulkResearch_autoApprove_appliesOverridesAndAudits() {
		KnowledgeBaseConfigDto current = configService.getConfig();
		KnowledgeBaseConfigDto updated = new KnowledgeBaseConfigDto(
				current.enabled(),
				current.refreshIntervalDays(),
				current.autoApprove(),
				true,
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

		KnowledgeBaseBulkResearchResponseDto result = maintenanceService.bulkResearch(
				List.of("DE0000000001"),
				true,
				true,
				"tester"
		);

		assertThat(result.succeeded()).isEqualTo(1);
		var dossier = dossierRepository.findFirstByIsinOrderByVersionDesc("DE0000000001").orElseThrow();
		assertThat(dossier.getStatus()).isEqualTo(DossierStatus.APPROVED);
		assertThat(dossier.isAutoApproved()).isTrue();

		var extraction = extractionRepository.findByDossierIdOrderByCreatedAtDesc(dossier.getDossierId()).getFirst();
		assertThat(extraction.getStatus()).isEqualTo(DossierExtractionStatus.APPLIED);
		assertThat(extraction.isAutoApproved()).isTrue();

		assertThat(overrideRepository.findById("DE0000000001")).isPresent();
		assertThat(editRepository.findAll()).isNotEmpty();
	}

	@Configuration
	static class TestConfig {
		@Bean
		@org.springframework.context.annotation.Primary
		KnowledgeBaseLlmClient knowledgeBaseLlmClient(ObjectMapper objectMapper) {
			return new KnowledgeBaseLlmClient() {
				@Override
				public KnowledgeBaseLlmDossierDraft generateDossier(String isin,
											 String context,
											 List<String> allowedDomains,
											 int maxChars) {
					ArrayNode citations = objectMapper.createArrayNode();
					citations.add(objectMapper.createObjectNode()
							.put("id", "1")
							.put("title", "Issuer Factsheet")
							.put("url", "https://issuer.example.com/factsheet")
							.put("publisher", "Issuer")
							.put("accessed_at", "2024-01-01"));
					citations.add(objectMapper.createObjectNode()
							.put("id", "2")
							.put("title", "KID")
							.put("url", "https://issuer.example.com/kid")
							.put("publisher", "Issuer")
							.put("accessed_at", "2024-01-01"));
					String content = "# " + isin + " — Test Instrument\n" +
							"## Quick profile\n" +
							"name: Test Instrument\n" +
							"## Classification\n" +
							"instrument_type: ETF\n" +
							"asset_class: Equity\n" +
							"layer: 2\n" +
							"layer_notes: Core\n" +
							"## Risk\n" +
							"sri: 5\n" +
							"## Costs & structure\n" +
							"ongoing_charges_pct: 0.20\n" +
							"benchmark_index: MSCI World\n" +
							"## Exposures\n" +
							"## Valuation & profitability\n" +
							"## Sources\n";
					return new KnowledgeBaseLlmDossierDraft(content, "Test Instrument", citations, "test-model");
				}

				@Override
				public KnowledgeBaseLlmDossierDraft patchDossierMissingFields(String isin,
														 String contentMd,
														 JsonNode existingCitations,
														 List<String> missingFields,
														 String context,
														 List<String> allowedDomains,
														 int maxChars) {
					JsonNode citations = existingCitations == null
							? objectMapper.createArrayNode()
							: existingCitations;
					return new KnowledgeBaseLlmDossierDraft(contentMd, "Test Instrument", citations, "test-model");
				}

				@Override
				public my.portfoliomanager.app.llm.KnowledgeBaseLlmExtractionDraft extractMetadata(String dossierText) {
					throw new UnsupportedOperationException();
				}

				@Override
				public KnowledgeBaseLlmAlternativesDraft findAlternatives(String isin, List<String> allowedDomains) {
					return new KnowledgeBaseLlmAlternativesDraft(List.of());
				}
			};
		}
	}
}
