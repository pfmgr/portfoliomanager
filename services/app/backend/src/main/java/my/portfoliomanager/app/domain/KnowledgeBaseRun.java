package my.portfoliomanager.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.LocalDateTime;

@Entity
@Table(name = "kb_runs")
public class KnowledgeBaseRun {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "run_id")
	private Long runId;

	@Column(name = "isin")
	private String isin;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_run_id")
	private KnowledgeBaseRun parentRun;

	@Column(name = "idempotency_key", unique = true)
	private String idempotencyKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "action", nullable = false)
	private KnowledgeBaseRunAction action;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private KnowledgeBaseRunStatus status;

	@Column(name = "current_step")
	private String currentStep;

	@Column(name = "started_at", nullable = false)
	private LocalDateTime startedAt;

	@Column(name = "next_retry_at")
	private LocalDateTime nextRetryAt;

	@Column(name = "lease_token")
	private String leaseToken;

	@Column(name = "lease_until")
	private LocalDateTime leaseUntil;

	@Column(name = "last_heartbeat_at")
	private LocalDateTime lastHeartbeatAt;

	@Column(name = "cancel_requested_at")
	private LocalDateTime cancelRequestedAt;

	@Column(name = "finished_at")
	private LocalDateTime finishedAt;

	@Column(name = "attempts", nullable = false)
	private Integer attempts;

	@Column(name = "error", columnDefinition = "TEXT")
	private String error;

	@Column(name = "error_code")
	private String errorCode;

	@Column(name = "batch_id")
	private String batchId;

	@Column(name = "request_id")
	private String requestId;

	/** Bounded, sanitized action context and UI result; never provider prompts or responses. */
	@Column(name = "action_payload", columnDefinition = "TEXT")
	private String actionPayload;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@PrePersist
	void prePersist() {
		LocalDateTime now = LocalDateTime.now();
		if (createdAt == null) {
			createdAt = now;
		}
		updatedAt = now;
	}

	@PreUpdate
	void preUpdate() {
		updatedAt = LocalDateTime.now();
	}

	public Long getRunId() {
		return runId;
	}

	public void setRunId(Long runId) {
		this.runId = runId;
	}

	public String getIsin() {
		return isin;
	}

	public void setIsin(String isin) {
		this.isin = isin;
	}

	public KnowledgeBaseRun getParentRun() {
		return parentRun;
	}

	public void setParentRun(KnowledgeBaseRun parentRun) {
		this.parentRun = parentRun;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public void setIdempotencyKey(String idempotencyKey) {
		this.idempotencyKey = idempotencyKey;
	}

	public KnowledgeBaseRunAction getAction() {
		return action;
	}

	public void setAction(KnowledgeBaseRunAction action) {
		this.action = action;
	}

	public KnowledgeBaseRunStatus getStatus() {
		return status;
	}

	public void setStatus(KnowledgeBaseRunStatus status) {
		this.status = status;
	}

	public String getCurrentStep() {
		return currentStep;
	}

	public void setCurrentStep(String currentStep) {
		this.currentStep = currentStep;
	}

	public LocalDateTime getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(LocalDateTime startedAt) {
		this.startedAt = startedAt;
	}

	public LocalDateTime getNextRetryAt() {
		return nextRetryAt;
	}

	public void setNextRetryAt(LocalDateTime nextRetryAt) {
		this.nextRetryAt = nextRetryAt;
	}

	public String getLeaseToken() {
		return leaseToken;
	}

	public void setLeaseToken(String leaseToken) {
		this.leaseToken = leaseToken;
	}

	public LocalDateTime getLeaseUntil() {
		return leaseUntil;
	}

	public void setLeaseUntil(LocalDateTime leaseUntil) {
		this.leaseUntil = leaseUntil;
	}

	public LocalDateTime getLastHeartbeatAt() {
		return lastHeartbeatAt;
	}

	public void setLastHeartbeatAt(LocalDateTime lastHeartbeatAt) {
		this.lastHeartbeatAt = lastHeartbeatAt;
	}

	public LocalDateTime getCancelRequestedAt() {
		return cancelRequestedAt;
	}

	public void setCancelRequestedAt(LocalDateTime cancelRequestedAt) {
		this.cancelRequestedAt = cancelRequestedAt;
	}

	public LocalDateTime getFinishedAt() {
		return finishedAt;
	}

	public void setFinishedAt(LocalDateTime finishedAt) {
		this.finishedAt = finishedAt;
	}

	public Integer getAttempts() {
		return attempts;
	}

	public void setAttempts(Integer attempts) {
		this.attempts = attempts;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public void setErrorCode(String errorCode) {
		this.errorCode = errorCode;
	}

	public String getBatchId() {
		return batchId;
	}

	public void setBatchId(String batchId) {
		this.batchId = batchId;
	}

	public String getRequestId() {
		return requestId;
	}

	public void setRequestId(String requestId) {
		this.requestId = requestId;
	}

	public String getActionPayload() { return actionPayload; }

	public void setActionPayload(String actionPayload) { this.actionPayload = actionPayload; }

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}
}
