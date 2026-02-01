package my.portfoliomanager.app.service.util;

import my.portfoliomanager.app.model.LayerTargetRiskThresholds;

public final class RiskThresholdsUtil {
	public static final int DEFAULT_LOW_MAX = 30;
	public static final int DEFAULT_HIGH_MIN = 51;

	private RiskThresholdsUtil() {
	}

	public static LayerTargetRiskThresholds normalize(LayerTargetRiskThresholds thresholds) {
		return normalize(thresholds, DEFAULT_LOW_MAX, DEFAULT_HIGH_MIN);
	}

	public static LayerTargetRiskThresholds normalize(LayerTargetRiskThresholds thresholds,
									   LayerTargetRiskThresholds fallback) {
		int fallbackLow = fallback == null ? DEFAULT_LOW_MAX : clamp(fallback.getLowMax(), DEFAULT_LOW_MAX);
		int fallbackHigh = fallback == null ? DEFAULT_HIGH_MIN : clamp(fallback.getHighMin(), DEFAULT_HIGH_MIN);
		return normalize(thresholds, fallbackLow, fallbackHigh);
	}

	public static LayerTargetRiskThresholds normalize(LayerTargetRiskThresholds thresholds, int fallbackLow, int fallbackHigh) {
		int lowMax = clamp(thresholds == null ? null : thresholds.getLowMax(), fallbackLow);
		int highMin = clamp(thresholds == null ? null : thresholds.getHighMin(), fallbackHigh);
		if (highMin <= lowMax) {
			highMin = Math.min(100, lowMax + 1);
			if (highMin <= lowMax) {
				lowMax = Math.max(0, highMin - 1);
			}
		}
		return new LayerTargetRiskThresholds(lowMax, highMin);
	}

	public static int clamp(Integer value, int fallback) {
		int resolved = value == null ? fallback : value;
		if (resolved < 0) {
			return 0;
		}
		if (resolved > 100) {
			return 100;
		}
		return resolved;
	}
}
