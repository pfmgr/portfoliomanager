package my.portfoliomanager.app.domain;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "kb_config")
public class KnowledgeBaseConfig {
	@Id
	@Column(name = "id")
	private Integer id;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "config_json", nullable = false)
	private JsonNode configJson;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public JsonNode getConfigJson() {
		return configJson;
	}

	public void setConfigJson(JsonNode configJson) {
		this.configJson = configJson;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}
}
