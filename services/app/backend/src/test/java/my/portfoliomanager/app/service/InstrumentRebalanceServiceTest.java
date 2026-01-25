package my.portfoliomanager.app.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.dto.InstrumentProposalDto;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class InstrumentRebalanceServiceTest {
	@Test
	void gatingBlocksWhenKbEntryMissing() {
		Map<String, ExtractionRow> rows = Map.of(
				"DE000A", new ExtractionRow("DE000A", "COMPLETE", "{\"isin\":\"DE000A\"}")
		);
		InstrumentRebalanceService service = buildService(rows, true);

		List<SavingPlanInstrument> instruments = List.of(
				new SavingPlanInstrument("DE000A", "Alpha Fund", new BigDecimal("10"), 1, null),
				new SavingPlanInstrument("DE000B", "Beta Fund", new BigDecimal("20"), 1, null)
		);
		Map<Integer, BigDecimal> budgets = Map.of(1, new BigDecimal("30"));

		var result = service.buildInstrumentProposals(instruments, budgets, 15, null, false);

		assertThat(result.gating().kbComplete()).isFalse();
		assertThat(result.gating().missingIsins()).contains("DE000B");
		assertThat(result.proposals()).isEmpty();
	}

	@Test
	void doesNotDropInstrumentsBelowMinimumWhenBudgetMatches() {
		Map<String, ExtractionRow> rows = Map.of(
				"DE000A", new ExtractionRow("DE000A", "COMPLETE", "{\"etf\":{\"ongoing_charges_pct\":0.1}}"),
				"DE000B", new ExtractionRow("DE000B", "COMPLETE", "{\"etf\":{\"ongoing_charges_pct\":1.0}}")
		);
		InstrumentRebalanceService service = buildService(rows, true);

		List<SavingPlanInstrument> instruments = List.of(
				new SavingPlanInstrument("DE000A", "Alpha Fund", new BigDecimal("20"), 1, null),
				new SavingPlanInstrument("DE000B", "Beta Fund", new BigDecimal("10"), 1, null)
		);
		Map<Integer, BigDecimal> budgets = Map.of(1, new BigDecimal("30"));

		var result = service.buildInstrumentProposals(instruments, budgets, 15, null, false);

		Map<String, InstrumentProposalDto> byIsin = toMap(result.proposals());
		assertThat(byIsin.get("DE000A").getProposedAmountEur()).isEqualTo(20.0d);
		assertThat(byIsin.get("DE000B").getProposedAmountEur()).isEqualTo(10.0d);
		assertThat(byIsin.get("DE000B").getReasonCodes()).doesNotContain("MIN_AMOUNT_DROPPED");
	}

	@Test
	void dropsInstrumentWhenNegativeDeltaCannotMeetMinimums() {
		Map<String, ExtractionRow> rows = Map.of(
				"DE000A", new ExtractionRow("DE000A", "COMPLETE", "{\"isin\":\"DE000A\"}"),
				"DE000B", new ExtractionRow("DE000B", "COMPLETE", "{\"isin\":\"DE000B\"}")
		);
		InstrumentRebalanceService service = buildService(rows, true);

		List<SavingPlanInstrument> instruments = List.of(
				new SavingPlanInstrument("DE000A", "Alpha Fund", new BigDecimal("20"), 1, null),
				new SavingPlanInstrument("DE000B", "Beta Fund", new BigDecimal("20"), 1, null)
		);
		Map<Integer, BigDecimal> budgets = Map.of(1, new BigDecimal("25"));

		var result = service.buildInstrumentProposals(instruments, budgets, 15, 0, false);

		Map<String, InstrumentProposalDto> byIsin = toMap(result.proposals());
		long dropped = byIsin.values().stream()
				.filter(proposal -> proposal.getProposedAmountEur() == 0.0d)
				.count();
		assertThat(dropped).isEqualTo(1);
		assertThat(byIsin.values().stream()
				.anyMatch(proposal -> proposal.getReasonCodes().contains("MIN_AMOUNT_DROPPED")))
				.isTrue();
	}

	@Test
	void keepsAllInstrumentsOnPositiveBudgetIncrease() {
		Map<String, ExtractionRow> rows = Map.of(
				"DE000A", new ExtractionRow("DE000A", "COMPLETE", "{\"etf\":{\"ongoing_charges_pct\":0.1}}"),
				"DE000B", new ExtractionRow("DE000B", "COMPLETE", "{\"etf\":{\"ongoing_charges_pct\":0.5}}")
		);
		InstrumentRebalanceService service = buildService(rows, true);

		List<SavingPlanInstrument> instruments = List.of(
				new SavingPlanInstrument("DE000A", "Alpha Fund", new BigDecimal("20"), 1, null),
				new SavingPlanInstrument("DE000B", "Beta Fund", new BigDecimal("10"), 1, null)
		);
		Map<Integer, BigDecimal> budgets = Map.of(1, new BigDecimal("40"));

		var result = service.buildInstrumentProposals(instruments, budgets, 15, 5, false);

		Map<String, InstrumentProposalDto> byIsin = toMap(result.proposals());
		assertThat(byIsin.get("DE000A").getProposedAmountEur()).isGreaterThan(0.0d);
		assertThat(byIsin.get("DE000B").getProposedAmountEur()).isGreaterThan(0.0d);
		assertThat(byIsin.get("DE000A").getReasonCodes()).doesNotContain("MIN_AMOUNT_DROPPED");
		assertThat(byIsin.get("DE000B").getReasonCodes()).doesNotContain("MIN_AMOUNT_DROPPED");
		assertThat(sumLayer(result.proposals(), 1)).isEqualTo(40.0d);
	}

	@Test
	void roundsUsingLargestRemainder() {
		Map<String, ExtractionRow> rows = Map.of(
				"DE000A", new ExtractionRow("DE000A", "COMPLETE", "{\"isin\":\"DE000A\"}"),
				"DE000B", new ExtractionRow("DE000B", "COMPLETE", "{\"isin\":\"DE000B\"}"),
				"DE000C", new ExtractionRow("DE000C", "COMPLETE", "{\"isin\":\"DE000C\"}")
		);
		InstrumentRebalanceService service = buildService(rows, true);

		List<SavingPlanInstrument> instruments = List.of(
				new SavingPlanInstrument("DE000A", "Alpha Fund", new BigDecimal("30"), 2, null),
				new SavingPlanInstrument("DE000B", "Beta Fund", new BigDecimal("30"), 2, null),
				new SavingPlanInstrument("DE000C", "Gamma Fund", new BigDecimal("40"), 2, null)
		);
		Map<Integer, BigDecimal> budgets = Map.of(2, new BigDecimal("100"));

		var result = service.buildInstrumentProposals(instruments, budgets, 5, null, false);

		double total = result.proposals().stream()
				.mapToDouble(InstrumentProposalDto::getProposedAmountEur)
				.sum();
		assertThat(total).isEqualTo(100.0d);
	}

	@Test
	void withinToleranceKeepsCurrentAmounts() {
		Map<String, ExtractionRow> rows = Map.of(
				"DE000A", new ExtractionRow("DE000A", "COMPLETE", "{\"isin\":\"DE000A\"}"),
				"DE000B", new ExtractionRow("DE000B", "COMPLETE", "{\"isin\":\"DE000B\"}")
		);
		InstrumentRebalanceService service = buildService(rows, true);

		List<SavingPlanInstrument> instruments = List.of(
				new SavingPlanInstrument("DE000A", "Alpha Fund", new BigDecimal("60"), 1, null),
				new SavingPlanInstrument("DE000B", "Beta Fund", new BigDecimal("40"), 1, null)
		);
		Map<Integer, BigDecimal> budgets = Map.of(1, new BigDecimal("100"));

		var result = service.buildInstrumentProposals(instruments, budgets, 15, null, true);

		Map<String, InstrumentProposalDto> byIsin = toMap(result.proposals());
		assertThat(byIsin.get("DE000A").getProposedAmountEur()).isEqualTo(60.0d);
		assertThat(byIsin.get("DE000B").getProposedAmountEur()).isEqualTo(40.0d);
		assertThat(byIsin.get("DE000A").getReasonCodes()).contains("NO_CHANGE_WITHIN_TOLERANCE");
	}

	@Test
	void prefersUniqueHoldingsWhenOverlapDetected() {
		Map<String, ExtractionRow> rows = Map.of(
				"DE000A", new ExtractionRow("DE000A", "COMPLETE",
						"{\"top_holdings\":[{\"name\":\"Apple\"},{\"name\":\"Microsoft\"}]}"),
				"DE000B", new ExtractionRow("DE000B", "COMPLETE",
						"{\"top_holdings\":[{\"name\":\"Apple\"},{\"name\":\"Microsoft\"}]}"),
				"DE000C", new ExtractionRow("DE000C", "COMPLETE",
						"{\"top_holdings\":[{\"name\":\"Nestle\"},{\"name\":\"Novartis\"}]}")
		);
		InstrumentRebalanceService service = buildService(rows, true);

		List<SavingPlanInstrument> instruments = List.of(
				new SavingPlanInstrument("DE000A", "Alpha Fund", new BigDecimal("30"), 1, null),
				new SavingPlanInstrument("DE000B", "Beta Fund", new BigDecimal("30"), 1, null),
				new SavingPlanInstrument("DE000C", "Gamma Fund", new BigDecimal("40"), 1, null)
		);
		Map<Integer, BigDecimal> budgets = Map.of(1, new BigDecimal("100"));

		var result = service.buildInstrumentProposals(instruments, budgets, 5, null, false);

		Map<String, InstrumentProposalDto> byIsin = toMap(result.proposals());
		assertThat(byIsin.get("DE000C").getProposedAmountEur())
				.isGreaterThan(byIsin.get("DE000A").getProposedAmountEur());
	}

	@Test
	void keepsCurrentAmountsWhenLayerTotalUnchanged() {
		Map<String, ExtractionRow> rows = Map.of(
				"DE000A", new ExtractionRow("DE000A", "COMPLETE", "{\"isin\":\"DE000A\"}"),
				"DE000B", new ExtractionRow("DE000B", "COMPLETE", "{\"isin\":\"DE000B\"}")
		);
		InstrumentRebalanceService service = buildService(rows, true);

		List<SavingPlanInstrument> instruments = List.of(
				new SavingPlanInstrument("DE000A", "Alpha Fund", new BigDecimal("55"), 1, null),
				new SavingPlanInstrument("DE000B", "Beta Fund", new BigDecimal("45"), 1, null)
		);
		Map<Integer, BigDecimal> budgets = Map.of(1, new BigDecimal("100"));

		var result = service.buildInstrumentProposals(instruments, budgets, 5, 10, false);

		Map<String, InstrumentProposalDto> byIsin = toMap(result.proposals());
		assertThat(byIsin.get("DE000A").getProposedAmountEur()).isEqualTo(55.0d);
		assertThat(byIsin.get("DE000B").getProposedAmountEur()).isEqualTo(45.0d);
		assertThat(byIsin.get("DE000A").getReasonCodes()).doesNotContain("MIN_REBALANCE_AMOUNT");
		assertThat(byIsin.get("DE000B").getReasonCodes()).doesNotContain("MIN_REBALANCE_AMOUNT");
	}

	@Test
	void prefersLowerCurrentPeInValuationWeights() {
		Map<String, ExtractionRow> rows = Map.of(
				"DE000A", new ExtractionRow("DE000A", "COMPLETE", "{\"valuation\":{\"pe_current\":10.0}}"),
				"DE000B", new ExtractionRow("DE000B", "COMPLETE", "{\"valuation\":{\"pe_current\":30.0}}")
		);
		InstrumentRebalanceService service = buildService(rows, true);

		List<SavingPlanInstrument> instruments = List.of(
				new SavingPlanInstrument("DE000A", "Alpha Fund", new BigDecimal("50"), 1, null),
				new SavingPlanInstrument("DE000B", "Beta Fund", new BigDecimal("50"), 1, null)
		);
		Map<Integer, BigDecimal> budgets = Map.of(1, new BigDecimal("1000"));

		var result = service.buildInstrumentProposals(instruments, budgets, 1, null, false);

		Map<String, InstrumentProposalDto> byIsin = toMap(result.proposals());
		assertThat(byIsin.get("DE000A").getProposedAmountEur())
				.isGreaterThan(byIsin.get("DE000B").getProposedAmountEur());
	}

	@Test
	void prefersHigherHoldingsYieldForEtfWeights() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		String jsonA = mapper.writeValueAsString(Map.of(
				"instrument_type", "ETF",
				"asset_class", "Equity",
				"etf", Map.of("ongoing_charges_pct", 0.2),
				"valuation", Map.of(
						"earnings_yield_ttm_holdings", 0.08,
						"pe_method", "provider_weighted_avg",
						"pe_horizon", "ttm",
						"neg_earnings_handling", "exclude"
				)
		));
		String jsonB = mapper.writeValueAsString(Map.of(
				"instrument_type", "ETF",
				"asset_class", "Equity",
				"etf", Map.of("ongoing_charges_pct", 0.2),
				"valuation", Map.of(
						"earnings_yield_ttm_holdings", 0.02,
						"pe_method", "provider_weighted_avg",
						"pe_horizon", "ttm",
						"neg_earnings_handling", "exclude"
				)
		));
		Map<String, ExtractionRow> rows = Map.of(
				"DE000A", new ExtractionRow("DE000A", "COMPLETE", jsonA),
				"DE000B", new ExtractionRow("DE000B", "COMPLETE", jsonB)
		);
		InstrumentRebalanceService service = buildService(rows, true);

		List<SavingPlanInstrument> instruments = List.of(
				new SavingPlanInstrument("DE000A", "Alpha Fund", new BigDecimal("50"), 1, null),
				new SavingPlanInstrument("DE000B", "Beta Fund", new BigDecimal("50"), 1, null)
		);
		Map<Integer, BigDecimal> budgets = Map.of(1, new BigDecimal("1000"));

		var result = service.buildInstrumentProposals(instruments, budgets, 1, null, false);

		Map<String, InstrumentProposalDto> byIsin = toMap(result.proposals());
		assertThat(byIsin.get("DE000A").getProposedAmountEur())
				.isGreaterThan(byIsin.get("DE000B").getProposedAmountEur());
	}

	@Test
	void prefersHigherPeQualityFlagsInValuationWeights() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		String jsonA = mapper.writeValueAsString(Map.of(
				"instrument_type", "ETF",
				"asset_class", "Equity",
				"etf", Map.of("ongoing_charges_pct", 0.2),
				"valuation", Map.of(
						"earnings_yield_ttm_holdings", 0.05,
						"pe_method", "provider_aggregate",
						"pe_horizon", "ttm",
						"neg_earnings_handling", "aggregate_allows_negative"
				)
		));
		String jsonB = mapper.writeValueAsString(Map.of(
				"instrument_type", "ETF",
				"asset_class", "Equity",
				"etf", Map.of("ongoing_charges_pct", 0.2),
				"valuation", Map.of(
						"earnings_yield_ttm_holdings", 0.05,
						"pe_method", "provider_weighted_avg",
						"pe_horizon", "normalized",
						"neg_earnings_handling", "exclude"
				)
		));
		Map<String, ExtractionRow> rows = Map.of(
				"DE000A", new ExtractionRow("DE000A", "COMPLETE", jsonA),
				"DE000B", new ExtractionRow("DE000B", "COMPLETE", jsonB)
		);
		InstrumentRebalanceService service = buildService(rows, true);

		List<SavingPlanInstrument> instruments = List.of(
				new SavingPlanInstrument("DE000A", "Alpha Fund", new BigDecimal("50"), 1, null),
				new SavingPlanInstrument("DE000B", "Beta Fund", new BigDecimal("50"), 1, null)
		);
		Map<Integer, BigDecimal> budgets = Map.of(1, new BigDecimal("1000"));

		var result = service.buildInstrumentProposals(instruments, budgets, 1, null, false);

		Map<String, InstrumentProposalDto> byIsin = toMap(result.proposals());
		assertThat(byIsin.get("DE000B").getProposedAmountEur())
				.isGreaterThan(byIsin.get("DE000A").getProposedAmountEur());
	}

	@Test
	void penalizesLowerDataQualityInWeights() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		String jsonA = mapper.writeValueAsString(Map.of(
				"instrument_type", "ETF",
				"asset_class", "Equity",
				"etf", Map.of("ongoing_charges_pct", 0.2),
				"valuation", Map.of(
						"earnings_yield_ttm_holdings", 0.05,
						"pe_method", "provider_weighted_avg",
						"pe_horizon", "ttm",
						"neg_earnings_handling", "exclude"
				)
		));
		String jsonB = mapper.writeValueAsString(Map.of(
				"instrument_type", "ETF",
				"asset_class", "Equity",
				"etf", Map.of("ongoing_charges_pct", 0.2),
				"valuation", Map.of(
						"earnings_yield_ttm_holdings", 0.05,
						"pe_method", "provider_weighted_avg",
						"pe_horizon", "ttm",
						"neg_earnings_handling", "exclude"
				),
				"missing_fields", List.of(Map.of("field", "valuation", "reason", "missing")),
				"warnings", List.of(Map.of("message", "range"))
		));
		Map<String, ExtractionRow> rows = Map.of(
				"DE000A", new ExtractionRow("DE000A", "COMPLETE", jsonA),
				"DE000B", new ExtractionRow("DE000B", "COMPLETE", jsonB)
		);
		InstrumentRebalanceService service = buildService(rows, true);

		List<SavingPlanInstrument> instruments = List.of(
				new SavingPlanInstrument("DE000A", "Alpha Fund", new BigDecimal("50"), 1, null),
				new SavingPlanInstrument("DE000B", "Beta Fund", new BigDecimal("50"), 1, null)
		);
		Map<Integer, BigDecimal> budgets = Map.of(1, new BigDecimal("1000"));

		var result = service.buildInstrumentProposals(instruments, budgets, 1, null, false);

		Map<String, InstrumentProposalDto> byIsin = toMap(result.proposals());
		assertThat(byIsin.get("DE000A").getProposedAmountEur())
				.isGreaterThan(byIsin.get("DE000B").getProposedAmountEur());
	}

	@Test
	void backupDataKeepsSavingPlanTotals() throws IOException {
		BackupFixture fixture = loadBackupFixture();
		InstrumentRebalanceService service = buildService(fixture.extractions(), true);

		var result = service.buildInstrumentProposals(
				fixture.instruments(),
				fixture.layerBudgets(),
				fixture.minimumSavingPlanSize(),
				fixture.minimumRebalancingAmount(),
				false
		);

		double total = result.proposals().stream()
				.mapToDouble(InstrumentProposalDto::getProposedAmountEur)
				.sum();
		assertThat(total).isLessThanOrEqualTo(fixture.monthlyTotal().doubleValue());
		assertThat(sumLayer(result.proposals(), 4))
				.isLessThanOrEqualTo(fixture.layerBudgets().getOrDefault(4, BigDecimal.ZERO).doubleValue());
	}

	@Test
	void dropsInstrumentsWhenLayerBudgetFallsBelowMinimums() throws IOException {
		BackupFixture fixture = loadBackupFixture();
		Set<String> isins = Set.of("DE0006599905", "DE0007030009", "DE0007236101", "DE000SHL1006");
		Map<String, ExtractionRow> rows = new HashMap<>();
		for (String isin : isins) {
			ExtractionRow row = fixture.extractions().get(isin);
			if (row == null) {
				throw new IllegalStateException("Missing KB extraction for " + isin);
			}
			rows.put(isin, row);
		}
		InstrumentRebalanceService service = buildService(rows, true);

		List<SavingPlanInstrument> instruments = List.of(
				new SavingPlanInstrument("DE0006599905", "Test A", new BigDecimal("15"), 4, LocalDate.parse("2026-01-10")),
				new SavingPlanInstrument("DE0007030009", "Test B", new BigDecimal("15"), 4, LocalDate.parse("2026-01-12")),
				new SavingPlanInstrument("DE0007236101", "Test C", new BigDecimal("25"), 4, LocalDate.parse("2026-01-11")),
				new SavingPlanInstrument("DE000SHL1006", "Test D", new BigDecimal("20"), 4, LocalDate.parse("2026-01-13"))
		);
		Map<Integer, BigDecimal> budgets = Map.of(4, new BigDecimal("45"));

		var result = service.buildInstrumentProposals(instruments, budgets, 15, null, false);

		long dropped = result.proposals().stream()
				.filter(proposal -> proposal.getProposedAmountEur() == 0.0d)
				.count();
		assertThat(dropped).isGreaterThanOrEqualTo(1);
		double layerTotal = sumLayer(result.proposals(), 4);
		assertThat(layerTotal).isBetween(45.0d, 75.0d);
	}

	private InstrumentRebalanceService buildService(Map<String, ExtractionRow> rows, boolean kbEnabled) {
		NamedParameterJdbcTemplate jdbcTemplate = Mockito.mock(NamedParameterJdbcTemplate.class);
		Mockito.doAnswer(invocation -> {
			RowCallbackHandler handler = invocation.getArgument(2);
			for (ExtractionRow row : rows.values()) {
				ResultSet rs = Mockito.mock(ResultSet.class);
				Mockito.when(rs.getString("isin")).thenReturn(row.isin());
				Mockito.when(rs.getString("status")).thenReturn(row.status());
				Mockito.when(rs.getString("extracted_json")).thenReturn(row.json());
				handler.processRow(rs);
			}
			return null;
		}).when(jdbcTemplate).query(Mockito.anyString(), Mockito.any(MapSqlParameterSource.class), Mockito.any(RowCallbackHandler.class));

		return new InstrumentRebalanceService(jdbcTemplate, new ObjectMapper(), buildProperties(kbEnabled), new SavingPlanDeltaAllocator());
	}

	private AppProperties buildProperties(boolean kbEnabled) {
		AppProperties.Security security = new AppProperties.Security("admin", "admin");
		AppProperties.Jwt jwt = new AppProperties.Jwt("secret", "issuer");
		AppProperties.Llm.OpenAi openAi = new AppProperties.Llm.OpenAi(null, null, null, null, null);
		AppProperties.Llm llm = new AppProperties.Llm("none", openAi);
		AppProperties.Kb kb = new AppProperties.Kb(kbEnabled, false);
		return new AppProperties(security, jwt, llm, kb);
	}

	private Map<String, InstrumentProposalDto> toMap(List<InstrumentProposalDto> proposals) {
		Map<String, InstrumentProposalDto> result = new HashMap<>();
		for (InstrumentProposalDto proposal : proposals) {
			result.put(proposal.getIsin(), proposal);
		}
		return result;
	}

	private double sumLayer(List<InstrumentProposalDto> proposals, int layer) {
		return proposals.stream()
				.filter(proposal -> proposal.getLayer() != null && proposal.getLayer() == layer)
				.mapToDouble(InstrumentProposalDto::getProposedAmountEur)
				.sum();
	}

	private BackupFixture loadBackupFixture() throws IOException {
		Map<String, byte[]> entries = loadBackupEntries();
		ObjectMapper mapper = new ObjectMapper();

		List<Map<String, Object>> sparplans = mapper.readValue(entries.get("data/sparplans.json"), new TypeReference<>() {
		});
		List<Map<String, Object>> overrides = mapper.readValue(entries.get("data/instrument_overrides.json"), new TypeReference<>() {
		});
		List<Map<String, Object>> kbRows = mapper.readValue(entries.get("data/knowledge_base_extractions.json"), new TypeReference<>() {
		});
		List<Map<String, Object>> configRows = mapper.readValue(entries.get("data/layer_target_config.json"), new TypeReference<>() {
		});

		Map<String, Integer> layerByIsin = new HashMap<>();
		for (Map<String, Object> row : overrides) {
			Object isin = row.get("isin");
			Object layer = row.get("layer");
			if (isin != null && layer != null) {
				layerByIsin.put(String.valueOf(isin), ((Number) layer).intValue());
			}
		}

		Map<String, BigDecimal> amountByIsin = new LinkedHashMap<>();
		Map<String, LocalDate> lastChangedByIsin = new HashMap<>();
		BigDecimal monthlyTotal = BigDecimal.ZERO;
		for (Map<String, Object> row : sparplans) {
			if (!Boolean.TRUE.equals(row.get("active"))) {
				continue;
			}
			String frequency = String.valueOf(row.get("frequency"));
			if (!"monthly".equalsIgnoreCase(frequency)) {
				continue;
			}
			String isin = String.valueOf(row.get("isin"));
			BigDecimal amount = new BigDecimal(String.valueOf(row.get("amount_eur")));
			amountByIsin.merge(isin, amount, BigDecimal::add);
			monthlyTotal = monthlyTotal.add(amount);
			Object lastChangedRaw = row.get("last_changed");
			if (lastChangedRaw != null) {
				LocalDate lastChanged = LocalDate.parse(String.valueOf(lastChangedRaw));
				lastChangedByIsin.merge(isin, lastChanged, (left, right) -> left.isAfter(right) ? left : right);
			}
		}

		List<SavingPlanInstrument> instruments = new ArrayList<>();
		for (Map.Entry<String, BigDecimal> entry : amountByIsin.entrySet()) {
			String isin = entry.getKey();
			int layer = layerByIsin.getOrDefault(isin, 5);
			LocalDate lastChanged = lastChangedByIsin.get(isin);
			instruments.add(new SavingPlanInstrument(isin, isin, entry.getValue(), layer, lastChanged));
		}

		LayerTargetConfig config = parseLayerTargetConfig(mapper, configRows);
		Map<Integer, BigDecimal> budgets = roundLayerTargets(buildLayerTargets(config.layerTargets(), monthlyTotal), monthlyTotal);

		Set<String> isins = new HashSet<>(amountByIsin.keySet());
		Map<String, ExtractionRow> extractions = new HashMap<>();
		for (Map<String, Object> row : kbRows) {
			String isin = String.valueOf(row.get("isin"));
			if (!isins.contains(isin)) {
				continue;
			}
			String status = String.valueOf(row.get("status"));
			Object extracted = row.get("extracted_json");
			if (!(extracted instanceof Map)) {
				continue;
			}
			Object value = ((Map<?, ?>) extracted).get("value");
			if (value == null) {
				continue;
			}
			extractions.put(isin, new ExtractionRow(isin, status, String.valueOf(value)));
		}

		return new BackupFixture(instruments, budgets, config.minimumSavingPlanSize(), config.minimumRebalancingAmount(),
				extractions, monthlyTotal);
	}

	private Map<Integer, BigDecimal> buildLayerTargets(Map<Integer, BigDecimal> targetWeights, BigDecimal total) {
		Map<Integer, BigDecimal> targets = new HashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal weight = targetWeights.getOrDefault(layer, BigDecimal.ZERO);
			targets.put(layer, weight.multiply(total));
		}
		return targets;
	}

	private Map<Integer, BigDecimal> roundLayerTargets(Map<Integer, BigDecimal> desiredTargets, BigDecimal total) {
		BigDecimal totalRounded = total.setScale(0, RoundingMode.HALF_UP);
		Map<Integer, BigDecimal> rounded = new HashMap<>();
		Map<Integer, BigDecimal> fractions = new HashMap<>();
		BigDecimal sum = BigDecimal.ZERO;

		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal desired = desiredTargets.getOrDefault(layer, BigDecimal.ZERO);
			if (desired.signum() < 0) {
				desired = BigDecimal.ZERO;
			}
			BigDecimal ceil = desired.setScale(0, RoundingMode.CEILING);
			rounded.put(layer, ceil);
			fractions.put(layer, desired.subtract(desired.setScale(0, RoundingMode.FLOOR)));
			sum = sum.add(ceil);
		}

		int diff = sum.subtract(totalRounded).intValue();
		if (diff > 0) {
			List<Integer> layers = sortLayersByFraction(fractions, true);
			int index = 0;
			while (diff > 0 && !layers.isEmpty()) {
				int layer = layers.get(index % layers.size());
				BigDecimal current = rounded.getOrDefault(layer, BigDecimal.ZERO);
				if (current.signum() > 0) {
					rounded.put(layer, current.subtract(BigDecimal.ONE));
					diff -= 1;
				}
				index++;
			}
		} else if (diff < 0) {
			List<Integer> layers = sortLayersByFraction(fractions, false);
			int index = 0;
			while (diff < 0 && !layers.isEmpty()) {
				int layer = layers.get(index % layers.size());
				rounded.put(layer, rounded.getOrDefault(layer, BigDecimal.ZERO).add(BigDecimal.ONE));
				diff += 1;
				index++;
			}
		}
		return rounded;
	}

	private List<Integer> sortLayersByFraction(Map<Integer, BigDecimal> fractions, boolean ascending) {
		List<Integer> layers = new ArrayList<>(fractions.keySet());
		layers.sort((left, right) -> {
			int cmp = fractions.getOrDefault(left, BigDecimal.ZERO)
					.compareTo(fractions.getOrDefault(right, BigDecimal.ZERO));
			if (cmp == 0) {
				cmp = Integer.compare(left, right);
			}
			return ascending ? cmp : -cmp;
		});
		return layers;
	}

	private LayerTargetConfig parseLayerTargetConfig(ObjectMapper mapper, List<Map<String, Object>> configRows) throws IOException {
		if (configRows.isEmpty()) {
			throw new IllegalStateException("No layer target config data found in backup.");
		}
		Map<String, Object> row = configRows.get(0);
		Object configJson = row.get("config_json");
		if (!(configJson instanceof Map)) {
			throw new IllegalStateException("Missing layer target config JSON.");
		}
		String configValue = String.valueOf(((Map<?, ?>) configJson).get("value"));
		Map<String, Object> parsed = mapper.readValue(configValue, new TypeReference<>() {
		});
		String activeProfile = String.valueOf(parsed.get("active_profile"));
		Object profilesRaw = parsed.get("profiles");
		if (!(profilesRaw instanceof Map)) {
			throw new IllegalStateException("Missing profiles in layer target config.");
		}
		Object profileRaw = ((Map<?, ?>) profilesRaw).get(activeProfile);
		if (!(profileRaw instanceof Map)) {
			throw new IllegalStateException("Missing active profile in layer target config.");
		}
		Map<?, ?> profile = (Map<?, ?>) profileRaw;
		int minSavingPlanSize = ((Number) profile.get("minimum_saving_plan_size")).intValue();
		int minRebalancingAmount = ((Number) profile.get("minimum_rebalancing_amount")).intValue();
		Object targetsRaw = profile.get("layer_targets");
		if (!(targetsRaw instanceof Map)) {
			throw new IllegalStateException("Missing layer targets in layer target config.");
		}
		Map<Integer, BigDecimal> targets = new HashMap<>();
		for (Map.Entry<?, ?> entry : ((Map<?, ?>) targetsRaw).entrySet()) {
			Integer layer = Integer.valueOf(String.valueOf(entry.getKey()));
			BigDecimal weight = new BigDecimal(String.valueOf(entry.getValue()));
			targets.put(layer, weight);
		}
		return new LayerTargetConfig(targets, minSavingPlanSize, minRebalancingAmount);
	}

	private Map<String, byte[]> loadBackupEntries() throws IOException {
		Map<String, byte[]> entries = new HashMap<>();
		try (InputStream input = InstrumentRebalanceServiceTest.class.getResourceAsStream("/backup/database-backup_saving_plan_proposals.zip")) {
			if (input == null) {
				throw new IllegalStateException("Backup zip not found in test resources.");
			}
			try (ZipInputStream zip = new ZipInputStream(input)) {
				ZipEntry entry;
				while ((entry = zip.getNextEntry()) != null) {
					ByteArrayOutputStream output = new ByteArrayOutputStream();
					byte[] buffer = new byte[4096];
					int read;
					while ((read = zip.read(buffer)) > 0) {
						output.write(buffer, 0, read);
					}
					entries.put(entry.getName(), output.toByteArray());
				}
			}
		}
		return entries;
	}

	private record ExtractionRow(String isin, String status, String json) {
	}

	private record LayerTargetConfig(Map<Integer, BigDecimal> layerTargets,
									 int minimumSavingPlanSize,
									 int minimumRebalancingAmount) {
	}

	private record BackupFixture(List<SavingPlanInstrument> instruments,
								 Map<Integer, BigDecimal> layerBudgets,
								 int minimumSavingPlanSize,
								 int minimumRebalancingAmount,
								 Map<String, ExtractionRow> extractions,
								 BigDecimal monthlyTotal) {
	}
}
