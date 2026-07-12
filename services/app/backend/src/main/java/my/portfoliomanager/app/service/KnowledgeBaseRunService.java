package my.portfoliomanager.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.domain.KnowledgeBaseRun;
import my.portfoliomanager.app.domain.KnowledgeBaseRunAction;
import my.portfoliomanager.app.domain.KnowledgeBaseRunStatus;
import my.portfoliomanager.app.domain.KnowledgeBaseRunStep;
import my.portfoliomanager.app.repository.KnowledgeBaseRunRepository;
import my.portfoliomanager.app.repository.KnowledgeBaseRunStepRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class KnowledgeBaseRunService {
	private static final int ERROR_MAX_LENGTH = 2000;
	private static final int CLAIM_BATCH_SIZE = 50;
	private static final List<KnowledgeBaseRunStatus> TERMINAL_STATUSES = List.of(KnowledgeBaseRunStatus.REVIEW_REQUIRED,
			KnowledgeBaseRunStatus.COMPLETED, KnowledgeBaseRunStatus.CANCELED, KnowledgeBaseRunStatus.SUCCEEDED,
			KnowledgeBaseRunStatus.FAILED, KnowledgeBaseRunStatus.FAILED_TIMEOUT, KnowledgeBaseRunStatus.SKIPPED);
	private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();
	private final KnowledgeBaseRunRepository repository;
	private final KnowledgeBaseRunStepRepository stepRepository;
	private final TransactionTemplate requiresNewTransactionTemplate;

	public KnowledgeBaseRunService(KnowledgeBaseRunRepository repository, KnowledgeBaseRunStepRepository stepRepository,
			PlatformTransactionManager transactionManager) {
		this.repository = repository;
		this.stepRepository = stepRepository;
		this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
		this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Transactional
	public KnowledgeBaseRun startRun(String isin, KnowledgeBaseRunAction action, String batchId, String requestId) {
		return createRun(isin, action, KnowledgeBaseRunStatus.IN_PROGRESS, null, batchId, requestId);
	}

	@Transactional
	public KnowledgeBaseRun enqueueRun(String isin, KnowledgeBaseRunAction action, String idempotencyKey) {
		return enqueueRun(isin, action, idempotencyKey, null, null);
	}

	@Transactional
	public KnowledgeBaseRun enqueueRun(String isin,
							  KnowledgeBaseRunAction action,
							  String idempotencyKey,
							  String batchId,
							  String requestId) {
		String normalizedIdempotencyKey = normalizeKey(idempotencyKey);
		if (normalizedIdempotencyKey != null) {
			Optional<KnowledgeBaseRun> existing = repository.findByIdempotencyKey(normalizedIdempotencyKey);
			if (existing.isPresent()) {
				return validateMatchingIntent(existing.get(), isin, action);
			}
		}
		try {
			return requiresNewTransactionTemplate.execute(status ->
					createRun(isin, action, KnowledgeBaseRunStatus.QUEUED, normalizedIdempotencyKey, batchId, requestId));
		} catch (DataIntegrityViolationException ex) {
			if (normalizedIdempotencyKey == null) {
				throw ex;
			}
			return findExistingRunAfterCollision(normalizedIdempotencyKey, isin, action).orElseThrow(() -> ex);
		}
	}

	@Transactional
	public KnowledgeBaseRun enqueueActionRun(String isin, KnowledgeBaseRunAction action, String payload, String requestId) {
		return enqueueActionRun(isin, action, payload, requestId, null);
	}

	@Transactional
	public KnowledgeBaseRun enqueueActionRun(String isin, KnowledgeBaseRunAction action, String payload, String requestId, String idempotencyKey) {
		String key = normalizeKey(idempotencyKey);
		if (key != null) {
			Optional<KnowledgeBaseRun> existing = repository.findByIdempotencyKey(key);
			if (existing.isPresent()) return validateMatchingIntent(existing.get(), isin, action, payload);
		}
		try {
			return requiresNewTransactionTemplate.execute(status -> {
				KnowledgeBaseRun run = createRun(isin, action, KnowledgeBaseRunStatus.QUEUED, key, null, requestId);
				run.setActionPayload(payload);
				return repository.saveAndFlush(run);
			});
		} catch (DataIntegrityViolationException ex) {
			if (key == null) throw ex;
			return findExistingRunAfterCollision(key, isin, action, payload).orElseThrow(() -> ex);
		}
	}

	@Transactional
	public KnowledgeBaseRun enqueueActionChild(KnowledgeBaseRun parent, String isin, KnowledgeBaseRunAction action, String payload) {
		if (parent == null || parent.getRunId() == null) throw new IllegalArgumentException("A bulk child requires a persisted parent");
		return repository.findFirstByParentRun_RunIdAndIsinAndAction(parent.getRunId(), isin, action)
				.orElseGet(() -> createChild(parent, isin, action, payload, parent.getIdempotencyKey() + ":child:" + isin));
	}

	/** One transaction creates a parent and its uniquely-addressable children. */
	public KnowledgeBaseRun enqueueBulkAction(String payload, String parentIdempotencyKey,
			Map<String, String> childPayloads, KnowledgeBaseRunAction action) {
		String key = normalizeKey(parentIdempotencyKey);
		Optional<KnowledgeBaseRun> existing = repository.findByIdempotencyKey(key);
		if (existing.isPresent()) return validateMatchingIntent(existing.get(), action, payload);
		try {
			return requiresNewTransactionTemplate.execute(status -> {
				Optional<KnowledgeBaseRun> raced = repository.findByIdempotencyKey(key);
				if (raced.isPresent()) return validateMatchingIntent(raced.get(), action, payload);
				KnowledgeBaseRun parent = createRun(null, action, KnowledgeBaseRunStatus.QUEUED, key, null, null);
				parent.setActionPayload(payload);
				parent = repository.saveAndFlush(parent);
				for (Map.Entry<String, String> child : childPayloads.entrySet())
					createChild(parent, child.getKey(), action, child.getValue(), key + ":child:" + child.getKey());
				return parent;
			});
		} catch (DataIntegrityViolationException ex) {
			return findExistingBulkRunAfterCollision(key, action, payload).orElseThrow(() -> ex);
		}
	}

	@Transactional
	public Optional<KnowledgeBaseRun> completeAction(KnowledgeBaseRun run, KnowledgeBaseRunStatus status,
			String payload, String error, LocalDateTime now) {
		return completeAction(run, status, payload, error, error == null ? null : "ACTION_FAILED", null, now);
	}

	@Transactional
	public Optional<KnowledgeBaseRun> completeAction(KnowledgeBaseRun run, KnowledgeBaseRunStatus status,
			String payload, String error, String errorCode, LocalDateTime now) {
		return completeAction(run, status, payload, error, errorCode, null, now);
	}

	@Transactional
	public Optional<KnowledgeBaseRun> completeAction(KnowledgeBaseRun run, KnowledgeBaseRunStatus status,
			String payload, String error, String errorCode, String requestId, LocalDateTime now) {
		if (run == null || run.getRunId() == null || run.getLeaseToken() == null) return Optional.empty();
		int updated = repository.completeActionRun(run.getRunId(), run.getLeaseToken(), KnowledgeBaseRunStatus.RUNNING,
				status, payload, sanitizeError(error), normalizeText(errorCode), normalizeText(requestId), now);
		return updated == 0 ? Optional.empty() : afterTerminalTransition(run.getRunId(), now);
	}

	@Transactional
	public Optional<KnowledgeBaseRun> completeQueuedAction(KnowledgeBaseRun run, KnowledgeBaseRunStatus status,
			String payload, String error, LocalDateTime now) {
		return completeQueuedAction(run, status, payload, error, null, now);
	}

	@Transactional
	public Optional<KnowledgeBaseRun> completeQueuedAction(KnowledgeBaseRun run, KnowledgeBaseRunStatus status,
			String payload, String error, String requestId, LocalDateTime now) {
		if (run == null || run.getRunId() == null) return Optional.empty();
		int updated = repository.completeQueuedAction(run.getRunId(), KnowledgeBaseRunStatus.QUEUED, status,
				payload, sanitizeError(error), normalizeText(requestId), now);
		return updated == 0 ? Optional.empty() : afterTerminalTransition(run.getRunId(), now);
	}

	public Optional<KnowledgeBaseRun> findById(Long runId) { return repository.findById(runId); }

	public List<KnowledgeBaseRun> findActionRuns() {
		return repository.findAllByActionInOrderByCreatedAtDesc(List.of(KnowledgeBaseRunAction.BULK_CREATE,
				KnowledgeBaseRunAction.REFRESH, KnowledgeBaseRunAction.EXTRACT, KnowledgeBaseRunAction.ALTERNATIVES,
				KnowledgeBaseRunAction.MISSING_DATA));
	}

	public List<KnowledgeBaseRun> childrenOf(Long parentRunId) {
		return parentRunId == null ? List.of() : repository.findAllByParentRun_RunIdOrderByCreatedAtAsc(parentRunId);
	}

	/** Bounded wake-up candidate lookup; claiming remains the cross-instance atomic gate. */
	public List<Long> dueWaitingRetryRunIds(LocalDateTime now) {
		return repository.findDueWaitingRetryRunIds(KnowledgeBaseRunStatus.WAITING_RETRY, now, PageRequest.of(0, CLAIM_BATCH_SIZE));
	}

	private KnowledgeBaseRun createChild(KnowledgeBaseRun parent, String isin, KnowledgeBaseRunAction action, String payload, String key) {
		KnowledgeBaseRun child = createRun(isin, action, KnowledgeBaseRunStatus.QUEUED, key, null, parent.getRequestId());
		child.setParentRun(parent);
		child.setActionPayload(payload);
		return repository.saveAndFlush(child);
	}


	@Transactional
	public KnowledgeBaseRun incrementAttempt(KnowledgeBaseRun run) {
		if (run == null) {
			return null;
		}
		int attempts = run.getAttempts() == null ? 0 : run.getAttempts();
		if (run.getStatus() == KnowledgeBaseRunStatus.RUNNING) {
			if (run.getRunId() == null) {
				return run;
			}
			int updated = repository.incrementRunningAttempt(
					run.getRunId(),
					run.getLeaseToken(),
					KnowledgeBaseRunStatus.RUNNING,
					LocalDateTime.now());
			if (updated == 0) {
				return run;
			}
			return repository.findById(run.getRunId()).orElse(run);
		}
		run.setAttempts(attempts + 1);
		return repository.save(run);
	}

	@Transactional
	public Optional<KnowledgeBaseRun> updateCurrentStep(KnowledgeBaseRun run, String step, LocalDateTime now) {
		if (run == null || run.getRunId() == null || run.getLeaseToken() == null) return Optional.empty();
		int updated = repository.updateCurrentStep(run.getRunId(), run.getLeaseToken(), KnowledgeBaseRunStatus.RUNNING,
				normalizeText(step), now);
		return updated == 0 ? Optional.empty() : repository.findById(run.getRunId());
	}

	/** Persists only bounded, sanitized workflow progress while retaining the immutable input envelope. */
	@Transactional
	public Optional<KnowledgeBaseRun> updateProgress(KnowledgeBaseRun run, String step, String payload, LocalDateTime now) {
		if (run == null || run.getRunId() == null || run.getLeaseToken() == null) return Optional.empty();
		int updated = repository.updateProgress(run.getRunId(), run.getLeaseToken(), KnowledgeBaseRunStatus.RUNNING,
				normalizeText(step), payload, now);
		return updated == 0 ? Optional.empty() : repository.findById(run.getRunId());
	}

	@Transactional
	public KnowledgeBaseRun markSucceeded(KnowledgeBaseRun run) {
		return markFinished(run, KnowledgeBaseRunStatus.SUCCEEDED, null, null, null, LocalDateTime.now())
				.orElse(run);
	}

	@Transactional
	public Optional<KnowledgeBaseRun> markSucceeded(KnowledgeBaseRun run, LocalDateTime now) {
		return markFinished(run, KnowledgeBaseRunStatus.SUCCEEDED, null, null, null, now);
	}

	@Transactional
	public KnowledgeBaseRun markFailed(KnowledgeBaseRun run, String error) {
		return markFinished(run, KnowledgeBaseRunStatus.FAILED, error, null, null, LocalDateTime.now())
				.orElse(run);
	}

	@Transactional
	public Optional<KnowledgeBaseRun> markFailed(KnowledgeBaseRun run, String error, String errorCode, LocalDateTime now) {
		return markFinished(run, KnowledgeBaseRunStatus.FAILED, error, errorCode, null, now);
	}

	@Transactional
	public KnowledgeBaseRun markSkipped(KnowledgeBaseRun run, String reason) {
		return markFinished(run, KnowledgeBaseRunStatus.SKIPPED, reason, null, null, LocalDateTime.now())
				.orElse(run);
	}

	@Transactional
	public KnowledgeBaseRun markFailedTimeout(KnowledgeBaseRun run, String reason) {
		return markFinished(run, KnowledgeBaseRunStatus.FAILED_TIMEOUT, reason, "TIMEOUT", null, LocalDateTime.now())
				.orElse(run);
	}

	@Transactional
	public KnowledgeBaseRun markReviewRequired(KnowledgeBaseRun run, String reason) {
		return markFinished(run, KnowledgeBaseRunStatus.REVIEW_REQUIRED, reason, "REVIEW_REQUIRED", null, LocalDateTime.now())
				.orElse(run);
	}

	@Transactional
	public Optional<KnowledgeBaseRun> recordStep(KnowledgeBaseRun run, String step) {
		if (run == null) {
			return Optional.empty();
		}
		run.setCurrentStep(normalizeText(step));
		KnowledgeBaseRun saved = repository.save(run);
		appendStep(saved, step, "STARTED", null, LocalDateTime.now());
		return Optional.of(saved);
	}

	/** Appends rather than replaces progress, so restarts retain the complete workflow audit. */
	@Transactional
	public void appendStep(KnowledgeBaseRun run, String step, String outcome, String details, LocalDateTime now) {
		if (run == null || run.getRunId() == null || step == null || step.isBlank()) return;
		KnowledgeBaseRunStep event = new KnowledgeBaseRunStep();
		event.setRun(run);
		event.setSequenceNo(Math.toIntExact(stepRepository.countByRun_RunId(run.getRunId())) + 1);
		event.setStep(normalizeText(step));
		event.setOutcome(normalizeText(outcome) == null ? "RECORDED" : normalizeText(outcome));
		event.setDetails(sanitizeError(details));
		event.setCreatedAt(now == null ? LocalDateTime.now() : now);
		stepRepository.saveAndFlush(event);
	}

	public List<KnowledgeBaseRunStep> stepHistory(Long runId) {
		return runId == null ? List.of() : stepRepository.findByRun_RunIdOrderBySequenceNoAsc(runId);
	}

	@Transactional
	public Optional<KnowledgeBaseRun> claimNextRun(Duration leaseDuration, LocalDateTime now) {
		validateLeaseDuration(leaseDuration);
		List<Long> candidateRunIds = repository.findClaimableRunIds(
				KnowledgeBaseRunStatus.QUEUED,
				KnowledgeBaseRunStatus.WAITING_RETRY,
				now,
				PageRequest.of(0, CLAIM_BATCH_SIZE));
		for (Long runId : candidateRunIds) {
			Optional<KnowledgeBaseRun> claimed = claimRun(runId, leaseDuration, now);
			if (claimed.isPresent()) {
				return claimed;
			}
		}
		return Optional.empty();
	}

	@Transactional
	public Optional<KnowledgeBaseRun> claimRun(Long runId, Duration leaseDuration, LocalDateTime now) {
		validateLeaseDuration(leaseDuration);
		if (runId == null) {
			return Optional.empty();
		}
		String leaseToken = UUID.randomUUID().toString();
		LocalDateTime leaseUntil = now.plus(leaseDuration);
		int updated = repository.claimEligibleRun(
				runId,
				KnowledgeBaseRunStatus.QUEUED,
				KnowledgeBaseRunStatus.WAITING_RETRY,
				KnowledgeBaseRunStatus.RUNNING,
				leaseToken,
				leaseUntil,
				now);
		return updated == 0 ? Optional.empty() : repository.findById(runId);
	}

	@Transactional
	public Optional<KnowledgeBaseRun> heartbeat(KnowledgeBaseRun run, Duration leaseDuration, LocalDateTime now) {
		validateLeaseDuration(leaseDuration);
		if (run == null || run.getRunId() == null || run.getLeaseToken() == null) {
			return Optional.empty();
		}
		int updated = repository.extendLease(
				run.getRunId(),
				run.getLeaseToken(),
				KnowledgeBaseRunStatus.RUNNING,
				now.plus(leaseDuration),
				now);
		return updated == 0 ? Optional.empty() : repository.findById(run.getRunId());
	}

	@Transactional
	public Optional<KnowledgeBaseRun> requestCancellation(KnowledgeBaseRun run, LocalDateTime now) {
		if (run == null || run.getRunId() == null || run.getStatus() == null) {
			return Optional.empty();
		}
		if (run.getStatus() == KnowledgeBaseRunStatus.QUEUED || run.getStatus() == KnowledgeBaseRunStatus.WAITING_RETRY) {
			int updated = repository.cancelQueuedOrWaiting(
					run.getRunId(),
					List.of(KnowledgeBaseRunStatus.QUEUED, KnowledgeBaseRunStatus.WAITING_RETRY),
					KnowledgeBaseRunStatus.CANCELED,
					now);
			return canceledAfterCascadingChildren(run.getRunId(), updated, now);
		}
		if (isRunningLike(run.getStatus())) {
			int updated = repository.requestCancellation(
					run.getRunId(),
					run.getLeaseToken(),
					List.of(KnowledgeBaseRunStatus.RUNNING, KnowledgeBaseRunStatus.IN_PROGRESS),
					now);
			return canceledAfterCascadingChildren(run.getRunId(), updated, now);
		}
		return Optional.empty();
	}

	@Transactional
	public Optional<KnowledgeBaseRun> markCanceled(KnowledgeBaseRun run, LocalDateTime now) {
		if (run == null || run.getRunId() == null) {
			return Optional.empty();
		}
		int updated = repository.finalizeCancellation(
				run.getRunId(),
				run.getLeaseToken(),
				KnowledgeBaseRunStatus.RUNNING,
				KnowledgeBaseRunStatus.IN_PROGRESS,
				KnowledgeBaseRunStatus.CANCELED,
				now);
		return updated == 0 ? Optional.empty() : afterTerminalTransition(run.getRunId(), now);
	}

	@Transactional
	public Optional<KnowledgeBaseRun> markWaitingRetry(KnowledgeBaseRun run,
						   LocalDateTime nextRetryAt,
						   String errorCode,
						   String error,
						   String requestId,
						   LocalDateTime now) {
		if (run == null || run.getRunId() == null || nextRetryAt == null) {
			return Optional.empty();
		}
		int updated = repository.markWaitingRetry(
				run.getRunId(),
				run.getLeaseToken(),
				KnowledgeBaseRunStatus.RUNNING,
				KnowledgeBaseRunStatus.IN_PROGRESS,
				KnowledgeBaseRunStatus.WAITING_RETRY,
				nextRetryAt,
				sanitizeError(error),
				normalizeText(errorCode),
				normalizeText(requestId),
				now);
		return updated == 0 ? Optional.empty() : repository.findById(run.getRunId());
	}

	@Transactional
	public int recoverExpiredLeases(LocalDateTime now) {
		List<Long> affectedParents = repository.findParentRunIdsForExpiredRunningLeases(KnowledgeBaseRunStatus.RUNNING, now);
		int recovered = repository.recoverExpiredRunningLeases(
				KnowledgeBaseRunStatus.RUNNING,
				KnowledgeBaseRunStatus.QUEUED,
				KnowledgeBaseRunStatus.CANCELED,
				now);
		affectedParents.forEach(parentRunId -> reconcileParent(parentRunId, now));
		return recovered;
	}

	@Transactional
	public int markTimedOutRuns(Duration timeout, LocalDateTime now) {
		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			return 0;
		}
		LocalDateTime cutoff = now.minus(timeout);
		List<Long> affectedParents = repository.findParentRunIdsForTimedOutRuns(KnowledgeBaseRunStatus.RUNNING,
				KnowledgeBaseRunStatus.IN_PROGRESS, cutoff, now);
		int timedOut = repository.markTimedOutRuns(
				KnowledgeBaseRunStatus.RUNNING,
				KnowledgeBaseRunStatus.IN_PROGRESS,
				KnowledgeBaseRunStatus.FAILED_TIMEOUT,
				cutoff,
				"Timed out",
				"TIMEOUT",
				now);
		affectedParents.forEach(parentRunId -> reconcileParent(parentRunId, now));
		return timedOut;
	}

	public Optional<KnowledgeBaseRun> findLatest(String isin, KnowledgeBaseRunAction action) {
		if (action == null) {
			return Optional.empty();
		}
		return repository.findFirstByIsinAndActionOrderByStartedAtDesc(isin, action);
	}

	public Page<KnowledgeBaseRun> search(String isin, KnowledgeBaseRunStatus status, Pageable pageable) {
		return repository.search(isin, status, pageable);
	}

	@Transactional
	public int markTimedOutRuns(Duration timeout) {
		return markTimedOutRuns(timeout, LocalDateTime.now());
	}

	private Optional<KnowledgeBaseRun> markFinished(KnowledgeBaseRun run,
						   KnowledgeBaseRunStatus status,
						   String error,
						   String errorCode,
						   String requestId,
						   LocalDateTime now) {
		if (run == null) {
			return Optional.empty();
		}
		if (run.getStatus() != null && run.getStatus().isTerminal()) {
			return Optional.empty();
		}
		int updated = repository.completeRun(
				run.getRunId(),
				run.getLeaseToken(),
				KnowledgeBaseRunStatus.RUNNING,
				KnowledgeBaseRunStatus.IN_PROGRESS,
				status,
				sanitizeError(error),
				normalizeText(errorCode),
				normalizeText(requestId),
				now);
		return updated == 0 ? Optional.empty() : afterTerminalTransition(run.getRunId(), now);
	}

	private KnowledgeBaseRun createRun(String isin,
								  KnowledgeBaseRunAction action,
								  KnowledgeBaseRunStatus status,
								  String idempotencyKey,
								  String batchId,
								  String requestId) {
		KnowledgeBaseRun run = new KnowledgeBaseRun();
		run.setIsin(isin);
		run.setAction(action);
		run.setStatus(status);
		run.setStartedAt(LocalDateTime.now());
		run.setAttempts(0);
		run.setIdempotencyKey(idempotencyKey);
		run.setBatchId(batchId);
		run.setRequestId(requestId);
		return repository.saveAndFlush(run);
	}

	private Optional<KnowledgeBaseRun> findExistingRunAfterCollision(String idempotencyKey,
											 String isin,
											 KnowledgeBaseRunAction action) {
		return requiresNewTransactionTemplate.execute(status -> repository.findByIdempotencyKey(idempotencyKey)
				.map(existingRun -> validateMatchingIntent(existingRun, isin, action)));
	}

	private Optional<KnowledgeBaseRun> findExistingRunAfterCollision(String idempotencyKey, String isin,
			KnowledgeBaseRunAction action, String payload) {
		return requiresNewTransactionTemplate.execute(status -> repository.findByIdempotencyKey(idempotencyKey)
				.map(existingRun -> validateMatchingIntent(existingRun, isin, action, payload)));
	}

	private KnowledgeBaseRun validateMatchingIntent(KnowledgeBaseRun existingRun,
										 String isin,
										 KnowledgeBaseRunAction action) {
		if (!Objects.equals(existingRun.getAction(), action) || !Objects.equals(existingRun.getIsin(), isin)) {
			throw new IllegalStateException("Idempotency key already used for a different workflow intent");
		}
		return existingRun;
	}

	private KnowledgeBaseRun validateMatchingIntent(KnowledgeBaseRun existingRun, KnowledgeBaseRunAction action, String payload) {
		if (!Objects.equals(existingRun.getAction(), action) || !Objects.equals(normalizePayloadInput(existingRun.getActionPayload()), normalizePayloadInput(payload))) {
			throw new IllegalStateException("Idempotency key already used for a different workflow intent");
		}
		return existingRun;
	}

	private KnowledgeBaseRun validateMatchingIntent(KnowledgeBaseRun existingRun, String isin,
			KnowledgeBaseRunAction action, String payload) {
		KnowledgeBaseRun matched = validateMatchingIntent(existingRun, isin, action);
		if (!Objects.equals(matched.getActionPayload(), payload)) {
			throw new IllegalStateException("Idempotency key already used for a different workflow intent");
		}
		return matched;
	}

	private String normalizeKey(String idempotencyKey) {
		if (idempotencyKey == null) {
			return null;
		}
		String trimmed = idempotencyKey.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private String sanitizeError(String error) {
		if (error == null) {
			return null;
		}
		String trimmed = KnowledgeBaseSourceUrlPolicy.redactSensitiveText(error);
		if (trimmed.length() > ERROR_MAX_LENGTH) {
			return trimmed.substring(0, ERROR_MAX_LENGTH);
		}
		return trimmed.isEmpty() ? null : trimmed;
	}

	private String normalizeText(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private void validateLeaseDuration(Duration leaseDuration) {
		if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
			throw new IllegalArgumentException("Lease duration must be positive");
		}
	}

	private boolean isRunningLike(KnowledgeBaseRunStatus status) {
		return status == KnowledgeBaseRunStatus.RUNNING || status == KnowledgeBaseRunStatus.IN_PROGRESS;
	}

	private Optional<KnowledgeBaseRun> canceledAfterCascadingChildren(Long runId, int updated, LocalDateTime now) {
		if (updated == 0) return Optional.empty();
		repository.cancelUnfinishedChildren(runId,
				List.of(KnowledgeBaseRunStatus.REVIEW_REQUIRED, KnowledgeBaseRunStatus.COMPLETED,
						KnowledgeBaseRunStatus.CANCELED, KnowledgeBaseRunStatus.SUCCEEDED,
						KnowledgeBaseRunStatus.FAILED, KnowledgeBaseRunStatus.FAILED_TIMEOUT, KnowledgeBaseRunStatus.SKIPPED),
				KnowledgeBaseRunStatus.CANCELED, now);
		return afterTerminalTransition(runId, now);
	}

	private Optional<KnowledgeBaseRun> afterTerminalTransition(Long runId, LocalDateTime now) {
		Optional<KnowledgeBaseRun> transitioned = repository.findById(runId);
		transitioned.filter(run -> run.getStatus().isTerminal() && run.getParentRun() != null)
				.ifPresent(run -> reconcileParent(run.getParentRun().getRunId(), now));
		return transitioned;
	}

	private void reconcileParent(Long parentRunId, LocalDateTime now) {
		List<KnowledgeBaseRun> children = childrenOf(parentRunId);
		if (children.isEmpty() || children.stream().anyMatch(child -> !child.getStatus().isTerminal())) return;
		KnowledgeBaseRunStatus status = children.stream().allMatch(child -> child.getStatus() == KnowledgeBaseRunStatus.CANCELED)
				? KnowledgeBaseRunStatus.CANCELED
				: children.stream().anyMatch(child -> child.getStatus() == KnowledgeBaseRunStatus.FAILED || child.getStatus() == KnowledgeBaseRunStatus.FAILED_TIMEOUT)
						? KnowledgeBaseRunStatus.FAILED : KnowledgeBaseRunStatus.COMPLETED;
		repository.terminalizeParentWhenChildrenTerminal(parentRunId,
				List.of(KnowledgeBaseRunStatus.QUEUED, KnowledgeBaseRunStatus.RUNNING, KnowledgeBaseRunStatus.WAITING_RETRY, KnowledgeBaseRunStatus.IN_PROGRESS),
				TERMINAL_STATUSES, status, now);
	}

	private Optional<KnowledgeBaseRun> findExistingBulkRunAfterCollision(String idempotencyKey, KnowledgeBaseRunAction action, String payload) {
		return requiresNewTransactionTemplate.execute(status -> repository.findByIdempotencyKey(idempotencyKey)
				.map(existingRun -> validateMatchingIntent(existingRun, action, payload)));
	}

	private JsonNode normalizePayloadInput(String payload) {
		if (payload == null || payload.isBlank()) {
			return null;
		}
		try {
			JsonNode root = PAYLOAD_MAPPER.readTree(payload);
			return root != null && root.isObject() && root.has("input") ? root.get("input") : root;
		} catch (Exception ex) {
			throw new IllegalStateException("Unable to compare idempotency payloads", ex);
		}
	}
}
