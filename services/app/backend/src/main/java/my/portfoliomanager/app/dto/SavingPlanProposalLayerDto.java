package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SavingPlanProposalLayerDto {
	private final Integer layer;
	private final String layerName;
	private final Double currentAmountEur;
	private final Double currentWeightPct;
	private final Double targetWeightPct;
	private final Double targetAmountEur;
	private final Double deltaEur;
	private final Double targetTotalWeightPct;
	private final Double targetTotalAmountEur;

	@JsonCreator
	public SavingPlanProposalLayerDto(@JsonProperty("layer") Integer layer,
								@JsonProperty("layerName") String layerName,
								@JsonProperty("currentAmountEur") Double currentAmountEur,
								@JsonProperty("currentWeightPct") Double currentWeightPct,
								@JsonProperty("targetWeightPct") Double targetWeightPct,
								@JsonProperty("targetAmountEur") Double targetAmountEur,
								@JsonProperty("deltaEur") Double deltaEur,
								@JsonProperty("targetTotalWeightPct") Double targetTotalWeightPct,
								@JsonProperty("targetTotalAmountEur") Double targetTotalAmountEur) {
		this.layer = layer;
		this.layerName = layerName;
		this.currentAmountEur = currentAmountEur;
		this.currentWeightPct = currentWeightPct;
		this.targetWeightPct = targetWeightPct;
		this.targetAmountEur = targetAmountEur;
		this.deltaEur = deltaEur;
		this.targetTotalWeightPct = targetTotalWeightPct;
		this.targetTotalAmountEur = targetTotalAmountEur;
	}

}
