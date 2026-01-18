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
@Table(name = "kb_alternatives")
public class KnowledgeBaseAlternative {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "alternative_id")
	private Long alternativeId;

	@Column(name = "base_isin", nullable = false)
	private String baseIsin;

	@Column(name = "alt_isin", nullable = false)
	private String altIsin;

	@Column(name = "rationale", columnDefinition = "TEXT")
	private String rationale;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "sources_json", nullable = false)
	private JsonNode sourcesJson;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private KnowledgeBaseAlternativeStatus status;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public Long getAlternativeId() {
		return alternativeId;
	}

	public void setAlternativeId(Long alternativeId) {
		this.alternativeId = alternativeId;
	}

	public String getBaseIsin() {
		return baseIsin;
	}

	public void setBaseIsin(String baseIsin) {
		this.baseIsin = baseIsin;
	}

	public String getAltIsin() {
		return altIsin;
	}

	public void setAltIsin(String altIsin) {
		this.altIsin = altIsin;
	}

	public String getRationale() {
		return rationale;
	}

	public void setRationale(String rationale) {
		this.rationale = rationale;
	}

	public JsonNode getSourcesJson() {
		return sourcesJson;
	}

	public void setSourcesJson(JsonNode sourcesJson) {
		this.sourcesJson = sourcesJson;
	}

	public KnowledgeBaseAlternativeStatus getStatus() {
		return status;
	}

	public void setStatus(KnowledgeBaseAlternativeStatus status) {
		this.status = status;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}
}
