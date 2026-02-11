
package my.portfoliomanager.app.service;

import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseQualityGateServiceTest {
	private final KnowledgeBaseQualityGateService service = new KnowledgeBaseQualityGateService();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void evaluateDossier_acceptsBoldIsinHeaderAndBulletSections() {
		String content = "# **DE000DK2CDS0 - Sample**\n"
				+ "## Quick profile (table)\n"
				+ "instrument_type: ETF\n"
				+ "## Classification\n"
				+ "- Risk\n"
				+ "## Costs & structure\n"
				+ "- Exposures\n"
				+ "## Valuation & profitability\n"
				+ "## Sources\n"
				+ "1) https://example.com\n";

		KnowledgeBaseQualityGateService.DossierQualityResult result = service.evaluateDossier(
				"DE000DK2CDS0",
				content,
				buildCitations(),
				null
		);

		assertThat(result.reasons()).doesNotContain(
				"missing_isin_header",
				"missing_section:risk",
				"missing_section:exposures"
		);
	}

	@Test
	void evaluateDossier_acceptsLayerNotesAndReferencesHeading() {
		String content = "# IE00BC7GZW19 - Sample\n"
				+ "## Quick profile\n"
				+ "instrument_type: ETF\n"
				+ "## Layer notes\n"
				+ "## Risk\n"
				+ "## Costs and structure\n"
				+ "## Exposures\n"
				+ "## Valuation & profitability\n"
				+ "## References\n"
				+ "1) https://example.com\n";

		KnowledgeBaseQualityGateService.DossierQualityResult result = service.evaluateDossier(
				"IE00BC7GZW19",
				content,
				buildCitations(),
				null
		);

		assertThat(result.reasons()).doesNotContain(
				"missing_section:classification",
				"missing_section:sources"
		);
	}

	@Test
	void evaluateExtractionEvidence_fundProfile_requiresFundFieldsAndHoldings() {
		InstrumentDossierExtractionPayload payload = payload(
				"IE00B4L5Y983",
				"ETF",
				etf(new BigDecimal("0.12"), "MSCI World"),
				risk(4),
				null,
				valuation(
						new BigDecimal("100"),
						new BigDecimal("20"),
						new BigDecimal("2"),
						new BigDecimal("1000000000"),
						null,
						null,
						null,
						null,
						null,
						new BigDecimal("18"),
						new BigDecimal("0.055"),
						new BigDecimal("80"),
						500,
						"2024-12-31",
						null
				)
		);
		String dossierContent = "instrument_type: ETF";

		KnowledgeBaseQualityGateService.EvidenceResult result = service.evaluateExtractionEvidence(dossierContent, payload, null);

		assertThat(result.missingEvidence()).contains(
				"benchmark_index",
				"ongoing_charges_pct",
				"sri",
				"price",
				"pe_current",
				"pb_current",
				"pe_ttm_holdings",
				"earnings_yield_ttm_holdings",
				"holdings_coverage_weight_pct",
				"holdings_coverage_count",
				"holdings_asof"
		);
		assertThat(result.missingEvidence()).doesNotContain(
				"market_cap",
				"revenue",
				"net_income",
				"ffo"
		);
	}

	@Test
	void evaluateExtractionEvidence_equityProfile_skipsFundFieldsAndMarketCap() {
		InstrumentDossierExtractionPayload payload = payload(
				"US0378331005",
				"Equity",
				null,
				null,
				financials(new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("1.2")),
				valuation(
						new BigDecimal("150"),
						new BigDecimal("25"),
						new BigDecimal("5"),
						new BigDecimal("1000000000"),
						new BigDecimal("200"),
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						List.of(epsHistory(2023, new BigDecimal("5")))
				)
		);
		String dossierContent = "instrument_type: Equity";

		KnowledgeBaseQualityGateService.EvidenceResult result = service.evaluateExtractionEvidence(dossierContent, payload, null);

		assertThat(result.missingEvidence()).contains(
				"price",
				"pb_current",
				"dividend_per_share",
				"revenue",
				"net_income",
				"ebitda",
				"eps_history"
		);
		assertThat(result.missingEvidence()).doesNotContain(
				"benchmark_index",
				"ongoing_charges_pct",
				"sri",
				"pe_ttm_holdings",
				"market_cap",
				"holdings_asof"
		);
	}

	@Test
	void evaluateExtractionEvidence_equityScaledNumbersMatch() {
		InstrumentDossierExtractionPayload payload = payload(
				"DE0007030009",
				"Equity",
				null,
				null,
				financials(
						new BigDecimal("11000000000"),
						new BigDecimal("840000000"),
						new BigDecimal("8.10")
				),
				valuation(
						new BigDecimal("1628.00"),
						new BigDecimal("88.98"),
						new BigDecimal("14.51"),
						new BigDecimal("74740000000"),
						new BigDecimal("1920000000"),
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						List.of(
							epsHistory(2024, new BigDecimal("16.51")),
							epsHistory(2023, new BigDecimal("12.32"))
						)
				)
		);
		String dossierContent = "revenue: 11.00 B EUR (TTM)\n"
				+ "net_income: 840.00 M EUR (TTM)\n"
				+ "ebitda: 1.92 B EUR (TTM)\n"
				+ "price: 1628.00 EUR\n"
				+ "pe_current: 88.98\n"
				+ "pb_current: 14.51\n"
				+ "dividend_per_share: 8.10 EUR\n"
				+ "eps_history:\n"
				+ "- 2024: 16.51 EUR\n"
				+ "- 2023: 12.32 EUR\n";

		KnowledgeBaseQualityGateService.EvidenceResult result =
				service.evaluateExtractionEvidence(dossierContent, payload, null);

		assertThat(result.missingEvidence()).doesNotContain(
				"revenue",
				"net_income",
				"ebitda"
		);
	}

	@Test
	void evaluateExtractionEvidence_reitProfile_requiresReitFields() {
		InstrumentDossierExtractionPayload payload = payload(
				"US8288071029",
				"REIT",
				null,
				null,
				financials(new BigDecimal("500"), new BigDecimal("80"), new BigDecimal("1.0")),
				valuation(
						new BigDecimal("80"),
						new BigDecimal("18"),
						new BigDecimal("1.8"),
						null,
						new BigDecimal("150"),
						new BigDecimal("120"),
						new BigDecimal("90"),
						new BigDecimal("70"),
						new BigDecimal("65"),
						null,
						null,
						null,
						null,
						null,
						null
				)
		);
		String dossierContent = "instrument_type: REIT";

		KnowledgeBaseQualityGateService.EvidenceResult result = service.evaluateExtractionEvidence(dossierContent, payload, null);

		assertThat(result.missingEvidence()).contains(
				"net_rent",
				"noi",
				"affo",
				"ffo"
		);
		assertThat(result.missingEvidence()).doesNotContain(
				"benchmark_index",
				"ongoing_charges_pct",
				"sri"
		);
	}

	@Test
	void evaluateExtractionEvidence_unknownProfile_limitsChecksToCoreValuation() {
		InstrumentDossierExtractionPayload payload = payload(
				"ZZ0000000000",
				null,
				null,
				null,
				financials(new BigDecimal("900"), new BigDecimal("60"), null),
				valuation(
						new BigDecimal("10"),
						new BigDecimal("15"),
						new BigDecimal("1"),
						new BigDecimal("500"),
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null
				)
		);
		String dossierContent = "instrument_type: unknown";

		KnowledgeBaseQualityGateService.EvidenceResult result = service.evaluateExtractionEvidence(dossierContent, payload, null);

		assertThat(result.missingEvidence()).contains("price", "pe_current", "pb_current");
		assertThat(result.missingEvidence()).doesNotContain(
				"benchmark_index",
				"ongoing_charges_pct",
				"sri",
				"revenue",
				"net_income",
				"market_cap"
		);
	}

	@Test
	void evaluateExtractionEvidence_layerFallback_resolvesFund() {
		InstrumentDossierExtractionPayload payload = payloadWithLayer(
				"LU1111111111",
				null,
				2,
				null,
				null,
				null,
				valuation(
						new BigDecimal("120"),
						new BigDecimal("18"),
						new BigDecimal("2"),
						null,
						null,
						null,
						null,
						null,
						null,
						new BigDecimal("16"),
						new BigDecimal("0.062"),
						new BigDecimal("75"),
						250,
						"2024-12-31",
						null
				)
		);
		String dossierContent = "layer: 2";

		KnowledgeBaseQualityGateService.EvidenceResult result = service.evaluateExtractionEvidence(dossierContent, payload, null);

		assertThat(result.missingEvidence()).contains(
				"pe_ttm_holdings",
				"earnings_yield_ttm_holdings",
				"holdings_coverage_weight_pct",
				"holdings_coverage_count",
				"holdings_asof"
		);
		assertThat(result.missingEvidence()).doesNotContain(
				"revenue",
				"net_income",
				"dividend_per_share"
		);
	}

	@Test
	void evaluateExtractionEvidence_layerFallback_resolvesEquity() {
		InstrumentDossierExtractionPayload payload = payloadWithLayer(
				"US1111111111",
				null,
				4,
				null,
				null,
				financials(new BigDecimal("200"), new BigDecimal("40"), new BigDecimal("0.5")),
				valuation(
						new BigDecimal("50"),
						new BigDecimal("12"),
						new BigDecimal("1.4"),
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null
				)
		);
		String dossierContent = "layer: 4";

		KnowledgeBaseQualityGateService.EvidenceResult result = service.evaluateExtractionEvidence(dossierContent, payload, null);

		assertThat(result.missingEvidence()).contains(
				"revenue",
				"net_income",
				"dividend_per_share"
		);
		assertThat(result.missingEvidence()).doesNotContain(
				"pe_ttm_holdings",
				"holdings_asof",
				"benchmark_index"
		);
	}

	private InstrumentDossierExtractionPayload payload(
			String isin,
			String instrumentType,
			InstrumentDossierExtractionPayload.EtfPayload etf,
			InstrumentDossierExtractionPayload.RiskPayload risk,
			InstrumentDossierExtractionPayload.FinancialsPayload financials,
			InstrumentDossierExtractionPayload.ValuationPayload valuation
	) {
		return payloadWithLayer(isin, instrumentType, null, etf, risk, financials, valuation);
	}

	private ArrayNode buildCitations() {
		ArrayNode citations = objectMapper.createArrayNode();
		ObjectNode entry = objectMapper.createObjectNode();
		entry.put("id", "1");
		entry.put("title", "Example");
		entry.put("url", "https://example.com");
		entry.put("publisher", "Example");
		entry.put("accessed_at", "2026-02-08");
		citations.add(entry);
		return citations;
	}

	private InstrumentDossierExtractionPayload payloadWithLayer(
			String isin,
			String instrumentType,
			Integer layer,
			InstrumentDossierExtractionPayload.EtfPayload etf,
			InstrumentDossierExtractionPayload.RiskPayload risk,
			InstrumentDossierExtractionPayload.FinancialsPayload financials,
			InstrumentDossierExtractionPayload.ValuationPayload valuation
	) {
		return new InstrumentDossierExtractionPayload(
				isin,
				"Sample",
				instrumentType,
				null,
				null,
				null,
				null,
				null,
				null,
				layer,
				null,
				etf,
				risk,
				null,
				null,
				null,
				financials,
				valuation,
				null,
				null,
				null
		);
	}

	private InstrumentDossierExtractionPayload.ValuationPayload valuation(
			BigDecimal price,
			BigDecimal peCurrent,
			BigDecimal pbCurrent,
			BigDecimal marketCap,
			BigDecimal ebitda,
			BigDecimal netRent,
			BigDecimal noi,
			BigDecimal affo,
			BigDecimal ffo,
			BigDecimal peTtmHoldings,
			BigDecimal earningsYieldTtmHoldings,
			BigDecimal holdingsCoverageWeightPct,
			Integer holdingsCoverageCount,
			String holdingsAsOf,
			List<InstrumentDossierExtractionPayload.EpsHistoryPayload> epsHistory
	) {
		return new InstrumentDossierExtractionPayload.ValuationPayload(
				ebitda,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				marketCap,
				null,
				null,
				netRent,
				null,
				null,
				null,
				noi,
				null,
				null,
				null,
				affo,
				null,
				null,
				null,
				ffo,
				null,
				null,
				null,
				null,
				price,
				null,
				null,
				null,
				null,
				null,
				null,
				epsHistory,
				null,
				null,
				null,
				null,
				null,
				peCurrent,
				null,
				pbCurrent,
				null,
				peTtmHoldings,
				earningsYieldTtmHoldings,
				holdingsCoverageWeightPct,
				holdingsCoverageCount,
				holdingsAsOf,
				null,
				null,
				null,
				null
		);
	}

	private InstrumentDossierExtractionPayload.FinancialsPayload financials(
			BigDecimal revenue,
			BigDecimal netIncome,
			BigDecimal dividendPerShare
	) {
		return new InstrumentDossierExtractionPayload.FinancialsPayload(
				revenue,
				null,
				null,
				null,
				null,
				netIncome,
				null,
				null,
				null,
				null,
				dividendPerShare,
				null,
				null,
				null
		);
	}

	private InstrumentDossierExtractionPayload.EtfPayload etf(BigDecimal ongoingChargesPct, String benchmarkIndex) {
		return new InstrumentDossierExtractionPayload.EtfPayload(ongoingChargesPct, benchmarkIndex);
	}

	private InstrumentDossierExtractionPayload.RiskPayload risk(int sri) {
		return new InstrumentDossierExtractionPayload.RiskPayload(
				new InstrumentDossierExtractionPayload.SummaryRiskIndicatorPayload(sri),
				null
		);
	}

	private InstrumentDossierExtractionPayload.EpsHistoryPayload epsHistory(int year, BigDecimal eps) {
		return new InstrumentDossierExtractionPayload.EpsHistoryPayload(year, eps, null, null, null);
	}
}
