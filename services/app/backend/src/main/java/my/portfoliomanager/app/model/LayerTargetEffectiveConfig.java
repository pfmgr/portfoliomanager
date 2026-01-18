package my.portfoliomanager.app.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

public record LayerTargetEffectiveConfig(String selectedProfileKey,
										 LayerTargetProfile selectedProfile,
										 Map<Integer, BigDecimal> effectiveLayerTargets,
										 BigDecimal acceptableVariancePct,
										 Integer minimumSavingPlanSize,
										 Integer minimumRebalancingAmount,
										 boolean customOverridesActive,
										 Map<Integer, String> layerNames,
										 OffsetDateTime updatedAt) {
}
