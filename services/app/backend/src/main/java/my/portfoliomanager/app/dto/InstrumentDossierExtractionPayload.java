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
		@JsonProperty("sources") List<SourcePayload> sources,
		@JsonProperty("missing_fields") List<MissingFieldPayload> missingFields,
		@JsonProperty("warnings") List<WarningPayload> warnings
) {
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
