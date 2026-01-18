package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SavingPlanProposalDto {
	private final Double totalMonthlyAmountEur;
	private final Double targetWeightTotalPct;
	private final String source;
	private final String narrative;
	private final List<String> notes;
	private final List<SavingPlanProposalLayerDto> layers;
	private final Map<Integer, Double> actualDistributionByLayer;
	private final Map<Integer, Double> targetDistributionByLayer;
	private final Map<Integer, Double> proposedDistributionByLayer;
	@Schema(description = "Layer-level proposal budgets in EUR.")
	private final Map<Integer, Double> layerBudgets;
	@Schema(description = "Instrument-level saving plan proposals.")
	private final List<InstrumentProposalDto> instrumentProposals;
	@Schema(description = "Instrument-level warnings from proposal generation.")
	private final List<String> instrumentWarnings;
	@Schema(description = "Instrument-level warning codes for filtering/sorting.")
	private final List<String> instrumentWarningCodes;
	@Schema(description = "Knowledge Base gating status for instrument proposals.")
	private final InstrumentProposalGatingDto gating;
	private final Map<Integer, Double> deviationsByLayer;
	private final boolean withinTolerance;
	private final List<ConstraintResultDto> constraints;
	private final String recommendation;
	private final String selectedProfileKey;
	private final String selectedProfileDisplayName;

	@JsonCreator
	public SavingPlanProposalDto(@JsonProperty("totalMonthlyAmountEur") Double totalMonthlyAmountEur,
							   @JsonProperty("targetWeightTotalPct") Double targetWeightTotalPct,
							   @JsonProperty("source") String source,
							   @JsonProperty("narrative") String narrative,
							   @JsonProperty("notes") List<String> notes,
							   @JsonProperty("layers") List<SavingPlanProposalLayerDto> layers,
							   @JsonProperty("actualDistributionByLayer") Map<Integer, Double> actualDistributionByLayer,
							   @JsonProperty("targetDistributionByLayer") Map<Integer, Double> targetDistributionByLayer,
							   @JsonProperty("proposedDistributionByLayer") Map<Integer, Double> proposedDistributionByLayer,
							   @JsonProperty("layerBudgets") Map<Integer, Double> layerBudgets,
							   @JsonProperty("instrumentProposals") List<InstrumentProposalDto> instrumentProposals,
							   @JsonProperty("instrumentWarnings") List<String> instrumentWarnings,
							   @JsonProperty("instrumentWarningCodes") List<String> instrumentWarningCodes,
							   @JsonProperty("gating") InstrumentProposalGatingDto gating,
							   @JsonProperty("deviationsByLayer") Map<Integer, Double> deviationsByLayer,
							   @JsonProperty("withinTolerance") boolean withinTolerance,
							   @JsonProperty("constraints") List<ConstraintResultDto> constraints,
							   @JsonProperty("recommendation") String recommendation,
							   @JsonProperty("selectedProfileKey") String selectedProfileKey,
							   @JsonProperty("selectedProfileDisplayName") String selectedProfileDisplayName) {
		this.totalMonthlyAmountEur = totalMonthlyAmountEur;
		this.targetWeightTotalPct = targetWeightTotalPct;
		this.source = source;
		this.narrative = narrative;
		this.notes = notes;
		this.layers = layers;
		this.actualDistributionByLayer = actualDistributionByLayer;
		this.targetDistributionByLayer = targetDistributionByLayer;
		this.proposedDistributionByLayer = proposedDistributionByLayer;
		this.layerBudgets = layerBudgets;
		this.instrumentProposals = instrumentProposals;
		this.instrumentWarnings = instrumentWarnings;
		this.instrumentWarningCodes = instrumentWarningCodes;
		this.gating = gating;
		this.deviationsByLayer = deviationsByLayer;
		this.withinTolerance = withinTolerance;
		this.constraints = constraints;
		this.recommendation = recommendation;
		this.selectedProfileKey = selectedProfileKey;
		this.selectedProfileDisplayName = selectedProfileDisplayName;
	}

	public Double getTotalMonthlyAmountEur() {
		return totalMonthlyAmountEur;
	}

	public Double getTargetWeightTotalPct() {
		return targetWeightTotalPct;
	}

	public String getSource() {
		return source;
	}

	public String getNarrative() {
		return narrative;
	}

	public List<String> getNotes() {
		return notes;
	}

	public List<SavingPlanProposalLayerDto> getLayers() {
		return layers;
	}

	public Map<Integer, Double> getActualDistributionByLayer() {
		return actualDistributionByLayer;
	}

	public Map<Integer, Double> getTargetDistributionByLayer() {
		return targetDistributionByLayer;
	}

	public Map<Integer, Double> getProposedDistributionByLayer() {
		return proposedDistributionByLayer;
	}

	public Map<Integer, Double> getLayerBudgets() {
		return layerBudgets;
	}

	public List<InstrumentProposalDto> getInstrumentProposals() {
		return instrumentProposals;
	}

	public List<String> getInstrumentWarnings() {
		return instrumentWarnings;
	}

	public List<String> getInstrumentWarningCodes() {
		return instrumentWarningCodes;
	}

	public InstrumentProposalGatingDto getGating() {
		return gating;
	}

	public Map<Integer, Double> getDeviationsByLayer() {
		return deviationsByLayer;
	}

	public boolean isWithinTolerance() {
		return withinTolerance;
	}

	public List<ConstraintResultDto> getConstraints() {
		return constraints;
	}

	public String getRecommendation() {
		return recommendation;
	}

	public String getSelectedProfileKey() {
		return selectedProfileKey;
	}

	public String getSelectedProfileDisplayName() {
		return selectedProfileDisplayName;
	}
}
