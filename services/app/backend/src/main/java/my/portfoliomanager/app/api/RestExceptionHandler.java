package my.portfoliomanager.app.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@RestControllerAdvice
public class RestExceptionHandler {
	private static final Logger logger = LoggerFactory.getLogger(RestExceptionHandler.class);

	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
		logger.warn("Bad request on {}: {}", request.getRequestURI(), ex.getMessage());
		ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
		detail.setTitle("Bad Request");
		detail.setDetail("Invalid request.");
		detail.setProperty("path", request.getRequestURI());
		return detail;
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ProblemDetail handleMaxUpload(MaxUploadSizeExceededException ex, HttpServletRequest request) {
		logger.warn("Upload too large on {}: {}", request.getRequestURI(), ex.getMessage());
		ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
		detail.setTitle("Payload Too Large");
		detail.setDetail("Upload exceeded the maximum allowed size.");
		detail.setProperty("path", request.getRequestURI());
		return detail;
	}

	@ExceptionHandler(MultipartException.class)
	public ProblemDetail handleMultipart(MultipartException ex, HttpServletRequest request) {
		logger.warn("Multipart request failed on {}: {}", request.getRequestURI(), ex.getMessage());
		ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
		detail.setTitle("Invalid multipart request");
		detail.setDetail("Failed to read multipart request.");
		detail.setProperty("path", request.getRequestURI());
		return detail;
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ProblemDetail handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
		ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
		detail.setTitle("Not Found");
		detail.setDetail("Resource not found.");
		detail.setProperty("path", request.getRequestURI());
		return detail;
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
		logger.warn("Validation failed on {}", request.getRequestURI(), ex);
		ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
		detail.setTitle("Validation failed");
		List<String> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(this::formatFieldError)
				.toList();
		detail.setProperty("errors", errors);
		detail.setProperty("path", request.getRequestURI());
		return detail;
	}

	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnhandled(Exception ex, HttpServletRequest request) {
		logger.error("Unexpected error on {}", request.getRequestURI(), ex);
		ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
		detail.setTitle("Internal Server Error");
		detail.setDetail("Unexpected error");
		detail.setProperty("path", request.getRequestURI());
		return detail;
	}

	private String formatFieldError(FieldError error) {
		return error.getField() + ": " + error.getDefaultMessage();
	}
}
