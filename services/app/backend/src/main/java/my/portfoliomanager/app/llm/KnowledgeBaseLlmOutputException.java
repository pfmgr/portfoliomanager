package my.portfoliomanager.app.llm;

public class KnowledgeBaseLlmOutputException extends RuntimeException {
	private final String errorCode;

	public KnowledgeBaseLlmOutputException(String message, String errorCode) {
		super(message);
		this.errorCode = errorCode;
	}

	public String getErrorCode() {
		return errorCode;
	}
}
