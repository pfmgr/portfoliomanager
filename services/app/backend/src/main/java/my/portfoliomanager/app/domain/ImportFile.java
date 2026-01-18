package my.portfoliomanager.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "import_files")
public class ImportFile {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "file_id")
	private Long fileId;

	@Column(name = "depot_code", nullable = false)
	private String depotCode;

	@Column(name = "source", nullable = false)
	private String source;

	@Column(name = "filename", nullable = false)
	private String filename;

	@Column(name = "file_hash", nullable = false)
	private String fileHash;

	@Column(name = "as_of_date")
	private LocalDate asOfDate;

	@Column(name = "imported_at", nullable = false)
	private LocalDateTime importedAt;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "error")
	private String error;

	public Long getFileId() {
		return fileId;
	}

	public void setFileId(Long fileId) {
		this.fileId = fileId;
	}

	public String getDepotCode() {
		return depotCode;
	}

	public void setDepotCode(String depotCode) {
		this.depotCode = depotCode;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public String getFilename() {
		return filename;
	}

	public void setFilename(String filename) {
		this.filename = filename;
	}

	public String getFileHash() {
		return fileHash;
	}

	public void setFileHash(String fileHash) {
		this.fileHash = fileHash;
	}

	public LocalDate getAsOfDate() {
		return asOfDate;
	}

	public void setAsOfDate(LocalDate asOfDate) {
		this.asOfDate = asOfDate;
	}

	public LocalDateTime getImportedAt() {
		return importedAt;
	}

	public void setImportedAt(LocalDateTime importedAt) {
		this.importedAt = importedAt;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}
}
