package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InstrumentDossierExtractionPayload(
		@JsonProperty("isin") String isin,
		@JsonProperty("name") String name,
		@JsonProperty("instrument_type") String instrumentType,
		@JsonProperty("asset_class") String assetClass,
		@JsonProperty("sub_class") String subClass,
		@JsonProperty("layer") Integer layer,
		@JsonProperty("layer_notes") String layerNotes,
		@JsonProperty("etf") EtfPayload etf,
		@JsonProperty("risk") RiskPayload risk,
		@JsonProperty("regions") List<RegionExposurePayload> regions,
		@JsonProperty("top_holdings") List<HoldingPayload> topHoldings,
		@JsonProperty("valuation") ValuationPayload valuation,
		@JsonProperty("sources") List<SourcePayload> sources,
		@JsonProperty("missing_fields") List<MissingFieldPayload> missingFields,
		@JsonProperty("warnings") List<WarningPayload> warnings
) {
	public InstrumentDossierExtractionPayload(String isin,
											  String name,
											  String instrumentType,
											  String assetClass,
											  String subClass,
											  Integer layer,
											  String layerNotes,
											  EtfPayload etf,
											  RiskPayload risk,
											  List<RegionExposurePayload> regions,
											  List<HoldingPayload> topHoldings,
											  List<SourcePayload> sources,
											  List<MissingFieldPayload> missingFields,
											  List<WarningPayload> warnings) {
		this(isin, name, instrumentType, assetClass, subClass, layer, layerNotes, etf, risk, regions, topHoldings,
				null, sources, missingFields, warnings);
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record EtfPayload(
			@JsonProperty("ongoing_charges_pct") BigDecimal ongoingChargesPct,
			@JsonProperty("benchmark_index") String benchmarkIndex
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RiskPayload(
			@JsonProperty("summary_risk_indicator") SummaryRiskIndicatorPayload summaryRiskIndicator
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SummaryRiskIndicatorPayload(
			@JsonProperty("value") Integer value
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RegionExposurePayload(
			@JsonProperty("name") String name,
			@JsonProperty("weight_pct") BigDecimal weightPct
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record HoldingPayload(
			@JsonProperty("name") String name,
			@JsonProperty("weight_pct") BigDecimal weightPct
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ValuationPayload(
			@JsonProperty("ebitda") BigDecimal ebitda,
			@JsonProperty("ebitda_currency") String ebitdaCurrency,
			@JsonProperty("ebitda_eur") BigDecimal ebitdaEur,
			@JsonProperty("fx_rate_to_eur") BigDecimal fxRateToEur,
			@JsonProperty("ebitda_period_end") String ebitdaPeriodEnd,
			@JsonProperty("ebitda_period_type") String ebitdaPeriodType,
			@JsonProperty("enterprise_value") BigDecimal enterpriseValue,
			@JsonProperty("net_debt") BigDecimal netDebt,
			@JsonProperty("market_cap") BigDecimal marketCap,
			@JsonProperty("shares_outstanding") BigDecimal sharesOutstanding,
			@JsonProperty("ev_to_ebitda") BigDecimal evToEbitda,
			@JsonProperty("price_asof") String priceAsOf,
			@JsonProperty("eps_type") String epsType,
			@JsonProperty("eps_norm") BigDecimal epsNorm,
			@JsonProperty("eps_norm_years_used") Integer epsNormYearsUsed,
			@JsonProperty("eps_norm_years_available") Integer epsNormYearsAvailable,
			@JsonProperty("eps_floor_policy") String epsFloorPolicy,
			@JsonProperty("eps_floor_value") BigDecimal epsFloorValue,
			@JsonProperty("eps_norm_period_end") String epsNormPeriodEnd,
			@JsonProperty("pe_longterm") BigDecimal peLongterm,
			@JsonProperty("earnings_yield_longterm") BigDecimal earningsYieldLongterm,
			@JsonProperty("pe_ttm_holdings") BigDecimal peTtmHoldings,
			@JsonProperty("earnings_yield_ttm_holdings") BigDecimal earningsYieldTtmHoldings,
			@JsonProperty("holdings_coverage_weight_pct") BigDecimal holdingsCoverageWeightPct,
			@JsonProperty("holdings_coverage_count") Integer holdingsCoverageCount,
			@JsonProperty("holdings_asof") String holdingsAsOf,
			@JsonProperty("holdings_weight_method") String holdingsWeightMethod,
			@JsonProperty("pe_method") String peMethod,
			@JsonProperty("pe_horizon") String peHorizon,
			@JsonProperty("neg_earnings_handling") String negEarningsHandling
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SourcePayload(
			@JsonProperty("id") String id,
			@JsonProperty("title") String title,
			@JsonProperty("url") String url,
			@JsonProperty("publisher") String publisher,
			@JsonProperty("accessed_at") String accessedAt
	) {
	}

	public record MissingFieldPayload(
			@JsonProperty("field") String field,
			@JsonProperty("reason") String reason
	) {
	}

	public record WarningPayload(
			@JsonProperty("message") String message
	) {
	}
}
