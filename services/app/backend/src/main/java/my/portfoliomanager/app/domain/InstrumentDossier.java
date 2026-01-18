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
@Table(name = "instrument_dossiers")
public class InstrumentDossier {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "dossier_id")
	private Long dossierId;

	@Column(name = "isin", nullable = false)
	private String isin;

	@Column(name = "display_name")
	private String displayName;

	@Column(name = "created_by", nullable = false)
	private String createdBy;

	@Enumerated(EnumType.STRING)
	@Column(name = "origin", nullable = false)
	private DossierOrigin origin;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private DossierStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "authored_by", nullable = false)
	private DossierAuthoredBy authoredBy;

	@Column(name = "version", nullable = false)
	private Integer version;

	@Column(name = "content_md", nullable = false, columnDefinition = "TEXT")
	private String contentMd;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "citations_json", nullable = false)
	private JsonNode citationsJson;

	@Column(name = "content_hash", nullable = false, length = 64)
	private String contentHash;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Column(name = "approved_by")
	private String approvedBy;

	@Column(name = "approved_at")
	private LocalDateTime approvedAt;

	@Column(name = "auto_approved", nullable = false)
	private boolean autoApproved;

	@Column(name = "supersedes_id")
	private Long supersedesId;

	public Long getDossierId() {
		return dossierId;
	}

	public void setDossierId(Long dossierId) {
		this.dossierId = dossierId;
	}

	public String getIsin() {
		return isin;
	}

	public void setIsin(String isin) {
		this.isin = isin;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(String createdBy) {
		this.createdBy = createdBy;
	}

	public DossierOrigin getOrigin() {
		return origin;
	}

	public void setOrigin(DossierOrigin origin) {
		this.origin = origin;
	}

	public DossierStatus getStatus() {
		return status;
	}

	public void setStatus(DossierStatus status) {
		this.status = status;
	}

	public DossierAuthoredBy getAuthoredBy() {
		return authoredBy;
	}

	public void setAuthoredBy(DossierAuthoredBy authoredBy) {
		this.authoredBy = authoredBy;
	}

	public Integer getVersion() {
		return version;
	}

	public void setVersion(Integer version) {
		this.version = version;
	}

	public String getContentMd() {
		return contentMd;
	}

	public void setContentMd(String contentMd) {
		this.contentMd = contentMd;
	}

	public JsonNode getCitationsJson() {
		return citationsJson;
	}

	public void setCitationsJson(JsonNode citationsJson) {
		this.citationsJson = citationsJson;
	}

	public String getContentHash() {
		return contentHash;
	}

	public void setContentHash(String contentHash) {
		this.contentHash = contentHash;
	}

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

	public boolean isAutoApproved() {
		return autoApproved;
	}

	public void setAutoApproved(boolean autoApproved) {
		this.autoApproved = autoApproved;
	}

	public Long getSupersedesId() {
		return supersedesId;
	}

	public void setSupersedesId(Long supersedesId) {
		this.supersedesId = supersedesId;
	}
}
