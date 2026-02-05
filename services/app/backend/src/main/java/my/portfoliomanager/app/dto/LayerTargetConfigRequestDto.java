package my.portfoliomanager.app.dto;

import java.util.Map;

public record LayerTargetConfigRequestDto(String activeProfile,
						  Boolean customOverridesEnabled,
						  Map<Integer, Double> layerTargets,
						  Double acceptableVariancePct,
						  Integer minimumSavingPlanSize,
					  Integer minimumRebalancingAmount,
				  Map<Integer, Integer> maxSavingPlansPerLayer,
				  Map<String, LayerTargetRiskThresholdsDto> profileRiskThresholds,
				  Map<String, Map<Integer, LayerTargetRiskThresholdsDto>> profileRiskThresholdsByLayer,
				  Map<String, Integer> profileProjectionHorizonMonths,
				  Map<String, Double> profileProjectionBlendMin,
				  Map<String, Double> profileProjectionBlendMax) {
}
