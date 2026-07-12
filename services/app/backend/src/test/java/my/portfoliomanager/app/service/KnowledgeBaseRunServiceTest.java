package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.KnowledgeBaseRun;
import my.portfoliomanager.app.domain.KnowledgeBaseRunAction;
import my.portfoliomanager.app.domain.KnowledgeBaseRunStatus;
import my.portfoliomanager.app.repository.KnowledgeBaseRunRepository;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
class KnowledgeBaseRunServiceTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private KnowledgeBaseRunService runService;

	@Autowired
	private KnowledgeBaseRunRepository runRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
	}

	@BeforeEach
	void setup() {
		jdbcTemplate.update("delete from kb_runs");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void enqueueRun_defaultsToQueuedAndPersistsRootIsinNull() {
		KnowledgeBaseRun saved = runService.enqueueRun(null, KnowledgeBaseRunAction.REFRESH, "idem-root");

		KnowledgeBaseRun loaded = runRepository.findById(saved.getRunId()).orElseThrow();
		assertThat(loaded.getStatus()).isEqualTo(KnowledgeBaseRunStatus.QUEUED);
		assertThat(loaded.getIsin()).isNull();
		assertThat(loaded.getCreatedAt()).isNotNull();
		assertThat(loaded.getUpdatedAt()).isNotNull();
	}

	@Test
	void stepHistory_isAppendOnlyAndOrderedAfterReload() {
		KnowledgeBaseRun run = runService.enqueueRun("DE0000000099", KnowledgeBaseRunAction.EXTRACT, "step-history");
		LocalDateTime now = LocalDateTime.of(2026, 7, 12, 10, 0);
		runService.appendStep(run, "STRUCTURED_EXTRACTION", "PASSED", null, now);
		runService.appendStep(run, "SCHEMA_VALIDATION", "PASSED", null, now.plusSeconds(1));
		runService.appendStep(run, "EVIDENCE_VALIDATION", "REJECTED", "unsupported", now.plusSeconds(2));

		assertThat(runService.stepHistory(run.getRunId())).extracting(step -> step.getSequenceNo() + ":" + step.getStep() + ":" + step.getOutcome())
				.containsExactly("1:STRUCTURED_EXTRACTION:PASSED", "2:SCHEMA_VALIDATION:PASSED", "3:EVIDENCE_VALIDATION:REJECTED");
	}

	@Test
	void claimNextRun_competingClaimsLoseAndStaleWorkerCannotComplete() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 12, 0);
		KnowledgeBaseRun queued = persistRun("DE0000000001", KnowledgeBaseRunAction.REFRESH,
				KnowledgeBaseRunStatus.QUEUED, now.minusMinutes(5));

		KnowledgeBaseRun firstClaim = runService.claimRun(queued.getRunId(), Duration.ofMinutes(15), now).orElseThrow();
		assertThat(runService.claimRun(queued.getRunId(), Duration.ofMinutes(15), now)).isEmpty();

		LocalDateTime recoveryTime = now.plusMinutes(16);
		assertThat(runService.recoverExpiredLeases(recoveryTime)).isEqualTo(1);

		KnowledgeBaseRun secondClaim = runService.claimRun(queued.getRunId(), Duration.ofMinutes(15), recoveryTime).orElseThrow();
		assertThat(secondClaim.getLeaseToken()).isNotEqualTo(firstClaim.getLeaseToken());

		assertThat(runService.markSucceeded(firstClaim, recoveryTime.plusMinutes(1))).isEmpty();

		KnowledgeBaseRun afterStaleComplete = runRepository.findById(queued.getRunId()).orElseThrow();
		assertThat(afterStaleComplete.getStatus()).isEqualTo(KnowledgeBaseRunStatus.RUNNING);
		assertThat(afterStaleComplete.getLeaseToken()).isEqualTo(secondClaim.getLeaseToken());
	}

	@Test
	void heartbeat_requiresMatchingLeaseTokenAndOwnership() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 13, 0);
		KnowledgeBaseRun queued = persistRun("DE0000000002", KnowledgeBaseRunAction.REFRESH,
				KnowledgeBaseRunStatus.QUEUED, now.minusMinutes(5));
		KnowledgeBaseRun claimed = runService.claimRun(queued.getRunId(), Duration.ofMinutes(10), now).orElseThrow();

		KnowledgeBaseRun staleCopy = runRepository.findById(claimed.getRunId()).orElseThrow();
		staleCopy.setLeaseToken("bogus-token");
		LocalDateTime originalLeaseUntil = staleCopy.getLeaseUntil();

		assertThat(runService.heartbeat(staleCopy, Duration.ofMinutes(10), now.plusMinutes(2))).isEmpty();
		assertThat(runRepository.findById(claimed.getRunId()).orElseThrow().getLeaseUntil()).isEqualTo(originalLeaseUntil);

		KnowledgeBaseRun refreshed = runService.heartbeat(claimed, Duration.ofMinutes(10), now.plusMinutes(2)).orElseThrow();
		assertThat(refreshed.getLeaseUntil()).isEqualTo(now.plusMinutes(12));
	}

	@Test
	void heartbeat_failsWhenLeaseExpired() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 13, 30);
		KnowledgeBaseRun running = persistRun("DE0000000009", KnowledgeBaseRunAction.EXTRACT,
				KnowledgeBaseRunStatus.RUNNING, now.minusMinutes(20));
		running.setLeaseToken("lease-token-expired");
		running.setLeaseUntil(now.minusMinutes(1));
		runRepository.save(running);

		assertThat(runService.heartbeat(running, Duration.ofMinutes(10), now)).isEmpty();
	}

	@Test
	void requestCancellation_cancelsQueuedRunImmediately() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 14, 0);
		KnowledgeBaseRun queued = persistRun("DE0000000003", KnowledgeBaseRunAction.REFRESH,
				KnowledgeBaseRunStatus.QUEUED, now.minusMinutes(1));

		KnowledgeBaseRun canceled = runService.requestCancellation(queued, now).orElseThrow();

		assertThat(canceled.getStatus()).isEqualTo(KnowledgeBaseRunStatus.CANCELED);
		assertThat(canceled.getCancelRequestedAt()).isEqualTo(now);
		assertThat(canceled.getFinishedAt()).isEqualTo(now);
	}

	@Test
	void requestCancellation_onParentTerminalizesEveryUnfinishedChildButPreservesCompletedChild() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 14, 30);
		KnowledgeBaseRun parent = persistRun(null, KnowledgeBaseRunAction.BULK_CREATE, KnowledgeBaseRunStatus.QUEUED, now.minusMinutes(2));
		KnowledgeBaseRun queuedChild = persistRun("DE0000000030", KnowledgeBaseRunAction.BULK_CREATE, KnowledgeBaseRunStatus.QUEUED, now.minusMinutes(1));
		queuedChild.setParentRun(parent);
		KnowledgeBaseRun completedChild = persistRun("DE0000000031", KnowledgeBaseRunAction.BULK_CREATE, KnowledgeBaseRunStatus.COMPLETED, now.minusMinutes(1));
		completedChild.setParentRun(parent);
		KnowledgeBaseRun runningChild = persistRun("DE0000000032", KnowledgeBaseRunAction.BULK_CREATE, KnowledgeBaseRunStatus.RUNNING, now.minusMinutes(1));
		runningChild.setParentRun(parent);
		runningChild.setLeaseToken("child-lease");
		runningChild.setLeaseUntil(now.plusMinutes(5));
		runRepository.saveAll(List.of(queuedChild, completedChild, runningChild));

		KnowledgeBaseRun canceledParent = runService.requestCancellation(parent, now).orElseThrow();

		assertThat(canceledParent.getStatus()).isEqualTo(KnowledgeBaseRunStatus.CANCELED);
		assertThat(runService.childrenOf(parent.getRunId())).extracting(KnowledgeBaseRun::getStatus)
				.containsExactly(KnowledgeBaseRunStatus.CANCELED, KnowledgeBaseRunStatus.COMPLETED, KnowledgeBaseRunStatus.CANCELED);
		assertThat(runService.claimNextRun(Duration.ofMinutes(10), now)).isEmpty();
	}

	@Test
	void directFinalChildCancellationReconcilesParentToTerminalStatus() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 14, 40);
		KnowledgeBaseRun parent = persistRun(null, KnowledgeBaseRunAction.BULK_CREATE, KnowledgeBaseRunStatus.RUNNING, now.minusMinutes(2));
		parent.setLeaseToken("parent-lease");
		parent.setLeaseUntil(now.plusMinutes(5));
		KnowledgeBaseRun completed = persistRun("DE0000000034", KnowledgeBaseRunAction.BULK_CREATE, KnowledgeBaseRunStatus.COMPLETED, now.minusMinutes(1));
		KnowledgeBaseRun last = persistRun("DE0000000035", KnowledgeBaseRunAction.BULK_CREATE, KnowledgeBaseRunStatus.QUEUED, now.minusMinutes(1));
		completed.setParentRun(parent);
		last.setParentRun(parent);
		runRepository.saveAll(List.of(parent, completed, last));

		runService.requestCancellation(last, now).orElseThrow();

		KnowledgeBaseRun terminalParent = runService.findById(parent.getRunId()).orElseThrow();
		assertThat(terminalParent.getStatus()).isEqualTo(KnowledgeBaseRunStatus.COMPLETED);
		assertThat(terminalParent.getFinishedAt()).isEqualTo(now);
	}

	@Test
	void legacyInProgressIsNotClaimableAsADurableRun() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 14, 45);
		persistRun("DE0000000033", KnowledgeBaseRunAction.REFRESH, KnowledgeBaseRunStatus.IN_PROGRESS, now.minusMinutes(1));

		assertThat(runService.claimNextRun(Duration.ofMinutes(10), now)).isEmpty();
	}

	@Test
	void payloadReloadKeepsExecutionInputWhenRetainedOutputIsLarge() {
		KnowledgeBaseRun run = persistRun("DE0000000034", KnowledgeBaseRunAction.REFRESH, KnowledgeBaseRunStatus.COMPLETED,
				LocalDateTime.of(2026, 7, 11, 14, 50));
		String input = "{\"type\":\"REFRESH\",\"trigger\":\"USER\",\"actor\":\"alice\",\"isins\":[\"DE0000000034\"],\"force\":true}";
		run.setActionPayload("{\"input\":" + input + ",\"output\":{\"message\":\"Result too large to retain\",\"result\":{\"truncated\":true}}}");
		runRepository.saveAndFlush(run);

		String stored = jdbcTemplate.queryForObject("select action_payload from kb_runs where run_id = ?", String.class, run.getRunId());

		assertThat(stored).contains("\"input\"", "DE0000000034", "\"actor\":\"alice\"", "\"force\":true")
				.contains("\"truncated\":true");
	}

	@Test
	void restartRecoveryMakesQueuedExpiredAndDueRetryClaimableButNeverResumesTerminalRuns() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 14, 55);
		KnowledgeBaseRun queued = persistRun("DE0000000035", KnowledgeBaseRunAction.REFRESH, KnowledgeBaseRunStatus.QUEUED, now.minusMinutes(3));
		KnowledgeBaseRun dueRetry = persistRun("DE0000000036", KnowledgeBaseRunAction.REFRESH, KnowledgeBaseRunStatus.WAITING_RETRY, now.minusMinutes(2));
		dueRetry.setNextRetryAt(now.minusSeconds(1));
		KnowledgeBaseRun expired = persistRun("DE0000000037", KnowledgeBaseRunAction.REFRESH, KnowledgeBaseRunStatus.RUNNING, now.minusMinutes(2));
		expired.setLeaseToken("expired"); expired.setLeaseUntil(now.minusSeconds(1));
		KnowledgeBaseRun terminal = persistRun("DE0000000038", KnowledgeBaseRunAction.REFRESH, KnowledgeBaseRunStatus.COMPLETED, now.minusMinutes(1));
		runRepository.saveAll(List.of(dueRetry, expired));

		assertThat(runService.recoverExpiredLeases(now)).isEqualTo(1);
		assertThat(runService.claimNextRun(Duration.ofMinutes(10), now)).isPresent();
		assertThat(runService.claimNextRun(Duration.ofMinutes(10), now)).isPresent();
		assertThat(runService.claimNextRun(Duration.ofMinutes(10), now)).isPresent();
		assertThat(runService.claimNextRun(Duration.ofMinutes(10), now)).isEmpty();
		assertThat(runRepository.findById(terminal.getRunId()).orElseThrow().getStatus()).isEqualTo(KnowledgeBaseRunStatus.COMPLETED);
	}

	@Test
	void markWaitingRetry_persistsRequestIdAndRetryMetadata() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 15, 5);
		KnowledgeBaseRun running = persistRun("DE0000000040", KnowledgeBaseRunAction.REFRESH,
				KnowledgeBaseRunStatus.RUNNING, now.minusMinutes(1));
		running.setLeaseToken("lease-token");
		running.setLeaseUntil(now.plusMinutes(5));
		runRepository.saveAndFlush(running);

		KnowledgeBaseRun updated = runService.markWaitingRetry(running, now.plusSeconds(30), "RATE_LIMIT",
				"Action retry scheduled", "req-123", now).orElseThrow();

		assertThat(updated.getStatus()).isEqualTo(KnowledgeBaseRunStatus.WAITING_RETRY);
		assertThat(updated.getNextRetryAt()).isEqualTo(now.plusSeconds(30));
		assertThat(updated.getErrorCode()).isEqualTo("RATE_LIMIT");
		assertThat(updated.getRequestId()).isEqualTo("req-123");
	}

	@Test
	void requestCancellation_marksRunningRunUntilCheckpointResolvesCancellation() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 15, 0);
		KnowledgeBaseRun queued = persistRun("DE0000000004", KnowledgeBaseRunAction.REFRESH,
				KnowledgeBaseRunStatus.QUEUED, now.minusMinutes(5));
		KnowledgeBaseRun claimed = runService.claimRun(queued.getRunId(), Duration.ofMinutes(10), now).orElseThrow();

		KnowledgeBaseRun cancellationRequested = runService.requestCancellation(claimed, now.plusMinutes(1)).orElseThrow();
		assertThat(cancellationRequested.getStatus()).isEqualTo(KnowledgeBaseRunStatus.RUNNING);
		assertThat(cancellationRequested.getCancelRequestedAt()).isEqualTo(now.plusMinutes(1));
		assertThat(cancellationRequested.getFinishedAt()).isNull();

		KnowledgeBaseRun canceled = runService.markCanceled(cancellationRequested, now.plusMinutes(2)).orElseThrow();
		assertThat(canceled.getStatus()).isEqualTo(KnowledgeBaseRunStatus.CANCELED);
		assertThat(canceled.getFinishedAt()).isEqualTo(now.plusMinutes(2));
	}

	@Test
	void cancellation_blocksHeartbeatCompletionAndRetry() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 15, 30);
		KnowledgeBaseRun queued = persistRun("DE0000000010", KnowledgeBaseRunAction.REFRESH,
				KnowledgeBaseRunStatus.QUEUED, now.minusMinutes(5));
		KnowledgeBaseRun claimed = runService.claimRun(queued.getRunId(), Duration.ofMinutes(10), now).orElseThrow();
		KnowledgeBaseRun cancellationRequested = runService.requestCancellation(claimed, now.plusMinutes(1)).orElseThrow();

		assertThat(runService.heartbeat(cancellationRequested, Duration.ofMinutes(10), now.plusMinutes(2))).isEmpty();
		assertThat(runService.markSucceeded(cancellationRequested, now.plusMinutes(2))).isEmpty();
		assertThat(runService.markWaitingRetry(cancellationRequested, now.plusMinutes(5), "RATE_LIMIT", "try later", null, now.plusMinutes(2))).isEmpty();
	}

	@Test
	void expiredRunningRunCannotCompleteRetryOrCancelBeforeRecovery() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 16, 15);
		KnowledgeBaseRun expired = persistRun("DE0000000011", KnowledgeBaseRunAction.EXTRACT,
				KnowledgeBaseRunStatus.RUNNING, now.minusMinutes(30));
		expired.setLeaseToken("lease-token-expired");
		expired.setLeaseUntil(now.minusMinutes(1));
		runRepository.save(expired);

		assertThat(runService.markSucceeded(expired, now)).isEmpty();
		assertThat(runService.markWaitingRetry(expired, now.plusMinutes(5), "RATE_LIMIT", "try later", null, now)).isEmpty();
		assertThat(runService.markCanceled(expired, now)).isEmpty();
	}

	@Test
	void recoverExpiredLeases_cancelsExpiredRunningRunWithPendingCancellation() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 16, 30);
		KnowledgeBaseRun expired = persistRun("DE0000000012", KnowledgeBaseRunAction.REFRESH,
				KnowledgeBaseRunStatus.RUNNING, now.minusMinutes(25));
		expired.setLeaseToken("lease-token-cancelled");
		expired.setLeaseUntil(now.minusMinutes(1));
		expired.setCancelRequestedAt(now.minusMinutes(2));
		runRepository.save(expired);

		assertThat(runService.markCanceled(expired, now)).isEmpty();

		assertThat(runService.recoverExpiredLeases(now)).isEqualTo(1);

		KnowledgeBaseRun recovered = runRepository.findById(expired.getRunId()).orElseThrow();
		assertThat(recovered.getStatus()).isEqualTo(KnowledgeBaseRunStatus.CANCELED);
		assertThat(recovered.getFinishedAt()).isEqualTo(now);
		assertThat(runService.claimNextRun(Duration.ofMinutes(10), now)).isEmpty();
	}

	@Test
	void recoverExpiredLeases_requeuesRunningRunWithoutChangingAttempts() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 16, 0);
		KnowledgeBaseRun running = new KnowledgeBaseRun();
		running.setIsin("DE0000000005");
		running.setAction(KnowledgeBaseRunAction.EXTRACT);
		running.setStatus(KnowledgeBaseRunStatus.RUNNING);
		running.setStartedAt(now.minusMinutes(30));
		running.setAttempts(3);
		running.setLeaseToken("lease-token-1");
		running.setLeaseUntil(now.minusMinutes(1));
		running.setLastHeartbeatAt(now.minusMinutes(10));
		KnowledgeBaseRun saved = runRepository.save(running);

		assertThat(runService.recoverExpiredLeases(now)).isEqualTo(1);

		KnowledgeBaseRun recovered = runRepository.findById(saved.getRunId()).orElseThrow();
		assertThat(recovered.getStatus()).isEqualTo(KnowledgeBaseRunStatus.QUEUED);
		assertThat(recovered.getLeaseToken()).isNull();
		assertThat(recovered.getLeaseUntil()).isNull();
		assertThat(recovered.getAttempts()).isEqualTo(3);
	}

	@Test
	void incrementAttempt_staleRunningRunAfterRecoveryCannotChangePersistedAttempts() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 16, 10);
		KnowledgeBaseRun running = new KnowledgeBaseRun();
		running.setIsin("DE0000000015");
		running.setAction(KnowledgeBaseRunAction.EXTRACT);
		running.setStatus(KnowledgeBaseRunStatus.RUNNING);
		running.setStartedAt(now.minusMinutes(25));
		running.setAttempts(3);
		running.setLeaseToken("lease-token-2");
		running.setLeaseUntil(now.minusMinutes(1));
		running.setLastHeartbeatAt(now.minusMinutes(5));
		KnowledgeBaseRun saved = runRepository.save(running);

		KnowledgeBaseRun staleCopy = runRepository.findById(saved.getRunId()).orElseThrow();
		assertThat(runService.recoverExpiredLeases(now)).isEqualTo(1);

		runService.incrementAttempt(staleCopy);

		KnowledgeBaseRun recovered = runRepository.findById(saved.getRunId()).orElseThrow();
		assertThat(recovered.getStatus()).isEqualTo(KnowledgeBaseRunStatus.QUEUED);
		assertThat(recovered.getAttempts()).isEqualTo(3);
	}

	@Test
	void waitingRetryCanBeConfiguredAndClaimedWhenDue() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 17, 0);
		KnowledgeBaseRun queued = persistRun("DE0000000006", KnowledgeBaseRunAction.EXTRACT,
				KnowledgeBaseRunStatus.QUEUED, now.minusMinutes(10));
		KnowledgeBaseRun claimed = runService.claimRun(queued.getRunId(), Duration.ofMinutes(10), now).orElseThrow();

		LocalDateTime nextRetryAt = now.plusMinutes(3);
		KnowledgeBaseRun waitingRetry = runService.markWaitingRetry(claimed, nextRetryAt, "RATE_LIMIT", "try later", null, now.plusMinutes(1)).orElseThrow();
		assertThat(waitingRetry.getStatus()).isEqualTo(KnowledgeBaseRunStatus.WAITING_RETRY);
		assertThat(waitingRetry.getNextRetryAt()).isEqualTo(nextRetryAt);
		assertThat(waitingRetry.getErrorCode()).isEqualTo("RATE_LIMIT");

		assertThat(runService.claimNextRun(Duration.ofMinutes(10), nextRetryAt).map(KnowledgeBaseRun::getRunId)).contains(waitingRetry.getRunId());
	}

	@Test
	void waitingRetryIsNotClaimableBeforeNextRetryAt() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 17, 15);
		KnowledgeBaseRun queued = persistRun("DE0000000013", KnowledgeBaseRunAction.EXTRACT,
				KnowledgeBaseRunStatus.QUEUED, now.minusMinutes(10));
		KnowledgeBaseRun claimed = runService.claimRun(queued.getRunId(), Duration.ofMinutes(10), now).orElseThrow();

		LocalDateTime nextRetryAt = now.plusMinutes(5);
		runService.markWaitingRetry(claimed, nextRetryAt, "RATE_LIMIT", "try later", null, now).orElseThrow();

		assertThat(runService.claimNextRun(Duration.ofMinutes(10), now.plusMinutes(1))).isEmpty();
	}

	@Test
	void terminalReviewRequiredRunIsNeverClaimed() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 18, 0);
		persistRun("DE0000000007", KnowledgeBaseRunAction.REFRESH, KnowledgeBaseRunStatus.REVIEW_REQUIRED, now.minusMinutes(1));

		assertThat(runService.claimNextRun(Duration.ofMinutes(10), now)).isEmpty();
	}

	@Test
	void allTerminalStatusesAreNonclaimable() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 18, 15);
		List<KnowledgeBaseRunStatus> terminalStatuses = List.of(
				KnowledgeBaseRunStatus.REVIEW_REQUIRED,
				KnowledgeBaseRunStatus.COMPLETED,
				KnowledgeBaseRunStatus.CANCELED,
				KnowledgeBaseRunStatus.SUCCEEDED,
				KnowledgeBaseRunStatus.FAILED,
				KnowledgeBaseRunStatus.FAILED_TIMEOUT,
				KnowledgeBaseRunStatus.SKIPPED);
		for (int i = 0; i < terminalStatuses.size(); i++) {
			persistRun("DE00000000" + (20 + i), KnowledgeBaseRunAction.REFRESH, terminalStatuses.get(i), now.minusMinutes(1));
		}

		assertThat(runService.claimNextRun(Duration.ofMinutes(10), now)).isEmpty();
	}

	@Test
	void enqueueRun_sameIdempotencyKeySameIntentReturnsOriginalRun() {
		KnowledgeBaseRun first = runService.enqueueRun(null, KnowledgeBaseRunAction.REFRESH, "idem-shared");
		KnowledgeBaseRun second = runService.enqueueRun(null, KnowledgeBaseRunAction.REFRESH, "idem-shared");

		assertThat(second.getRunId()).isEqualTo(first.getRunId());
		assertThat(runRepository.count()).isEqualTo(1);
		assertThat(runRepository.findById(first.getRunId()).orElseThrow().getIsin()).isNull();
		assertThat(runRepository.findById(first.getRunId()).orElseThrow().getAction()).isEqualTo(KnowledgeBaseRunAction.REFRESH);
	}

	@Test
	void enqueueRun_sameIdempotencyKeyRejectsDifferentActionOrIsin() {
		runService.enqueueRun("DE0000000001", KnowledgeBaseRunAction.REFRESH, "idem-conflict");

		assertThatThrownBy(() -> runService.enqueueRun("DE0000000001", KnowledgeBaseRunAction.EXTRACT, "idem-conflict"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("different workflow intent");
		assertThatThrownBy(() -> runService.enqueueRun("DE0000000002", KnowledgeBaseRunAction.REFRESH, "idem-conflict"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("different workflow intent");
		assertThat(runRepository.count()).isEqualTo(1);
	}

	@Test
	void enqueueRun_concurrentSameIdempotencyKeySameIntentReturnsSingleRun() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Callable<Long> task = () -> {
				ready.countDown();
				assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
				return runService.enqueueRun("DE0000000014", KnowledgeBaseRunAction.REFRESH, "idem-concurrent").getRunId();
			};
			Future<Long> first = executor.submit(task);
			Future<Long> second = executor.submit(task);
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			Long firstRunId = first.get(10, TimeUnit.SECONDS);
			Long secondRunId = second.get(10, TimeUnit.SECONDS);
			assertThat(firstRunId).isEqualTo(secondRunId);
			assertThat(runRepository.count()).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void enqueueBulkAction_concurrentDuplicateCreatesOneParentAndOneChildPerIsin() throws Exception {
		Map<String, String> children = new LinkedHashMap<>();
		children.put("DE0000000040", "{\"input\":{\"isins\":[\"DE0000000040\"]}}");
		children.put("DE0000000041", "{\"input\":{\"isins\":[\"DE0000000041\"]}}");
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Callable<Long> task = () -> {
				ready.countDown();
				assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
				return runService.enqueueBulkAction("{\"input\":{\"isins\":[\"DE0000000040\",\"DE0000000041\"]}}",
						"bulk-intent", children, KnowledgeBaseRunAction.BULK_CREATE).getRunId();
			};
			Future<Long> first = executor.submit(task);
			Future<Long> second = executor.submit(task);
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			Long parentId = first.get(10, TimeUnit.SECONDS);
			assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(parentId);
			assertThat(runService.childrenOf(parentId)).extracting(KnowledgeBaseRun::getIsin)
					.containsExactly("DE0000000040", "DE0000000041");
			assertThat(runRepository.count()).isEqualTo(3);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void reviewRequiredIsTerminalAndCannotBeOverwritten() {
		assertThat(KnowledgeBaseRunStatus.REVIEW_REQUIRED.isTerminal()).isTrue();

		KnowledgeBaseRun run = runService.enqueueRun("DE0000000001", KnowledgeBaseRunAction.REFRESH, "idem-terminal");
		run.setStatus(KnowledgeBaseRunStatus.REVIEW_REQUIRED);
		runRepository.save(run);

		KnowledgeBaseRun terminal = runRepository.findById(run.getRunId()).orElseThrow();
		runService.markFailed(terminal, "should not replace terminal state");

		KnowledgeBaseRun loaded = runRepository.findById(run.getRunId()).orElseThrow();
		assertThat(loaded.getStatus()).isEqualTo(KnowledgeBaseRunStatus.REVIEW_REQUIRED);
		assertThat(loaded.getError()).isNull();
	}

	@Test
	void findLatest_allowsNullIsinForRootRuns() {
		KnowledgeBaseRun older = new KnowledgeBaseRun();
		older.setIsin(null);
		older.setAction(KnowledgeBaseRunAction.REFRESH);
		older.setStatus(KnowledgeBaseRunStatus.SUCCEEDED);
		older.setStartedAt(LocalDateTime.now().minusMinutes(10));
		older.setAttempts(0);
		runRepository.save(older);

		KnowledgeBaseRun newer = new KnowledgeBaseRun();
		newer.setIsin(null);
		newer.setAction(KnowledgeBaseRunAction.REFRESH);
		newer.setStatus(KnowledgeBaseRunStatus.SUCCEEDED);
		newer.setStartedAt(LocalDateTime.now());
		newer.setAttempts(0);
		runRepository.save(newer);

		assertThat(runService.findLatest(null, KnowledgeBaseRunAction.REFRESH)).hasValueSatisfying(run ->
				assertThat(run.getRunId()).isEqualTo(newer.getRunId()));
	}

	@Test
	void markTimedOutRuns_setsFailedTimeout() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 19, 0);
		KnowledgeBaseRun run = new KnowledgeBaseRun();
		run.setIsin("DE0000000001");
		run.setAction(KnowledgeBaseRunAction.REFRESH);
		run.setStatus(KnowledgeBaseRunStatus.IN_PROGRESS);
		run.setStartedAt(now.minusMinutes(45));
		run.setAttempts(1);
		KnowledgeBaseRun saved = runRepository.save(run);

		int marked = runService.markTimedOutRuns(Duration.ofMinutes(30), now);

		KnowledgeBaseRun updated = runRepository.findById(saved.getRunId()).orElseThrow();
		assertThat(marked).isEqualTo(1);
		assertThat(updated.getStatus()).isEqualTo(KnowledgeBaseRunStatus.FAILED_TIMEOUT);
		assertThat(updated.getFinishedAt()).isNotNull();
	}

	@Test
	void markTimedOutRuns_doesNotTerminalizeActivelyLeasedRunningRun() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 19, 30);
		KnowledgeBaseRun activeLease = new KnowledgeBaseRun();
		activeLease.setIsin("DE0000000016");
		activeLease.setAction(KnowledgeBaseRunAction.REFRESH);
		activeLease.setStatus(KnowledgeBaseRunStatus.RUNNING);
		activeLease.setStartedAt(now.minusMinutes(45));
		activeLease.setAttempts(1);
		activeLease.setLeaseToken("lease-token-active");
		activeLease.setLeaseUntil(now.plusMinutes(5));
		runRepository.save(activeLease);

		KnowledgeBaseRun expiredLease = new KnowledgeBaseRun();
		expiredLease.setIsin("DE0000000017");
		expiredLease.setAction(KnowledgeBaseRunAction.REFRESH);
		expiredLease.setStatus(KnowledgeBaseRunStatus.RUNNING);
		expiredLease.setStartedAt(now.minusMinutes(45));
		expiredLease.setAttempts(1);
		expiredLease.setLeaseToken("lease-token-expired");
		expiredLease.setLeaseUntil(now.minusMinutes(1));
		runRepository.save(expiredLease);

		int marked = runService.markTimedOutRuns(Duration.ofMinutes(30), now);

		KnowledgeBaseRun activeAfter = runRepository.findById(activeLease.getRunId()).orElseThrow();
		KnowledgeBaseRun expiredAfter = runRepository.findById(expiredLease.getRunId()).orElseThrow();
		assertThat(marked).isEqualTo(1);
		assertThat(activeAfter.getStatus()).isEqualTo(KnowledgeBaseRunStatus.RUNNING);
		assertThat(expiredAfter.getStatus()).isEqualTo(KnowledgeBaseRunStatus.FAILED_TIMEOUT);
	}

	private KnowledgeBaseRun persistRun(String isin,
								   KnowledgeBaseRunAction action,
								   KnowledgeBaseRunStatus status,
								   LocalDateTime startedAt) {
		KnowledgeBaseRun run = new KnowledgeBaseRun();
		run.setIsin(isin);
		run.setAction(action);
		run.setStatus(status);
		run.setStartedAt(startedAt);
		run.setAttempts(0);
		return runRepository.save(run);
	}
}
