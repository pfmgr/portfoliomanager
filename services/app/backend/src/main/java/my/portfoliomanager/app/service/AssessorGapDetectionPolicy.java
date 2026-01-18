package my.portfoliomanager.app.service;

import java.util.Locale;

public enum AssessorGapDetectionPolicy {
	SAVING_PLAN_GAPS("saving_plan_gaps"),
	PORTFOLIO_GAPS("portfolio_gaps");

	private final String id;

	AssessorGapDetectionPolicy(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public static AssessorGapDetectionPolicy from(String raw) {
		if (raw == null || raw.isBlank()) {
			return SAVING_PLAN_GAPS;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		if (normalized.equals("portfolio_gaps") || normalized.equals("portfolio_gap")
				|| normalized.equals("portfolio")) {
			return PORTFOLIO_GAPS;
		}
		if (normalized.equals("saving_plan_gaps") || normalized.equals("saving_plan_gap")
				|| normalized.equals("saving_plan") || normalized.equals("savingplan")) {
			return SAVING_PLAN_GAPS;
		}
		return normalized.contains("portfolio") ? PORTFOLIO_GAPS : SAVING_PLAN_GAPS;
	}
}
