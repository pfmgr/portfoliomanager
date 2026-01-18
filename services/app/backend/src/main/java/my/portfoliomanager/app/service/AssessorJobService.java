package my.portfoliomanager.app.service;

import jakarta.annotation.PreDestroy;
import my.portfoliomanager.app.dto.AssessorRunJobResponseDto;
import my.portfoliomanager.app.dto.AssessorRunJobStatus;
import my.portfoliomanager.app.dto.AssessorRunRequestDto;
import my.portfoliomanager.app.dto.AssessorRunResponseDto;
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

@Service
public class AssessorJobService {
	private static final Logger logger = LoggerFactory.getLogger(AssessorJobService.class);
	private static final Duration JOB_TTL = Duration.ofMinutes(30);
	private static final int MAX_CONCURRENT_JOBS = 2;

	private final AssessorService assessorService;
	private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Semaphore concurrency = new Semaphore(MAX_CONCURRENT_JOBS);

	public AssessorJobService(AssessorService assessorService) {
		this.assessorService = assessorService;
	}

	public AssessorRunJobResponseDto start(AssessorRunRequestDto request) {
		cleanupExpired();
		String jobId = UUID.randomUUID().toString();
		JobState job = new JobState(jobId, request, Instant.now());
		jobs.put(jobId, job);
		executor.submit(() -> runJob(jobId));
		return toDto(job);
	}

	public AssessorRunJobResponseDto get(String jobId) {
		cleanupExpired();
		JobState job = jobs.get(jobId);
		if (job == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assessor job not found");
		}
		return toDto(job);
	}

	@PreDestroy
	public void shutdown() {
		executor.shutdownNow();
	}

	private void runJob(String jobId) {
		JobState job = jobs.get(jobId);
		if (job == null) {
			return;
		}
		try {
			concurrency.acquire();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			job.status = AssessorRunJobStatus.FAILED;
			job.error = failWithReference(job, ex);
			job.finishedAt = Instant.now();
			return;
		}
		try {
			job.status = AssessorRunJobStatus.RUNNING;
			job.result = assessorService.run(job.request);
			job.status = AssessorRunJobStatus.DONE;
		} catch (Exception ex) {
			job.status = AssessorRunJobStatus.FAILED;
			job.error = failWithReference(job, ex);
		} finally {
			job.finishedAt = Instant.now();
			concurrency.release();
		}
	}

	private AssessorRunJobResponseDto toDto(JobState job) {
		return new AssessorRunJobResponseDto(
				job.jobId,
				job.status,
				job.result,
				job.error
		);
	}

	private void cleanupExpired() {
		Instant now = Instant.now();
		jobs.entrySet().removeIf(entry -> {
			JobState job = entry.getValue();
			Instant base = job.finishedAt == null ? job.createdAt : job.finishedAt;
			return base.plus(JOB_TTL).isBefore(now);
		});
	}

	private String failWithReference(JobState job, Exception ex) {
		String message = ex == null ? null : ex.getMessage();
		String reference = "AS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
		logger.error("Assessor job failed (ref={}, jobId={}, error={})", reference, job.jobId, message, ex);
		return "Error ref " + reference;
	}

	private static final class JobState {
		private final String jobId;
		private final AssessorRunRequestDto request;
		private final Instant createdAt;

		private volatile Instant finishedAt;
		private volatile AssessorRunJobStatus status;
		private volatile AssessorRunResponseDto result;
		private volatile String error;

		private JobState(String jobId, AssessorRunRequestDto request, Instant createdAt) {
			this.jobId = jobId;
			this.request = request;
			this.createdAt = createdAt;
			this.status = AssessorRunJobStatus.PENDING;
		}
	}
}
