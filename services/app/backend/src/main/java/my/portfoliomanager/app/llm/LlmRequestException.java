package my.portfoliomanager.app.llm;

import java.time.Duration;

public class LlmRequestException extends RuntimeException {
	private final Integer statusCode;
	private final boolean retryable;
	private final Duration retryAfter;
	private final String requestId;

	public LlmRequestException(String message, Integer statusCode, boolean retryable, Throwable cause) {
		this(message, statusCode, retryable, null, null, cause);
	}

	public LlmRequestException(String message,
							  Integer statusCode,
							  boolean retryable,
							  Duration retryAfter,
							  String requestId,
							  Throwable cause) {
		super(message, cause);
		this.statusCode = statusCode;
		this.retryable = retryable;
		this.retryAfter = retryAfter;
		this.requestId = requestId;
	}

	public Integer getStatusCode() {
		return statusCode;
	}

	public boolean isRetryable() {
		return retryable;
	}

	public Duration getRetryAfter() {
		return retryAfter;
	}

	public String getRequestId() {
		return requestId;
	}
}
