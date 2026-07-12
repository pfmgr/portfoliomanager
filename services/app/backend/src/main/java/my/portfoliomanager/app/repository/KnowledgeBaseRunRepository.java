package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.KnowledgeBaseRun;
import my.portfoliomanager.app.domain.KnowledgeBaseRunAction;
import my.portfoliomanager.app.domain.KnowledgeBaseRunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRunRepository extends JpaRepository<KnowledgeBaseRun, Long> {
	Optional<KnowledgeBaseRun> findByIdempotencyKey(String idempotencyKey);

	Optional<KnowledgeBaseRun> findFirstByIsinAndActionOrderByStartedAtDesc(String isin, KnowledgeBaseRunAction action);

	List<KnowledgeBaseRun> findAllByParentRun_RunIdOrderByCreatedAtAsc(Long parentRunId);

	Optional<KnowledgeBaseRun> findFirstByParentRun_RunIdAndIsinAndAction(Long parentRunId, String isin, KnowledgeBaseRunAction action);

	List<KnowledgeBaseRun> findAllByActionInOrderByCreatedAtDesc(List<KnowledgeBaseRunAction> actions);

	@Query("""
		select r from KnowledgeBaseRun r
		where (:isin is null or r.isin = :isin)
		  and (:status is null or r.status = :status)
		order by r.startedAt desc
		""")
	Page<KnowledgeBaseRun> search(@Param("isin") String isin,
								  @Param("status") KnowledgeBaseRunStatus status,
								  Pageable pageable);

	@Query("""
		select r.runId from KnowledgeBaseRun r
		where r.cancelRequestedAt is null
		  and (
			  r.status = :queuedStatus
			  or (r.status = :waitingRetryStatus and r.nextRetryAt is not null and r.nextRetryAt <= :now)
		  )
		  and (r.leaseToken is null or r.leaseUntil is null or r.leaseUntil <= :now)
		order by r.startedAt asc, r.runId asc
		""")
	List<Long> findClaimableRunIds(@Param("queuedStatus") KnowledgeBaseRunStatus queuedStatus,
									  @Param("waitingRetryStatus") KnowledgeBaseRunStatus waitingRetryStatus,
									  @Param("now") LocalDateTime now,
								  Pageable pageable);

	@Query("""
		select r.runId from KnowledgeBaseRun r
		where r.status = :waitingRetryStatus and r.cancelRequestedAt is null
		  and r.nextRetryAt is not null and r.nextRetryAt <= :now
		order by r.nextRetryAt asc, r.runId asc
		""")
	List<Long> findDueWaitingRetryRunIds(@Param("waitingRetryStatus") KnowledgeBaseRunStatus waitingRetryStatus,
									 @Param("now") LocalDateTime now, Pageable pageable);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update KnowledgeBaseRun r
		   set r.status = :runningStatus,
		       r.leaseToken = :leaseToken,
		       r.leaseUntil = :leaseUntil,
		       r.lastHeartbeatAt = :now,
		       r.cancelRequestedAt = null,
		       r.finishedAt = null,
		       r.updatedAt = :now
		 where r.runId = :runId
		   and r.cancelRequestedAt is null
		   and (
			   r.status = :queuedStatus
			   or (r.status = :waitingRetryStatus and r.nextRetryAt is not null and r.nextRetryAt <= :now)
		   )
		   and (r.leaseToken is null or r.leaseUntil is null or r.leaseUntil <= :now)
		""")
	int claimEligibleRun(@Param("runId") Long runId,
						 @Param("queuedStatus") KnowledgeBaseRunStatus queuedStatus,
						 @Param("waitingRetryStatus") KnowledgeBaseRunStatus waitingRetryStatus,
						 @Param("runningStatus") KnowledgeBaseRunStatus runningStatus,
						 @Param("leaseToken") String leaseToken,
						 @Param("leaseUntil") LocalDateTime leaseUntil,
						 @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update KnowledgeBaseRun r
		   set r.leaseUntil = :leaseUntil,
		       r.lastHeartbeatAt = :now,
		       r.updatedAt = :now
		 where r.runId = :runId
		   and r.status = :runningStatus
		   and r.leaseToken = :leaseToken
		   and r.cancelRequestedAt is null
		   and r.leaseUntil is not null
		   and r.leaseUntil > :now
		""")
	int extendLease(@Param("runId") Long runId,
				   @Param("leaseToken") String leaseToken,
				   @Param("runningStatus") KnowledgeBaseRunStatus runningStatus,
				   @Param("leaseUntil") LocalDateTime leaseUntil,
				   @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update KnowledgeBaseRun r
		   set r.status = :finalStatus,
		       r.finishedAt = :now,
		       r.nextRetryAt = null,
		       r.error = :error,
		       r.errorCode = :errorCode,
		       r.requestId = coalesce(:requestId, r.requestId),
		       r.leaseToken = null,
		       r.leaseUntil = null,
		       r.updatedAt = :now
		 where r.runId = :runId
		   and r.cancelRequestedAt is null
		   and (
		       r.status = :inProgressStatus
		       or (
		           r.status = :runningStatus
		           and r.leaseToken is not null
		           and r.leaseUntil is not null
		           and r.leaseUntil > :now
		           and r.leaseToken = :leaseToken
		       )
		   )
		""")
	int completeRun(@Param("runId") Long runId,
						 @Param("leaseToken") String leaseToken,
						 @Param("runningStatus") KnowledgeBaseRunStatus runningStatus,
						 @Param("inProgressStatus") KnowledgeBaseRunStatus inProgressStatus,
						 @Param("finalStatus") KnowledgeBaseRunStatus finalStatus,
						 @Param("error") String error,
						 @Param("errorCode") String errorCode,
						 @Param("requestId") String requestId,
						 @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		 update KnowledgeBaseRun r set r.status = :finalStatus, r.finishedAt = :now,
		 r.actionPayload = :payload, r.error = :error, r.errorCode = :errorCode, r.requestId = coalesce(:requestId, r.requestId), r.leaseToken = null, r.leaseUntil = null, r.updatedAt = :now
		where r.runId = :runId and r.status = :runningStatus and r.leaseToken = :leaseToken
		 and r.leaseUntil > :now and r.cancelRequestedAt is null
		""")
	int completeActionRun(@Param("runId") Long runId, @Param("leaseToken") String leaseToken,
			@Param("runningStatus") KnowledgeBaseRunStatus runningStatus,
			@Param("finalStatus") KnowledgeBaseRunStatus finalStatus, @Param("payload") String payload,
			@Param("error") String error, @Param("errorCode") String errorCode,
			@Param("requestId") String requestId, @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		 update KnowledgeBaseRun r set r.status = :finalStatus, r.finishedAt = :now, r.actionPayload = :payload,
		 r.error = :error, r.requestId = coalesce(:requestId, r.requestId), r.updatedAt = :now where r.runId = :runId and r.status = :queuedStatus
		""")
	int completeQueuedAction(@Param("runId") Long runId, @Param("queuedStatus") KnowledgeBaseRunStatus queuedStatus,
			@Param("finalStatus") KnowledgeBaseRunStatus finalStatus, @Param("payload") String payload,
			@Param("error") String error, @Param("requestId") String requestId, @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update KnowledgeBaseRun r
		   set r.cancelRequestedAt = :now,
		       r.updatedAt = :now
		 where r.runId = :runId
		   and r.status in (:runningStatuses)
		   and r.cancelRequestedAt is null
		   and ((:leaseToken is null and r.leaseToken is null) or r.leaseToken = :leaseToken)
		""")
	int requestCancellation(@Param("runId") Long runId,
						   @Param("leaseToken") String leaseToken,
						   @Param("runningStatuses") List<KnowledgeBaseRunStatus> runningStatuses,
						   @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update KnowledgeBaseRun r
		   set r.status = :canceledStatus,
		       r.cancelRequestedAt = :now,
		       r.finishedAt = :now,
		       r.nextRetryAt = null,
		       r.leaseToken = null,
		       r.leaseUntil = null,
		       r.lastHeartbeatAt = null,
		       r.updatedAt = :now
		 where r.runId = :runId
		   and r.cancelRequestedAt is null
		   and r.status in (:cancelableStatuses)
		""")
	int cancelQueuedOrWaiting(@Param("runId") Long runId,
							  @Param("cancelableStatuses") List<KnowledgeBaseRunStatus> cancelableStatuses,
							  @Param("canceledStatus") KnowledgeBaseRunStatus canceledStatus,
							  @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update KnowledgeBaseRun r
		   set r.status = :canceledStatus, r.cancelRequestedAt = :now, r.finishedAt = :now,
		       r.nextRetryAt = null, r.leaseToken = null, r.leaseUntil = null,
		       r.lastHeartbeatAt = null, r.updatedAt = :now
		 where r.parentRun.runId = :parentRunId and r.status not in (:terminalStatuses)
		""")
	int cancelUnfinishedChildren(@Param("parentRunId") Long parentRunId,
							  @Param("terminalStatuses") List<KnowledgeBaseRunStatus> terminalStatuses,
							  @Param("canceledStatus") KnowledgeBaseRunStatus canceledStatus,
							  @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update KnowledgeBaseRun r
		   set r.status = :canceledStatus,
		       r.finishedAt = :now,
		       r.nextRetryAt = null,
		       r.leaseToken = null,
		       r.leaseUntil = null,
		       r.lastHeartbeatAt = :now,
		       r.updatedAt = :now
		 where r.runId = :runId
		   and r.cancelRequestedAt is not null
		   and (
		       r.status = :inProgressStatus
		       or (
		           r.status = :runningStatus
		           and r.leaseToken is not null
		           and r.leaseUntil is not null
		           and r.leaseUntil > :now
		           and r.leaseToken = :leaseToken
		       )
		   )
		""")
	int finalizeCancellation(@Param("runId") Long runId,
						 @Param("leaseToken") String leaseToken,
						 @Param("runningStatus") KnowledgeBaseRunStatus runningStatus,
						 @Param("inProgressStatus") KnowledgeBaseRunStatus inProgressStatus,
						 @Param("canceledStatus") KnowledgeBaseRunStatus canceledStatus,
						 @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update KnowledgeBaseRun r
		   set r.status = :waitingRetryStatus,
		       r.nextRetryAt = :nextRetryAt,
		       r.lastHeartbeatAt = null,
		       r.error = :error,
		       r.errorCode = :errorCode,
		       r.requestId = coalesce(:requestId, r.requestId),
		       r.finishedAt = null,
		       r.leaseToken = null,
		       r.leaseUntil = null,
		       r.updatedAt = :now
		 where r.runId = :runId
		   and r.cancelRequestedAt is null
		   and (
		       r.status = :inProgressStatus
		       or (
		           r.status = :runningStatus
		           and r.leaseToken is not null
		           and r.leaseUntil is not null
		           and r.leaseUntil > :now
		           and r.leaseToken = :leaseToken
		       )
		   )
		""")
	int markWaitingRetry(@Param("runId") Long runId,
						 @Param("leaseToken") String leaseToken,
						 @Param("runningStatus") KnowledgeBaseRunStatus runningStatus,
						 @Param("inProgressStatus") KnowledgeBaseRunStatus inProgressStatus,
						 @Param("waitingRetryStatus") KnowledgeBaseRunStatus waitingRetryStatus,
						 @Param("nextRetryAt") LocalDateTime nextRetryAt,
						 @Param("error") String error,
						 @Param("errorCode") String errorCode,
						 @Param("requestId") String requestId,
						 @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update KnowledgeBaseRun r
		   set r.status = case when r.cancelRequestedAt is null then :queuedStatus else :canceledStatus end,
		       r.finishedAt = case when r.cancelRequestedAt is null then r.finishedAt else :now end,
		       r.leaseToken = null,
		       r.leaseUntil = null,
		       r.lastHeartbeatAt = null,
		       r.nextRetryAt = null,
		       r.updatedAt = :now
		 where r.status = :runningStatus
		   and r.leaseToken is not null
		   and r.leaseUntil is not null
		   and r.leaseUntil <= :now
		""")
	int recoverExpiredRunningLeases(@Param("runningStatus") KnowledgeBaseRunStatus runningStatus,
									 @Param("queuedStatus") KnowledgeBaseRunStatus queuedStatus,
									 @Param("canceledStatus") KnowledgeBaseRunStatus canceledStatus,
									 @Param("now") LocalDateTime now);

	@Query("""
		select distinct r.parentRun.runId from KnowledgeBaseRun r
		where r.parentRun is not null and r.status = :runningStatus and r.leaseUntil is not null and r.leaseUntil <= :now
		""")
	List<Long> findParentRunIdsForExpiredRunningLeases(@Param("runningStatus") KnowledgeBaseRunStatus runningStatus,
			@Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update KnowledgeBaseRun r
		   set r.status = :failedTimeoutStatus,
		       r.finishedAt = :now,
		       r.nextRetryAt = null,
		       r.error = :error,
		       r.errorCode = :errorCode,
		       r.leaseToken = null,
		       r.leaseUntil = null,
		       r.updatedAt = :now
		 where r.cancelRequestedAt is null
		   and r.startedAt <= :cutoff
		   and (
		       r.status = :inProgressStatus
		       or (
		           r.status = :runningStatus
		           and (
		               r.leaseToken is null
		               or r.leaseUntil is null
		               or r.leaseUntil <= :now
		           )
		       )
		   )
		""")
	int markTimedOutRuns(@Param("runningStatus") KnowledgeBaseRunStatus runningStatus,
						 @Param("inProgressStatus") KnowledgeBaseRunStatus inProgressStatus,
						 @Param("failedTimeoutStatus") KnowledgeBaseRunStatus failedTimeoutStatus,
						 @Param("cutoff") LocalDateTime cutoff,
						 @Param("error") String error,
						 @Param("errorCode") String errorCode,
							 @Param("now") LocalDateTime now);

	@Query("""
		select distinct r.parentRun.runId from KnowledgeBaseRun r
		where r.parentRun is not null and r.cancelRequestedAt is null and r.startedAt <= :cutoff
		 and (r.status = :inProgressStatus or (r.status = :runningStatus and (r.leaseToken is null or r.leaseUntil is null or r.leaseUntil <= :now)))
		""")
	List<Long> findParentRunIdsForTimedOutRuns(@Param("runningStatus") KnowledgeBaseRunStatus runningStatus,
			@Param("inProgressStatus") KnowledgeBaseRunStatus inProgressStatus, @Param("cutoff") LocalDateTime cutoff,
			@Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update KnowledgeBaseRun r
		   set r.attempts = coalesce(r.attempts, 0) + 1,
		       r.updatedAt = :now
		 where r.runId = :runId
		   and r.status = :runningStatus
		   and r.leaseToken = :leaseToken
		   and r.cancelRequestedAt is null
		   and r.leaseToken is not null
		   and r.leaseUntil is not null
		   and r.leaseUntil > :now
		""")
	int incrementRunningAttempt(@Param("runId") Long runId,
								   @Param("leaseToken") String leaseToken,
								   @Param("runningStatus") KnowledgeBaseRunStatus runningStatus,
							   @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update KnowledgeBaseRun r set r.currentStep = :step, r.updatedAt = :now
		where r.runId = :runId and r.status = :runningStatus and r.leaseToken = :leaseToken
		  and r.cancelRequestedAt is null and r.leaseUntil > :now
		""")
	int updateCurrentStep(@Param("runId") Long runId, @Param("leaseToken") String leaseToken,
					  @Param("runningStatus") KnowledgeBaseRunStatus runningStatus, @Param("step") String step,
					  @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update KnowledgeBaseRun r set r.currentStep = :step, r.actionPayload = :payload, r.updatedAt = :now
		where r.runId = :runId and r.status = :runningStatus and r.leaseToken = :leaseToken
		  and r.cancelRequestedAt is null and r.leaseUntil > :now
		""")
	int updateProgress(@Param("runId") Long runId, @Param("leaseToken") String leaseToken,
					   @Param("runningStatus") KnowledgeBaseRunStatus runningStatus, @Param("step") String step,
					   @Param("payload") String payload, @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update KnowledgeBaseRun p set p.status = :finalStatus, p.finishedAt = :now,
		 p.nextRetryAt = null, p.leaseToken = null, p.leaseUntil = null, p.updatedAt = :now
		where p.runId = :parentRunId and p.status in (:activeStatuses)
		 and not exists (select c from KnowledgeBaseRun c where c.parentRun.runId = p.runId and c.status not in (:terminalStatuses))
		""")
	int terminalizeParentWhenChildrenTerminal(@Param("parentRunId") Long parentRunId,
			@Param("activeStatuses") List<KnowledgeBaseRunStatus> activeStatuses,
			@Param("terminalStatuses") List<KnowledgeBaseRunStatus> terminalStatuses,
			@Param("finalStatus") KnowledgeBaseRunStatus finalStatus, @Param("now") LocalDateTime now);

	@Query("""
		select r from KnowledgeBaseRun r
		where r.status = :status and r.startedAt <= :cutoff
		""")
	List<KnowledgeBaseRun> findTimedOut(@Param("status") KnowledgeBaseRunStatus status,
										@Param("cutoff") LocalDateTime cutoff);
}
