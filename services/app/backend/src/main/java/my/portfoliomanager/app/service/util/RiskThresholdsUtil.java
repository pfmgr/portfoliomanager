package my.portfoliomanager.app.service.util;

import my.portfoliomanager.app.model.LayerTargetRiskThresholds;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RiskThresholdsUtil {
	public static final double DEFAULT_LOW_MAX = 30.0;
	public static final double DEFAULT_HIGH_MIN = 51.0;
	private static final double MIN_GAP = 0.1;

	private RiskThresholdsUtil() {
	}

	public static LayerTargetRiskThresholds normalize(LayerTargetRiskThresholds thresholds) {
		return normalize(thresholds, DEFAULT_LOW_MAX, DEFAULT_HIGH_MIN);
	}

	public static LayerTargetRiskThresholds normalize(LayerTargetRiskThresholds thresholds,
								   LayerTargetRiskThresholds fallback) {
		double fallbackLow = fallback == null ? DEFAULT_LOW_MAX : clamp(fallback.getLowMax(), DEFAULT_LOW_MAX);
		double fallbackHigh = fallback == null ? DEFAULT_HIGH_MIN : clamp(fallback.getHighMin(), DEFAULT_HIGH_MIN);
		return normalize(thresholds, fallbackLow, fallbackHigh);
	}

	public static LayerTargetRiskThresholds normalize(LayerTargetRiskThresholds thresholds, double fallbackLow, double fallbackHigh) {
		double lowMax = clamp(thresholds == null ? null : thresholds.getLowMax(), fallbackLow);
		double highMin = clamp(thresholds == null ? null : thresholds.getHighMin(), fallbackHigh);
		if (highMin <= lowMax) {
			highMin = Math.min(100.0, lowMax + MIN_GAP);
			if (highMin <= lowMax) {
				lowMax = Math.max(0.0, highMin - MIN_GAP);
			}
		}
		return new LayerTargetRiskThresholds(lowMax, highMin);
	}

	public static double clamp(Double value, double fallback) {
		double resolved = value == null ? fallback : value;
		if (resolved < 0.0) {
			return 0.0;
		}
		if (resolved > 100.0) {
			return 100.0;
		}
		return resolved;
	}

	public static Map<Integer, LayerTargetRiskThresholds> normalizeByLayer(
			Map<Integer, LayerTargetRiskThresholds> thresholdsByLayer,
			LayerTargetRiskThresholds fallback
	) {
		Map<Integer, LayerTargetRiskThresholds> normalized = new LinkedHashMap<>();
		LayerTargetRiskThresholds fallbackNormalized = normalize(fallback);
		for (int layer = 1; layer <= 5; layer++) {
			LayerTargetRiskThresholds raw = thresholdsByLayer == null ? null : thresholdsByLayer.get(layer);
			normalized.put(layer, normalize(raw, fallbackNormalized));
		}
		return Map.copyOf(normalized);
	}

	public static LayerTargetRiskThresholds resolveForLayer(
			Map<Integer, LayerTargetRiskThresholds> thresholdsByLayer,
			LayerTargetRiskThresholds fallback,
			Integer layer
	) {
		if (layer != null && thresholdsByLayer != null && thresholdsByLayer.containsKey(layer)) {
			return normalize(thresholdsByLayer.get(layer), fallback);
		}
		return normalize(fallback);
	}
}
