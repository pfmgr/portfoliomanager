package my.portfoliomanager.app.model;

import java.math.BigDecimal;
import java.util.Map;

public class LayerTargetProfile {
	private final String key;
	private final String displayName;
	private final String description;
	private final Map<Integer, BigDecimal> layerTargets;
	private final BigDecimal acceptableVariancePct;
	private final Integer minimumSavingPlanSize;
	private final Integer minimumRebalancingAmount;
	private final Map<String, BigDecimal> constraints;

	public LayerTargetProfile(String key,
							  String displayName,
							  String description,
							  Map<Integer, BigDecimal> layerTargets,
							  BigDecimal acceptableVariancePct,
							  Integer minimumSavingPlanSize,
							  Integer minimumRebalancingAmount,
							  Map<String, BigDecimal> constraints) {
		this.key = key;
		this.displayName = displayName;
		this.description = description;
		this.layerTargets = layerTargets;
		this.acceptableVariancePct = acceptableVariancePct;
		this.minimumSavingPlanSize = minimumSavingPlanSize;
		this.minimumRebalancingAmount = minimumRebalancingAmount;
		this.constraints = constraints;
	}

	public String getKey() {
		return key;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getDescription() {
		return description;
	}

	public Map<Integer, BigDecimal> getLayerTargets() {
		return layerTargets;
	}

	public BigDecimal getAcceptableVariancePct() {
		return acceptableVariancePct;
	}

	public Integer getMinimumSavingPlanSize() {
		return minimumSavingPlanSize;
	}

	public Integer getMinimumRebalancingAmount() {
		return minimumRebalancingAmount;
	}

	public Map<String, BigDecimal> getConstraints() {
		return constraints;
	}
}
