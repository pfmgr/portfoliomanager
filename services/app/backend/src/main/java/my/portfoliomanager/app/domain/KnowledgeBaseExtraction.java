package my.portfoliomanager.app.domain;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_base_extractions")
public class KnowledgeBaseExtraction {
	@Id
	@Column(name = "isin")
	private String isin;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private KnowledgeBaseExtractionStatus status;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "extracted_json", nullable = false)
	private JsonNode extractedJson;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	public String getIsin() {
		return isin;
	}

	public void setIsin(String isin) {
		this.isin = isin;
	}

	public KnowledgeBaseExtractionStatus getStatus() {
		return status;
	}

	public void setStatus(KnowledgeBaseExtractionStatus status) {
		this.status = status;
	}

	public JsonNode getExtractedJson() {
		return extractedJson;
	}

	public void setExtractedJson(JsonNode extractedJson) {
		this.extractedJson = extractedJson;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}
}
