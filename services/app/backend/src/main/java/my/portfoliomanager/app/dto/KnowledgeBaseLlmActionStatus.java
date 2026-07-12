package my.portfoliomanager.app.dto;

public enum KnowledgeBaseLlmActionStatus {
	QUEUED,
	RUNNING,
	WAITING_RETRY,
	REVIEW_REQUIRED,
	COMPLETED,
	DONE,
	FAILED,
	CANCELED
}
