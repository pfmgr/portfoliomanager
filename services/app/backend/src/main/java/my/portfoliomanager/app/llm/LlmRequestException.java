package my.portfoliomanager.app.llm;

public class LlmRequestException extends RuntimeException {
	private final Integer statusCode;
	private final boolean retryable;

	public LlmRequestException(String message, Integer statusCode, boolean retryable, Throwable cause) {
		super(message, cause);
		this.statusCode = statusCode;
		this.retryable = retryable;
	}

	public Integer getStatusCode() {
		return statusCode;
	}

	public boolean isRetryable() {
		return retryable;
	}
}
