package my.portfoliomanager.app.service;

import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.dto.AssessorNewInstrumentSuggestionDto;
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
class AssessorOneTimeActionIntegrationTest {
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
	void oneTimeSuggestionsUseIncreaseActionWhenInstrumentExists() throws Exception {
		insertDepot();
		insertInstrument("CAND1", "Candidate ETF");
		insertExtraction(buildPayload("CAND1", "Candidate ETF"));

		AssessorRunResponseDto result = assessorService.run(new AssessorRunRequestDto(
				"BALANCED",
				null,
				100.0,
				25,
				null,
				"saving_plan_gaps"
		));

		AssessorNewInstrumentSuggestionDto suggestion = result.oneTimeAllocation()
				.newInstruments()
				.stream()
				.filter(item -> "CAND1".equals(item.isin()))
				.findFirst()
				.orElseThrow();

		assertThat(suggestion.action()).isEqualTo("increase");
	}

	@Test
	void oneTimeSuggestionsUseNewActionWhenInstrumentMissing() throws Exception {
		insertExtraction(buildPayload("CAND2", "New Candidate ETF"));

		AssessorRunResponseDto result = assessorService.run(new AssessorRunRequestDto(
				"BALANCED",
				null,
				100.0,
				25,
				null,
				"saving_plan_gaps"
		));

		AssessorNewInstrumentSuggestionDto suggestion = result.oneTimeAllocation()
				.newInstruments()
				.stream()
				.filter(item -> "CAND2".equals(item.isin()))
				.findFirst()
				.orElseThrow();

		assertThat(suggestion.action()).isEqualTo("new");
	}

	private void insertDepot() {
		jdbcTemplate.update("""
				insert into depots (depot_id, depot_code, name, provider)
				values (1, 'tr', 'Trade Republic', 'TR')
				on conflict (depot_code) do nothing
				""");
	}

	private void insertInstrument(String isin, String name) {
		jdbcTemplate.update("""
				insert into instruments (isin, name, depot_code, layer, is_deleted)
				values (?, ?, 'tr', 1, false)
				""", isin, name);
	}

	private void insertExtraction(InstrumentDossierExtractionPayload payload) throws Exception {
		String json = objectMapper.writeValueAsString(payload);
		jdbcTemplate.update("""
				insert into knowledge_base_extractions (isin, status, extracted_json, updated_at)
				values (?, 'COMPLETE', cast(? as jsonb), ?)
				""", payload.isin(), json, LocalDateTime.now());
	}

	private InstrumentDossierExtractionPayload buildPayload(String isin, String name) {
		return new InstrumentDossierExtractionPayload(
				isin,
				name,
				"ETF",
				"Equity",
				"global equity",
				1,
				"core global",
				new InstrumentDossierExtractionPayload.EtfPayload(new BigDecimal("0.18"), "MSCI World"),
				null,
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Global", new BigDecimal("100"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Apple", new BigDecimal("5"))),
				null,
				null,
				null
		);
	}
}
