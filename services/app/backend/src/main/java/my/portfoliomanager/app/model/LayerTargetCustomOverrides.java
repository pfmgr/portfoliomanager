package my.portfoliomanager.app.model;

import java.math.BigDecimal;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LayerTargetCustomOverrides {
	private final boolean enabled;
	private final Map<Integer, BigDecimal> layerTargets;
	private final BigDecimal acceptableVariancePct;
	private final Integer minimumSavingPlanSize;
	private final Integer minimumRebalancingAmount;
	private final String legacyImport;
}
