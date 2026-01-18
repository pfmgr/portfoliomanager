package my.portfoliomanager.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "kb_runs")
public class KnowledgeBaseRun {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "run_id")
	private Long runId;

	@Column(name = "isin", nullable = false)
	private String isin;

	@Enumerated(EnumType.STRING)
	@Column(name = "action", nullable = false)
	private KnowledgeBaseRunAction action;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private KnowledgeBaseRunStatus status;

	@Column(name = "started_at", nullable = false)
	private LocalDateTime startedAt;

	@Column(name = "finished_at")
	private LocalDateTime finishedAt;

	@Column(name = "attempts", nullable = false)
	private Integer attempts;

	@Column(name = "error", columnDefinition = "TEXT")
	private String error;

	@Column(name = "batch_id")
	private String batchId;

	@Column(name = "request_id")
	private String requestId;

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

	public LocalDateTime getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(LocalDateTime startedAt) {
		this.startedAt = startedAt;
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
}
