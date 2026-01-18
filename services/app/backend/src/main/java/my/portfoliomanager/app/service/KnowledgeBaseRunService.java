package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.KnowledgeBaseRun;
import my.portfoliomanager.app.domain.KnowledgeBaseRunAction;
import my.portfoliomanager.app.domain.KnowledgeBaseRunStatus;
import my.portfoliomanager.app.repository.KnowledgeBaseRunRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class KnowledgeBaseRunService {
	private static final int ERROR_MAX_LENGTH = 2000;
	private final KnowledgeBaseRunRepository repository;

	public KnowledgeBaseRunService(KnowledgeBaseRunRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public KnowledgeBaseRun startRun(String isin, KnowledgeBaseRunAction action, String batchId, String requestId) {
		KnowledgeBaseRun run = new KnowledgeBaseRun();
		run.setIsin(isin);
		run.setAction(action);
		run.setStatus(KnowledgeBaseRunStatus.IN_PROGRESS);
		run.setStartedAt(LocalDateTime.now());
		run.setAttempts(0);
		run.setBatchId(batchId);
		run.setRequestId(requestId);
		return repository.save(run);
	}

	@Transactional
	public KnowledgeBaseRun incrementAttempt(KnowledgeBaseRun run) {
		if (run == null) {
			return null;
		}
		int attempts = run.getAttempts() == null ? 0 : run.getAttempts();
		run.setAttempts(attempts + 1);
		return repository.save(run);
	}

	@Transactional
	public KnowledgeBaseRun markSucceeded(KnowledgeBaseRun run) {
		return markFinished(run, KnowledgeBaseRunStatus.SUCCEEDED, null);
	}

	@Transactional
	public KnowledgeBaseRun markFailed(KnowledgeBaseRun run, String error) {
		return markFinished(run, KnowledgeBaseRunStatus.FAILED, error);
	}

	@Transactional
	public KnowledgeBaseRun markSkipped(KnowledgeBaseRun run, String reason) {
		return markFinished(run, KnowledgeBaseRunStatus.SKIPPED, reason);
	}

	@Transactional
	public KnowledgeBaseRun markFailedTimeout(KnowledgeBaseRun run, String reason) {
		return markFinished(run, KnowledgeBaseRunStatus.FAILED_TIMEOUT, reason);
	}

	public Optional<KnowledgeBaseRun> findLatest(String isin, KnowledgeBaseRunAction action) {
		if (isin == null || action == null) {
			return Optional.empty();
		}
		return repository.findFirstByIsinAndActionOrderByStartedAtDesc(isin, action);
	}

	public Page<KnowledgeBaseRun> search(String isin, KnowledgeBaseRunStatus status, Pageable pageable) {
		return repository.search(isin, status, pageable);
	}

	@Transactional
	public int markTimedOutRuns(Duration timeout) {
		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			return 0;
		}
		LocalDateTime cutoff = LocalDateTime.now().minus(timeout);
		List<KnowledgeBaseRun> timedOut = repository.findTimedOut(KnowledgeBaseRunStatus.IN_PROGRESS, cutoff);
		int count = 0;
		for (KnowledgeBaseRun run : timedOut) {
			run.setStatus(KnowledgeBaseRunStatus.FAILED_TIMEOUT);
			run.setFinishedAt(LocalDateTime.now());
			run.setError("Timed out");
			repository.save(run);
			count++;
		}
		return count;
	}

	private KnowledgeBaseRun markFinished(KnowledgeBaseRun run, KnowledgeBaseRunStatus status, String error) {
		if (run == null) {
			return null;
		}
		run.setStatus(status);
		run.setFinishedAt(LocalDateTime.now());
		run.setError(sanitizeError(error));
		return repository.save(run);
	}

	private String sanitizeError(String error) {
		if (error == null) {
			return null;
		}
		String trimmed = error.replaceAll("[\\r\\n]+", " ").trim();
		if (trimmed.length() > ERROR_MAX_LENGTH) {
			return trimmed.substring(0, ERROR_MAX_LENGTH);
		}
		return trimmed.isEmpty() ? null : trimmed;
	}
}
