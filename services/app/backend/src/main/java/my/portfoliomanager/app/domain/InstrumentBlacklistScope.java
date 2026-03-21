package my.portfoliomanager.app.domain;

public enum InstrumentBlacklistScope {
	NONE,
	SAVING_PLAN_ONLY,
	ALL_PROPOSALS;

	public boolean excludesSavingPlans() {
		return this == SAVING_PLAN_ONLY || this == ALL_PROPOSALS;
	}

	public boolean excludesOneTimeInvests() {
		return this == ALL_PROPOSALS;
	}
}
