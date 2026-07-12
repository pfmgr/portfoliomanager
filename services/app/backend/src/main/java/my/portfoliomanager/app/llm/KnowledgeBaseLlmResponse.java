package my.portfoliomanager.app.llm;

import java.time.Duration;

public record KnowledgeBaseLlmResponse(
		String output,
		String model,
		Integer statusCode,
		boolean retryable,
		Duration retryAfter,
		String requestId
) {
	public KnowledgeBaseLlmResponse(String output, String model) {
		this(output, model, 200, false, null, null);
	}
}
