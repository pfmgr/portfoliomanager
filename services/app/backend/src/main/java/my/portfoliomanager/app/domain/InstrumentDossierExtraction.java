package my.portfoliomanager.app.domain;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "instrument_dossier_extractions")
public class InstrumentDossierExtraction {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "extraction_id")
	private Long extractionId;

	@Column(name = "dossier_id", nullable = false)
	private Long dossierId;

	@Column(name = "model", nullable = false)
	private String model;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "extracted_json", nullable = false)
	private JsonNode extractedJson;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "missing_fields_json", nullable = false)
	private JsonNode missingFieldsJson;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "warnings_json", nullable = false)
	private JsonNode warningsJson;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private DossierExtractionStatus status;

	@Column(name = "error", columnDefinition = "TEXT")
	private String error;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "approved_by")
	private String approvedBy;

	@Column(name = "approved_at")
	private LocalDateTime approvedAt;

	@Column(name = "applied_by")
	private String appliedBy;

	@Column(name = "applied_at")
	private LocalDateTime appliedAt;

	@Column(name = "auto_approved", nullable = false)
	private boolean autoApproved;

	public Long getExtractionId() {
		return extractionId;
	}

	public void setExtractionId(Long extractionId) {
		this.extractionId = extractionId;
	}

	public Long getDossierId() {
		return dossierId;
	}

	public void setDossierId(Long dossierId) {
		this.dossierId = dossierId;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public JsonNode getExtractedJson() {
		return extractedJson;
	}

	public void setExtractedJson(JsonNode extractedJson) {
		this.extractedJson = extractedJson;
	}

	public JsonNode getMissingFieldsJson() {
		return missingFieldsJson;
	}

	public void setMissingFieldsJson(JsonNode missingFieldsJson) {
		this.missingFieldsJson = missingFieldsJson;
	}

	public JsonNode getWarningsJson() {
		return warningsJson;
	}

	public void setWarningsJson(JsonNode warningsJson) {
		this.warningsJson = warningsJson;
	}

	public DossierExtractionStatus getStatus() {
		return status;
	}

	public void setStatus(DossierExtractionStatus status) {
		this.status = status;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public String getApprovedBy() {
		return approvedBy;
	}

	public void setApprovedBy(String approvedBy) {
		this.approvedBy = approvedBy;
	}

	public LocalDateTime getApprovedAt() {
		return approvedAt;
	}

	public void setApprovedAt(LocalDateTime approvedAt) {
		this.approvedAt = approvedAt;
	}

	public String getAppliedBy() {
		return appliedBy;
	}

	public void setAppliedBy(String appliedBy) {
		this.appliedBy = appliedBy;
	}

	public LocalDateTime getAppliedAt() {
		return appliedAt;
	}

	public void setAppliedAt(LocalDateTime appliedAt) {
		this.appliedAt = appliedAt;
	}

	public boolean isAutoApproved() {
		return autoApproved;
	}

	public void setAutoApproved(boolean autoApproved) {
		this.autoApproved = autoApproved;
	}
}
