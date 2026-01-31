package my.portfoliomanager.app.service;

import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.dto.AssessorRunRequestDto;
import my.portfoliomanager.app.dto.AssessorRunResponseDto;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
class AssessorInstrumentAssessmentIntegrationTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private AssessorService assessorService;

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
		registry.add("app.kb.enabled", () -> "true");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void instrumentAssessmentReturnsMissingWhenNoApprovedExtraction() throws Exception {
		String isin = "TEST00000001";
		Long dossierId = insertDossier(isin);
		insertExtraction(dossierId, buildPayload(isin, "Missing Approval ETF"), "PENDING_REVIEW", false);
		insertKnowledgeBaseExtraction(isin, buildPayload(isin, "Missing Approval ETF"));

		AssessorRunResponseDto result = assessorService.run(new AssessorRunRequestDto(
				null,
				"instrument_one_time",
				null,
				null,
				null,
				null,
				null,
				List.of(isin),
				100
		));

		assertThat(result.instrumentAssessment()).isNotNull();
		assertThat(result.instrumentAssessment().missingKbIsins()).containsExactly(isin);
		assertThat(result.instrumentAssessment().items()).isEmpty();
	}

	@Test
	void instrumentAssessmentAcceptsAutoApprovedExtraction() throws Exception {
		String isin = "TEST00000002";
		Long dossierId = insertDossier(isin);
		insertExtraction(dossierId, buildPayload(isin, "Auto Approved ETF"), "APPROVED", true);
		insertKnowledgeBaseExtraction(isin, buildPayload(isin, "Auto Approved ETF"));

		AssessorRunResponseDto result = assessorService.run(new AssessorRunRequestDto(
				null,
				"instrument_one_time",
				null,
				null,
				null,
				null,
				null,
				List.of(isin),
				100
		));

		assertThat(result.instrumentAssessment()).isNotNull();
		assertThat(result.instrumentAssessment().missingKbIsins()).isEmpty();
		assertThat(result.instrumentAssessment().items())
				.hasSize(1)
				.first()
				.satisfies(item -> assertThat(item.isin()).isEqualTo(isin));
	}

	private Long insertDossier(String isin) {
		jdbcTemplate.update("""
				insert into instrument_dossiers
					(isin, created_by, origin, status, content_md, citations_json, content_hash, created_at, updated_at)
				values (?, 'tester', 'IMPORT', 'APPROVED', 'content', '[]'::jsonb, 'hash', now(), now())
				""", isin);
		return jdbcTemplate.queryForObject(
				"select dossier_id from instrument_dossiers where isin = ? order by dossier_id desc limit 1",
				Long.class,
				isin
		);
	}

	private void insertExtraction(Long dossierId,
							 InstrumentDossierExtractionPayload payload,
							 String status,
							 boolean autoApproved) throws Exception {
		String json = objectMapper.writeValueAsString(payload);
		jdbcTemplate.update("""
				insert into instrument_dossier_extractions
					(dossier_id, model, extracted_json, missing_fields_json, warnings_json, status, created_at, approved_at, auto_approved)
				values (?, 'test-model', cast(? as jsonb), '[]'::jsonb, '[]'::jsonb, ?, ?, ?, ?)
				""",
			dossierId,
			json,
			status,
			LocalDateTime.now(),
			LocalDateTime.now(),
			autoApproved
		);
	}

	private void insertKnowledgeBaseExtraction(String isin, InstrumentDossierExtractionPayload payload) throws Exception {
		String json = objectMapper.writeValueAsString(payload);
		jdbcTemplate.update("""
				insert into knowledge_base_extractions (isin, status, extracted_json, updated_at)
				values (?, 'COMPLETE', cast(? as jsonb), ?)
				""", isin, json, LocalDateTime.now());
	}

	private InstrumentDossierExtractionPayload buildPayload(String isin, String name) {
		return new InstrumentDossierExtractionPayload(
				isin,
				name,
				"ETF",
				"Equity",
				"global equity",
				1,
				"core",
				new InstrumentDossierExtractionPayload.EtfPayload(new BigDecimal("0.18"), "MSCI World"),
				new InstrumentDossierExtractionPayload.RiskPayload(
						new InstrumentDossierExtractionPayload.SummaryRiskIndicatorPayload(3)
				),
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Global", new BigDecimal("100"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Apple", new BigDecimal("5"))),
				null,
				null,
				null
		);
	}
}
