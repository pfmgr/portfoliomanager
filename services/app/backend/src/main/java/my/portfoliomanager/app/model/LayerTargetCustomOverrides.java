package my.portfoliomanager.app.model;

import java.math.BigDecimal;
import java.util.Map;

public class LayerTargetCustomOverrides {
	private final boolean enabled;
	private final Map<Integer, BigDecimal> layerTargets;
	private final BigDecimal acceptableVariancePct;
	private final Integer minimumSavingPlanSize;
	private final Integer minimumRebalancingAmount;
	private final String legacyImport;

	public LayerTargetCustomOverrides(boolean enabled,
									  Map<Integer, BigDecimal> layerTargets,
									  BigDecimal acceptableVariancePct,
									  Integer minimumSavingPlanSize,
									  Integer minimumRebalancingAmount,
									  String legacyImport) {
		this.enabled = enabled;
		this.layerTargets = layerTargets;
		this.acceptableVariancePct = acceptableVariancePct;
		this.minimumSavingPlanSize = minimumSavingPlanSize;
		this.minimumRebalancingAmount = minimumRebalancingAmount;
		this.legacyImport = legacyImport;
	}

	public boolean isEnabled() {
		return enabled;
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

	public String getLegacyImport() {
		return legacyImport;
	}
}
