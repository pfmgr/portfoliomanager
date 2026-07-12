package my.portfoliomanager.app.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Immutable audit event for a durable KB run. */
@Entity
@Table(name = "kb_run_steps")
public class KnowledgeBaseRunStep {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "step_id") private Long stepId;
	@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "run_id", nullable = false)
	private KnowledgeBaseRun run;
	@Column(name = "sequence_no", nullable = false) private Integer sequenceNo;
	@Column(name = "step", nullable = false) private String step;
	@Column(name = "outcome", nullable = false) private String outcome;
	@Column(name = "details", columnDefinition = "TEXT") private String details;
	@Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

	public Long getStepId() { return stepId; }
	public KnowledgeBaseRun getRun() { return run; }
	public void setRun(KnowledgeBaseRun run) { this.run = run; }
	public Integer getSequenceNo() { return sequenceNo; }
	public void setSequenceNo(Integer sequenceNo) { this.sequenceNo = sequenceNo; }
	public String getStep() { return step; }
	public void setStep(String step) { this.step = step; }
	public String getOutcome() { return outcome; }
	public void setOutcome(String outcome) { this.outcome = outcome; }
	public String getDetails() { return details; }
	public void setDetails(String details) { this.details = details; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
