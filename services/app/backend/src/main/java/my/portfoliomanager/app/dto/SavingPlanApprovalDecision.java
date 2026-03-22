package my.portfoliomanager.app.dto;

import my.portfoliomanager.app.domain.InstrumentBlacklistScope;

public enum SavingPlanApprovalDecision {
	APPLY,
	IGNORE,
	BLACKLIST_SAVING_PLAN_ONLY,
	BLACKLIST_ALL_PROPOSALS;

	public boolean requiresSavingPlanMutation() {
		return this == APPLY;
	}

	public InstrumentBlacklistScope blacklistScope() {
		return switch (this) {
			case BLACKLIST_SAVING_PLAN_ONLY -> InstrumentBlacklistScope.SAVING_PLAN_ONLY;
			case BLACKLIST_ALL_PROPOSALS -> InstrumentBlacklistScope.ALL_PROPOSALS;
			default -> null;
		};
	}
}
