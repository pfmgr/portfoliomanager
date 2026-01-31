package my.portfoliomanager.app.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Getter;

@Getter
public class LayerTargetConfigResponseDto {
	private final String activeProfileKey;
	private final String activeProfileDisplayName;
	private final String activeProfileDescription;
	private final Map<String, LayerTargetProfileDto> profiles;
	private final Map<String, LayerTargetProfileDto> seedProfiles;
	private final Map<Integer, Double> effectiveLayerTargets;
	private final Double acceptableVariancePct;
	private final Integer minimumSavingPlanSize;
	private final Integer minimumRebalancingAmount;
	private final Map<Integer, String> layerNames;
	private final Map<Integer, Integer> maxSavingPlansPerLayer;
	private final boolean customOverridesEnabled;
	private final Map<Integer, Double> customLayerTargets;
	private final Double customAcceptableVariancePct;
	private final Integer customMinimumSavingPlanSize;
	private final Integer customMinimumRebalancingAmount;
	private final OffsetDateTime updatedAt;

	public LayerTargetConfigResponseDto(String activeProfileKey,
									  String activeProfileDisplayName,
									  String activeProfileDescription,
									  Map<String, LayerTargetProfileDto> profiles,
									  Map<String, LayerTargetProfileDto> seedProfiles,
									  Map<Integer, Double> effectiveLayerTargets,
									  Double acceptableVariancePct,
									  Integer minimumSavingPlanSize,
									  Integer minimumRebalancingAmount,
									  Map<Integer, String> layerNames,
									  Map<Integer, Integer> maxSavingPlansPerLayer,
									  boolean customOverridesEnabled,
									  Map<Integer, Double> customLayerTargets,
									  Double customAcceptableVariancePct,
									  Integer customMinimumSavingPlanSize,
									  Integer customMinimumRebalancingAmount,
									  OffsetDateTime updatedAt) {
		this.activeProfileKey = activeProfileKey;
		this.activeProfileDisplayName = activeProfileDisplayName;
		this.activeProfileDescription = activeProfileDescription;
		this.profiles = profiles;
		this.seedProfiles = seedProfiles;
		this.effectiveLayerTargets = effectiveLayerTargets;
		this.acceptableVariancePct = acceptableVariancePct;
		this.minimumSavingPlanSize = minimumSavingPlanSize;
		this.minimumRebalancingAmount = minimumRebalancingAmount;
		this.layerNames = layerNames;
		this.maxSavingPlansPerLayer = maxSavingPlansPerLayer;
		this.customOverridesEnabled = customOverridesEnabled;
		this.customLayerTargets = customLayerTargets;
		this.customAcceptableVariancePct = customAcceptableVariancePct;
		this.customMinimumSavingPlanSize = customMinimumSavingPlanSize;
		this.customMinimumRebalancingAmount = customMinimumRebalancingAmount;
		this.updatedAt = updatedAt;
	}

	@Getter
	public static class LayerTargetProfileDto {
		private final String displayName;
		private final String description;
		private final Map<Integer, Double> layerTargets;
		private final Double acceptableVariancePct;
		private final Integer minimumSavingPlanSize;
		private final Integer minimumRebalancingAmount;
		private final Map<String, Double> constraints;
		private final LayerTargetRiskThresholdsDto riskThresholds;

		public LayerTargetProfileDto(String displayName,
							 String description,
							 Map<Integer, Double> layerTargets,
							 Double acceptableVariancePct,
							 Integer minimumSavingPlanSize,
							 Integer minimumRebalancingAmount,
							 Map<String, Double> constraints,
							 LayerTargetRiskThresholdsDto riskThresholds) {
			this.displayName = displayName;
			this.description = description;
			this.layerTargets = layerTargets;
			this.acceptableVariancePct = acceptableVariancePct;
			this.minimumSavingPlanSize = minimumSavingPlanSize;
			this.minimumRebalancingAmount = minimumRebalancingAmount;
			this.constraints = constraints;
			this.riskThresholds = riskThresholds;
		}

	}
}
