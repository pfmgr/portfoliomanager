package my.portfoliomanager.app.service;

import jakarta.annotation.PreDestroy;
import my.portfoliomanager.app.dto.InstrumentDossierWebsearchJobResponseDto;
import my.portfoliomanager.app.dto.InstrumentDossierWebsearchJobStatus;
import my.portfoliomanager.app.dto.InstrumentDossierWebsearchResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

@Service
public class KnowledgeBaseWebsearchJobService {
	private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseWebsearchJobService.class);
	private static final Pattern ISIN_RE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");
	private static final Duration JOB_TTL = Duration.ofMinutes(30);
	private static final int MAX_CONCURRENT_JOBS = 2;

	private final KnowledgeBaseService knowledgeBaseService;
	private final Map<String, WebsearchJobState> jobs = new ConcurrentHashMap<>();
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Semaphore concurrency = new Semaphore(MAX_CONCURRENT_JOBS);

	public KnowledgeBaseWebsearchJobService(KnowledgeBaseService knowledgeBaseService) {
		this.knowledgeBaseService = knowledgeBaseService;
	}

	public InstrumentDossierWebsearchJobResponseDto start(String isin) {
		String normalizedIsin = normalizeIsin(isin);
		cleanupExpired();
		String jobId = UUID.randomUUID().toString();
		WebsearchJobState job = new WebsearchJobState(jobId, normalizedIsin, Instant.now());
		jobs.put(jobId, job);
		executor.submit(() -> runJob(jobId));
		return toDto(job);
	}

	public InstrumentDossierWebsearchJobResponseDto get(String jobId) {
		cleanupExpired();
		WebsearchJobState job = jobs.get(jobId);
		if (job == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Websearch job not found");
		}
		return toDto(job);
	}

	@PreDestroy
	public void shutdown() {
		executor.shutdownNow();
	}

	private void runJob(String jobId) {
		WebsearchJobState job = jobs.get(jobId);
		if (job == null) {
			return;
		}
		try {
			concurrency.acquire();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			job.status = InstrumentDossierWebsearchJobStatus.FAILED;
			job.error = failWithReference(job, ex);
			job.finishedAt = Instant.now();
			return;
		}
		try {
			job.status = InstrumentDossierWebsearchJobStatus.RUNNING;
			InstrumentDossierWebsearchResponseDto result = knowledgeBaseService.createDossierDraftViaWebsearch(job.isin);
			job.result = result;
			job.status = InstrumentDossierWebsearchJobStatus.DONE;
		} catch (Exception ex) {
			job.status = InstrumentDossierWebsearchJobStatus.FAILED;
			job.error = failWithReference(job, ex);
		} finally {
			job.finishedAt = Instant.now();
			concurrency.release();
		}
	}

	private InstrumentDossierWebsearchJobResponseDto toDto(WebsearchJobState job) {
		return new InstrumentDossierWebsearchJobResponseDto(
				job.jobId,
				job.status,
				job.result,
				job.error
		);
	}

	private void cleanupExpired() {
		Instant now = Instant.now();
		jobs.entrySet().removeIf(entry -> {
			WebsearchJobState job = entry.getValue();
			Instant base = job.finishedAt == null ? job.createdAt : job.finishedAt;
			return base.plus(JOB_TTL).isBefore(now);
		});
	}

	private String normalizeIsin(String value) {
		String trimmed = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
		if (!ISIN_RE.matcher(trimmed).matches()) {
			throw new IllegalArgumentException("Invalid ISIN: " + trimmed);
		}
		return trimmed;
	}

	private String failWithReference(WebsearchJobState job, Exception ex) {
		String message = ex == null ? null : ex.getMessage();
		String reference = "KB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
		logger.error("KB websearch job failed (ref={}, jobId={}, isin={}, error={})",
				reference, job.jobId, job.isin, message, ex);
		return "Error ref " + reference;
	}

	private static final class WebsearchJobState {
		private final String jobId;
		private final String isin;
		private final Instant createdAt;

		private volatile Instant finishedAt;
		private volatile InstrumentDossierWebsearchJobStatus status;
		private volatile InstrumentDossierWebsearchResponseDto result;
		private volatile String error;

		private WebsearchJobState(String jobId, String isin, Instant createdAt) {
			this.jobId = jobId;
			this.isin = isin;
			this.createdAt = createdAt;
			this.status = InstrumentDossierWebsearchJobStatus.PENDING;
		}
	}
}
