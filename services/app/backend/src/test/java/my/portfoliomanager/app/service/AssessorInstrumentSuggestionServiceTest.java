package my.portfoliomanager.app.service;

import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
class AssessorInstrumentSuggestionServiceTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private AssessorInstrumentSuggestionService suggestionService;

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

	@BeforeEach
	void setup() {
		jdbcTemplate.update("delete from knowledge_base_extractions");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void suggestsNewInstrumentsWhenLayerHasGapAndBudget() throws Exception {
		insertExtraction(buildPayload(
				"EXIST1",
				"Global Core ETF",
				1,
				"global equity",
				"core global",
				new BigDecimal("0.18"),
				"MSCI World",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Global", new BigDecimal("100"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Apple", new BigDecimal("5")))
		));
		insertExtraction(buildPayload(
				"CAND1",
				"Tech Innovators ETF",
				1,
				"technology",
				"technology thematic accumulating",
				new BigDecimal("0.12"),
				"MSCI World Information Technology",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("United States", new BigDecimal("70"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Nvidia", new BigDecimal("6")))
		));

		List<AssessorEngine.SavingPlanItem> plans = List.of(
				new AssessorEngine.SavingPlanItem("EXIST1", 1L, new BigDecimal("50"), 1)
		);
		AssessorInstrumentSuggestionService.SuggestionResult result = suggestionService.suggest(
				new AssessorInstrumentSuggestionService.SuggestionRequest(
						plans,
						Set.of("EXIST1"),
						Map.of(1, new BigDecimal("50")),
						Map.of(1, new BigDecimal("100")),
						25,
						10,
						25,
						Map.of(1, 2),
						Set.of(),
						AssessorGapDetectionPolicy.SAVING_PLAN_GAPS
				)
		);

		assertThat(result.savingPlanSuggestions()).hasSize(1);
		assertThat(result.oneTimeSuggestions()).hasSize(1);
		assertThat(result.savingPlanSuggestions().get(0).isin()).isEqualTo("CAND1");
		assertThat(result.savingPlanSuggestions().get(0).amount()).isEqualByComparingTo(new BigDecimal("50"));
		assertThat(result.savingPlanSuggestions().get(0).rationale()).isNotBlank();
		assertThat(result.oneTimeSuggestions().get(0).isin()).isEqualTo("CAND1");
	}

	@Test
	void suggestsSavingPlanInstrumentsWithoutSavingPlans() throws Exception {
		insertExtraction(buildPayload(
				"EXIST1",
				"Global Core ETF",
				1,
				"global equity",
				"core global",
				new BigDecimal("0.18"),
				"MSCI World",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Global", new BigDecimal("100"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Apple", new BigDecimal("5")))
		));
		insertExtraction(buildPayload(
				"CAND1",
				"Tech Innovators ETF",
				1,
				"technology",
				"technology thematic accumulating",
				new BigDecimal("0.12"),
				"MSCI World Information Technology",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("United States", new BigDecimal("70"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Nvidia", new BigDecimal("6")))
		));

		AssessorInstrumentSuggestionService.SuggestionResult result = suggestionService.suggest(
				new AssessorInstrumentSuggestionService.SuggestionRequest(
						List.of(),
						Set.of("EXIST1"),
						Map.of(1, new BigDecimal("50")),
						Map.of(),
						25,
						10,
						25,
						Map.of(1, 2),
						Set.of(),
						AssessorGapDetectionPolicy.SAVING_PLAN_GAPS
				)
		);

		assertThat(result.savingPlanSuggestions()).hasSize(2);
		assertThat(result.savingPlanSuggestions())
				.extracting(AssessorInstrumentSuggestionService.NewInstrumentSuggestion::isin)
				.contains("CAND1", "EXIST1");
		BigDecimal total = result.savingPlanSuggestions().stream()
				.map(AssessorInstrumentSuggestionService.NewInstrumentSuggestion::amount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		assertThat(total).isEqualByComparingTo(new BigDecimal("50"));
	}

	@Test
	void prefersCandidateWithStrongerValuationSignals() throws Exception {
		insertExtraction(buildPayload(
				"EXIST1",
				"Global Core ETF",
				1,
				"global equity",
				"core global",
				new BigDecimal("0.18"),
				"MSCI World",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Global", new BigDecimal("100"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Apple", new BigDecimal("5")))
		));
		insertExtraction(buildPayloadWithValuation(
				"CAND1",
				"Tech Innovators ETF A",
				1,
				"technology",
				"technology thematic accumulating",
				new BigDecimal("0.12"),
				"MSCI World Information Technology",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("United States", new BigDecimal("70"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Nvidia", new BigDecimal("6"))),
				buildValuation(new BigDecimal("0.15"), new BigDecimal("8.0"), new BigDecimal("8000000000"))
		));
		insertExtraction(buildPayloadWithValuation(
				"CAND2",
				"Tech Innovators ETF B",
				1,
				"technology",
				"technology thematic accumulating",
				new BigDecimal("0.12"),
				"MSCI World Information Technology",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("United States", new BigDecimal("70"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Nvidia", new BigDecimal("6"))),
				buildValuation(new BigDecimal("0.005"), new BigDecimal("100.0"), new BigDecimal("1000"))
		));

		List<AssessorEngine.SavingPlanItem> plans = List.of(
				new AssessorEngine.SavingPlanItem("EXIST1", 1L, new BigDecimal("50"), 1)
		);
		AssessorInstrumentSuggestionService.SuggestionResult result = suggestionService.suggest(
				new AssessorInstrumentSuggestionService.SuggestionRequest(
						plans,
						Set.of("EXIST1"),
						Map.of(1, new BigDecimal("50")),
						Map.of(1, new BigDecimal("100")),
						25,
						10,
						25,
						Map.of(1, 2),
						Set.of(),
						AssessorGapDetectionPolicy.SAVING_PLAN_GAPS
				)
		);

		assertThat(result.savingPlanSuggestions()).hasSize(1);
		assertThat(result.savingPlanSuggestions().get(0).isin()).isEqualTo("CAND1");
	}

	@Test
	void prefersCandidateWithLowerCurrentPeWhenLongtermMissing() throws Exception {
		insertExtraction(buildPayload(
				"EXIST1",
				"Global Core ETF",
				1,
				"global equity",
				"core global",
				new BigDecimal("0.18"),
				"MSCI World",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Global", new BigDecimal("100"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Apple", new BigDecimal("5")))
		));
		insertExtraction(buildPayloadWithValuation(
				"CAND1",
				"Real Estate Fund A",
				1,
				"real estate",
				"real estate thematic accumulating",
				new BigDecimal("0.12"),
				"Global REIT Index",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Global", new BigDecimal("100"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Example REIT", new BigDecimal("6"))),
				buildValuationWithCurrentPe(new BigDecimal("10.0"))
		));
		insertExtraction(buildPayloadWithValuation(
				"CAND2",
				"Real Estate Fund B",
				1,
				"real estate",
				"real estate thematic accumulating",
				new BigDecimal("0.12"),
				"Global REIT Index",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Global", new BigDecimal("100"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Example REIT", new BigDecimal("6"))),
				buildValuationWithCurrentPe(new BigDecimal("25.0"))
		));

		List<AssessorEngine.SavingPlanItem> plans = List.of(
				new AssessorEngine.SavingPlanItem("EXIST1", 1L, new BigDecimal("50"), 1)
		);
		AssessorInstrumentSuggestionService.SuggestionResult result = suggestionService.suggest(
				new AssessorInstrumentSuggestionService.SuggestionRequest(
						plans,
						Set.of("EXIST1"),
						Map.of(1, new BigDecimal("50")),
						Map.of(1, new BigDecimal("100")),
						25,
						10,
						25,
						Map.of(1, 2),
						Set.of(),
						AssessorGapDetectionPolicy.SAVING_PLAN_GAPS
				)
		);

		assertThat(result.savingPlanSuggestions()).hasSize(1);
		assertThat(result.savingPlanSuggestions().get(0).isin()).isEqualTo("CAND1");
	}

	@Test
	void savingPlanSuggestionsStopWhenMaxPlansReached() throws Exception {
		insertExtraction(buildPayload(
				"EXIST1",
				"Global Core ETF",
				1,
				"global equity",
				"core global",
				new BigDecimal("0.18"),
				"MSCI World",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Global", new BigDecimal("100"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Apple", new BigDecimal("5")))
		));
		insertExtraction(buildPayload(
				"CAND1",
				"Tech Innovators ETF",
				1,
				"technology",
				"technology thematic accumulating",
				new BigDecimal("0.12"),
				"MSCI World Information Technology",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("United States", new BigDecimal("70"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Nvidia", new BigDecimal("6")))
		));

		List<AssessorEngine.SavingPlanItem> plans = List.of(
				new AssessorEngine.SavingPlanItem("EXIST1", 1L, new BigDecimal("50"), 1)
		);
		AssessorInstrumentSuggestionService.SuggestionResult result = suggestionService.suggest(
				new AssessorInstrumentSuggestionService.SuggestionRequest(
						plans,
						Set.of("EXIST1"),
						Map.of(1, new BigDecimal("50")),
						Map.of(1, new BigDecimal("100")),
						25,
						10,
						25,
						Map.of(1, 1),
						Set.of(),
						AssessorGapDetectionPolicy.SAVING_PLAN_GAPS
				)
		);

		assertThat(result.savingPlanSuggestions()).isEmpty();
		assertThat(result.oneTimeSuggestions()).isNotEmpty();
	}

	@Test
	void oneTimeSuggestionsFallBackToExistingWhenCandidatesExcluded() throws Exception {
		insertExtraction(buildPayload(
				"EXIST1",
				"Global Core ETF",
				1,
				"global equity",
				"core global",
				new BigDecimal("0.18"),
				"MSCI World",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Global", new BigDecimal("100"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Apple", new BigDecimal("5")))
		));
		insertExtraction(buildPayload(
				"CAND1",
				"Tech Innovators ETF",
				1,
				"technology",
				"technology thematic accumulating",
				new BigDecimal("0.12"),
				"MSCI World Information Technology",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("United States", new BigDecimal("70"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Nvidia", new BigDecimal("6")))
		));

		List<AssessorEngine.SavingPlanItem> plans = List.of(
				new AssessorEngine.SavingPlanItem("EXIST1", 1L, new BigDecimal("50"), 1)
		);
		AssessorInstrumentSuggestionService.SuggestionResult result = suggestionService.suggest(
				new AssessorInstrumentSuggestionService.SuggestionRequest(
						plans,
						Set.of("EXIST1"),
						Map.of(1, new BigDecimal("50")),
						Map.of(1, new BigDecimal("100")),
						25,
						10,
						25,
						Map.of(1, 5),
						Set.of("CAND1"),
						AssessorGapDetectionPolicy.SAVING_PLAN_GAPS
				)
		);

		assertThat(result.savingPlanSuggestions()).hasSize(1);
		assertThat(result.oneTimeSuggestions()).hasSize(1);
		assertThat(result.oneTimeSuggestions().get(0).isin()).isEqualTo("EXIST1");
	}

	@Test
	void savingPlanSuggestionsIgnoreNonPlanCoverageWhenGapsExist() throws Exception {
		insertExtraction(buildPayload(
				"EXIST1",
				"Global Core ETF",
				1,
				"core global",
				"core global",
				new BigDecimal("0.18"),
				"MSCI World",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Global", new BigDecimal("100"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Apple", new BigDecimal("5")))
		));
		insertExtraction(buildPayload(
				"HOLD1",
				"Tech Focus ETF",
				1,
				"technology",
				"technology thematic",
				new BigDecimal("0.12"),
				"MSCI World Information Technology",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("United States", new BigDecimal("70"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Nvidia", new BigDecimal("6")))
		));

		List<AssessorEngine.SavingPlanItem> plans = List.of(
				new AssessorEngine.SavingPlanItem("EXIST1", 1L, new BigDecimal("50"), 1)
		);
		AssessorInstrumentSuggestionService.SuggestionResult result = suggestionService.suggest(
				new AssessorInstrumentSuggestionService.SuggestionRequest(
						plans,
						Set.of("EXIST1", "HOLD1"),
						Map.of(1, new BigDecimal("50")),
						Map.of(),
						25,
						10,
						25,
						Map.of(1, 5),
						Set.of(),
						AssessorGapDetectionPolicy.SAVING_PLAN_GAPS
				)
		);

		assertThat(result.savingPlanSuggestions()).hasSize(1);
		assertThat(result.savingPlanSuggestions().get(0).isin()).isEqualTo("HOLD1");
	}

	@Test
	void portfolioGapPolicySkipsSuggestionsWhenPortfolioCoverageExists() throws Exception {
		insertExtraction(buildPayload(
				"EXIST1",
				"Global Core ETF",
				1,
				"core global",
				"core global",
				new BigDecimal("0.18"),
				"MSCI World",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Global", new BigDecimal("100"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Apple", new BigDecimal("5")))
		));
		insertExtraction(buildPayload(
				"HOLD1",
				"Tech Focus ETF",
				1,
				"technology",
				"technology thematic",
				new BigDecimal("0.12"),
				"MSCI World Information Technology",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("United States", new BigDecimal("70"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Nvidia", new BigDecimal("6")))
		));

		List<AssessorEngine.SavingPlanItem> plans = List.of(
				new AssessorEngine.SavingPlanItem("EXIST1", 1L, new BigDecimal("50"), 1)
		);
		AssessorInstrumentSuggestionService.SuggestionResult result = suggestionService.suggest(
				new AssessorInstrumentSuggestionService.SuggestionRequest(
						plans,
						Set.of("EXIST1", "HOLD1"),
						Map.of(1, new BigDecimal("50")),
						Map.of(),
						25,
						10,
						25,
						Map.of(1, 5),
						Set.of(),
						AssessorGapDetectionPolicy.PORTFOLIO_GAPS
				)
		);

		assertThat(result.savingPlanSuggestions()).isEmpty();
	}

	@Test
	void savingPlanSuggestionsRespectMinimumRebalancingAmount() throws Exception {
		insertExtraction(buildPayload(
				"EXIST1",
				"Global Core ETF",
				1,
				"core global",
				"core global",
				new BigDecimal("0.18"),
				"MSCI World",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Global", new BigDecimal("100"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Apple", new BigDecimal("5")))
		));
		insertExtraction(buildPayload(
				"CAND1",
				"Tech Focus ETF",
				1,
				"technology",
				"technology thematic",
				new BigDecimal("0.12"),
				"MSCI World Information Technology",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("United States", new BigDecimal("70"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Nvidia", new BigDecimal("6")))
		));

		List<AssessorEngine.SavingPlanItem> plans = List.of(
				new AssessorEngine.SavingPlanItem("EXIST1", 1L, new BigDecimal("50"), 1)
		);
		AssessorInstrumentSuggestionService.SuggestionResult result = suggestionService.suggest(
				new AssessorInstrumentSuggestionService.SuggestionRequest(
						plans,
						Set.of("EXIST1"),
						Map.of(1, new BigDecimal("20")),
						Map.of(),
						15,
						30,
						25,
						Map.of(1, 5),
						Set.of(),
						AssessorGapDetectionPolicy.SAVING_PLAN_GAPS
				)
		);

		assertThat(result.savingPlanSuggestions()).isEmpty();
	}

	@Test
	void oneTimeSuggestionsStillUseFullCoverageWhenSavingPlanHasGaps() throws Exception {
		insertExtraction(buildPayload(
				"EXIST1",
				"Global Core ETF",
				1,
				"core global",
				"core global",
				new BigDecimal("0.60"),
				"MSCI World",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Global", new BigDecimal("100"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Apple", new BigDecimal("5")))
		));
		insertExtraction(buildPayload(
				"HOLD1",
				"Dividend Quality ETF",
				1,
				"dividend",
				"dividend",
				new BigDecimal("0.05"),
				"MSCI World High Dividend",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("Europe", new BigDecimal("60"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Nestle", new BigDecimal("4")))
		));
		insertExtraction(buildPayload(
				"HOLD2",
				"Tech Growth ETF",
				1,
				"technology",
				"technology thematic",
				new BigDecimal("0.40"),
				"MSCI World Information Technology",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("United States", new BigDecimal("70"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Nvidia", new BigDecimal("6")))
		));
		insertExtraction(buildPayload(
				"CAND1",
				"Tech Momentum ETF",
				1,
				"technology",
				"technology thematic",
				new BigDecimal("0.60"),
				"MSCI World Information Technology",
				List.of(new InstrumentDossierExtractionPayload.RegionExposurePayload("United States", new BigDecimal("70"))),
				List.of(new InstrumentDossierExtractionPayload.HoldingPayload("Nvidia", new BigDecimal("6")))
		));

		List<AssessorEngine.SavingPlanItem> plans = List.of(
				new AssessorEngine.SavingPlanItem("EXIST1", 1L, new BigDecimal("50"), 1)
		);
		AssessorInstrumentSuggestionService.SuggestionResult result = suggestionService.suggest(
				new AssessorInstrumentSuggestionService.SuggestionRequest(
						plans,
						Set.of("EXIST1", "HOLD1", "HOLD2"),
						Map.of(),
						Map.of(1, new BigDecimal("25")),
						25,
						10,
						25,
						Map.of(1, 5),
						Set.of("HOLD2"),
						AssessorGapDetectionPolicy.SAVING_PLAN_GAPS
				)
		);

		assertThat(result.oneTimeSuggestions()).hasSize(1);
		assertThat(result.oneTimeSuggestions().get(0).isin()).isEqualTo("HOLD1");
	}

	private void insertExtraction(InstrumentDossierExtractionPayload payload) throws Exception {
		String json = objectMapper.writeValueAsString(payload);
		jdbcTemplate.update("""
				insert into knowledge_base_extractions (isin, status, extracted_json, updated_at)
				values (?, 'COMPLETE', cast(? as jsonb), ?)
				""", payload.isin(), json, LocalDateTime.now());
	}

	private InstrumentDossierExtractionPayload buildPayload(String isin,
															String name,
															int layer,
															String subClass,
															String notes,
															BigDecimal ter,
															String benchmark,
															List<InstrumentDossierExtractionPayload.RegionExposurePayload> regions,
															List<InstrumentDossierExtractionPayload.HoldingPayload> holdings) {
		return new InstrumentDossierExtractionPayload(
				isin,
				name,
				"ETF",
				"Equity",
				subClass,
				layer,
				notes,
				new InstrumentDossierExtractionPayload.EtfPayload(ter, benchmark),
				null,
				regions,
				holdings,
				null,
				null,
				null
		);
	}

	private InstrumentDossierExtractionPayload buildPayloadWithValuation(String isin,
																		 String name,
																		 int layer,
																		 String subClass,
																		 String notes,
																		 BigDecimal ter,
																		 String benchmark,
																		 List<InstrumentDossierExtractionPayload.RegionExposurePayload> regions,
																		 List<InstrumentDossierExtractionPayload.HoldingPayload> holdings,
																		 InstrumentDossierExtractionPayload.ValuationPayload valuation) {
		return new InstrumentDossierExtractionPayload(
				isin,
				name,
				"ETF",
				"Equity",
				subClass,
				layer,
				notes,
				new InstrumentDossierExtractionPayload.EtfPayload(ter, benchmark),
				null,
				regions,
				holdings,
				null,
				valuation,
				null,
				null,
				null
		);
	}

	private InstrumentDossierExtractionPayload.ValuationPayload buildValuation(BigDecimal earningsYield,
																			   BigDecimal evToEbitda,
																			   BigDecimal ebitdaEur) {
		return objectMapper.convertValue(Map.of(
				"earnings_yield_longterm", earningsYield,
				"ev_to_ebitda", evToEbitda,
				"ebitda_eur", ebitdaEur,
				"ebitda_currency", "EUR"
		), InstrumentDossierExtractionPayload.ValuationPayload.class);
	}

	private InstrumentDossierExtractionPayload.ValuationPayload buildValuationWithCurrentPe(BigDecimal peCurrent) {
		return objectMapper.convertValue(Map.of(
				"pe_current", peCurrent
		), InstrumentDossierExtractionPayload.ValuationPayload.class);
	}
}
