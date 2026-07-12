package my.portfoliomanager.app.domain;

public enum KnowledgeBaseRunStatus {
	QUEUED,
	RUNNING,
	WAITING_RETRY,
	REVIEW_REQUIRED,
	COMPLETED,
	CANCELED,
	IN_PROGRESS,
	SUCCEEDED,
	FAILED,
	FAILED_TIMEOUT,
	SKIPPED;

	public boolean isTerminal() {
		return switch (this) {
			case REVIEW_REQUIRED, COMPLETED, CANCELED, FAILED, FAILED_TIMEOUT, SUCCEEDED, SKIPPED -> true;
			default -> false;
		};
	}

	public boolean isActive() {
		return !isTerminal();
	}
}
