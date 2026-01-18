package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Instrument-level saving plan proposal.")
public class InstrumentProposalDto {
	@Schema(description = "Instrument ISIN.")
	private final String isin;
	@Schema(description = "Effective instrument name.")
	private final String instrumentName;
	@Schema(description = "Current monthly amount in EUR.")
	private final Double currentAmountEur;
	@Schema(description = "Proposed monthly amount in EUR.")
	private final Double proposedAmountEur;
	@Schema(description = "Delta between proposed and current monthly amount in EUR.")
	private final Double deltaEur;
	@Schema(description = "Effective layer of the instrument (1-5).")
	private final Integer layer;
	@Schema(description = "Reason codes for the proposal decision.")
	private final List<String> reasonCodes;

	@JsonCreator
	public InstrumentProposalDto(@JsonProperty("isin") String isin,
								 @JsonProperty("instrumentName") String instrumentName,
								 @JsonProperty("currentAmountEur") Double currentAmountEur,
								 @JsonProperty("proposedAmountEur") Double proposedAmountEur,
								 @JsonProperty("deltaEur") Double deltaEur,
								 @JsonProperty("layer") Integer layer,
								 @JsonProperty("reasonCodes") List<String> reasonCodes) {
		this.isin = isin;
		this.instrumentName = instrumentName;
		this.currentAmountEur = currentAmountEur;
		this.proposedAmountEur = proposedAmountEur;
		this.deltaEur = deltaEur;
		this.layer = layer;
		this.reasonCodes = reasonCodes;
	}

	public String getIsin() {
		return isin;
	}

	public String getInstrumentName() {
		return instrumentName;
	}

	public Double getCurrentAmountEur() {
		return currentAmountEur;
	}

	public Double getProposedAmountEur() {
		return proposedAmountEur;
	}

	public Double getDeltaEur() {
		return deltaEur;
	}

	public Integer getLayer() {
		return layer;
	}

	public List<String> getReasonCodes() {
		return reasonCodes;
	}
}
