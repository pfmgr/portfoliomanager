package my.portfoliomanager.app.service;

import jakarta.annotation.PreDestroy;
import my.portfoliomanager.app.dto.AdvisorRunDetailDto;
import my.portfoliomanager.app.dto.AdvisorSummaryDto;
import my.portfoliomanager.app.dto.RebalancerRunJobResponseDto;
import my.portfoliomanager.app.dto.RebalancerRunJobStatus;
import my.portfoliomanager.app.dto.RebalancerRunRequestDto;
import my.portfoliomanager.app.dto.RebalancerRunResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Service
public class RebalancerJobService {
	private static final Logger logger = LoggerFactory.getLogger(RebalancerJobService.class);
	private static final Duration JOB_TTL = Duration.ofMinutes(30);
	private static final int MAX_CONCURRENT_JOBS = 2;

	private final AdvisorService advisorService;
	private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Semaphore concurrency = new Semaphore(MAX_CONCURRENT_JOBS);

	public RebalancerJobService(AdvisorService advisorService) {
		this.advisorService = advisorService;
	}

	public RebalancerRunJobResponseDto start(RebalancerRunRequestDto request) {
		cleanupExpired();
		String jobId = UUID.randomUUID().toString();
		JobState job = new JobState(jobId, request, Instant.now());
		jobs.put(jobId, job);
		executor.submit(() -> runJob(jobId));
		return toDto(job);
	}

	public RebalancerRunJobResponseDto get(String jobId) {
		cleanupExpired();
		JobState job = jobs.get(jobId);
		if (job == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rebalancer job not found");
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
			job.status = RebalancerRunJobStatus.FAILED;
			job.error = failWithReference(job, ex);
			job.finishedAt = Instant.now();
			return;
		}
		try {
			job.status = RebalancerRunJobStatus.RUNNING;
			job.result = run(job.request);
			job.status = RebalancerRunJobStatus.DONE;
		} catch (Exception ex) {
			job.status = RebalancerRunJobStatus.FAILED;
			job.error = failWithReference(job, ex);
		} finally {
			job.finishedAt = Instant.now();
			concurrency.release();
		}
	}

	private RebalancerRunResponseDto run(RebalancerRunRequestDto request) {
		LocalDate asOf = null;
		boolean saveRun = false;
		if (request != null) {
			saveRun = Boolean.TRUE.equals(request.saveRun());
			if (request.asOf() != null && !request.asOf().isBlank()) {
				asOf = LocalDate.parse(request.asOf());
			}
		}
		if (saveRun) {
			AdvisorRunDetailDto savedRun = advisorService.saveRun(asOf);
			return new RebalancerRunResponseDto(savedRun == null ? null : savedRun.summary(), savedRun);
		}
		AdvisorSummaryDto summary = advisorService.summary(asOf);
		return new RebalancerRunResponseDto(summary, null);
	}

	private RebalancerRunJobResponseDto toDto(JobState job) {
		return new RebalancerRunJobResponseDto(
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
		String reference = "RB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
		logger.error("Rebalancer job failed (ref={}, jobId={}, request={}, error={})",
				reference, job.jobId, formatRequest(job.request), message, ex);
		return "Error ref " + reference;
	}

	private String formatRequest(RebalancerRunRequestDto request) {
		if (request == null) {
			return "{}";
		}
		return "{asOf=" + request.asOf() + ", saveRun=" + request.saveRun() + "}";
	}

	private static final class JobState {
		private final String jobId;
		private final RebalancerRunRequestDto request;
		private final Instant createdAt;

		private volatile Instant finishedAt;
		private volatile RebalancerRunJobStatus status;
		private volatile RebalancerRunResponseDto result;
		private volatile String error;

		private JobState(String jobId, RebalancerRunRequestDto request, Instant createdAt) {
			this.jobId = jobId;
			this.request = request;
			this.createdAt = createdAt;
			this.status = RebalancerRunJobStatus.PENDING;
		}
	}
}
