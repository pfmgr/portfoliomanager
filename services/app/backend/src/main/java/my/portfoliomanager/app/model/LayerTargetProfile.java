package my.portfoliomanager.app.model;

import java.math.BigDecimal;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LayerTargetProfile {
	private final String key;
	private final String displayName;
	private final String description;
	private final Map<Integer, BigDecimal> layerTargets;
	private final BigDecimal acceptableVariancePct;
	private final Integer minimumSavingPlanSize;
	private final Integer minimumRebalancingAmount;
	private final Integer projectionHorizonMonths;
	private final BigDecimal projectionBlendMin;
	private final BigDecimal projectionBlendMax;
	private final Map<String, BigDecimal> constraints;
	private final Map<Integer, LayerTargetRiskThresholds> riskThresholdsByLayer;
	private final LayerTargetRiskThresholds riskThresholds;
}
