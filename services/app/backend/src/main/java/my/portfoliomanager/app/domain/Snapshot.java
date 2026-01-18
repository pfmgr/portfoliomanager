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
@Table(name = "snapshots")
public class Snapshot {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "snapshot_id")
	private Long snapshotId;

	@Column(name = "depot_id", nullable = false)
	private Long depotId;

	@Column(name = "as_of_date", nullable = false)
	private LocalDate asOfDate;

	@Column(name = "source", nullable = false)
	private String source;

	@Column(name = "file_hash", nullable = false)
	private String fileHash;

	@Column(name = "imported_at", nullable = false)
	private LocalDateTime importedAt;

	public Long getSnapshotId() {
		return snapshotId;
	}

	public void setSnapshotId(Long snapshotId) {
		this.snapshotId = snapshotId;
	}

	public Long getDepotId() {
		return depotId;
	}

	public void setDepotId(Long depotId) {
		this.depotId = depotId;
	}

	public LocalDate getAsOfDate() {
		return asOfDate;
	}

	public void setAsOfDate(LocalDate asOfDate) {
		this.asOfDate = asOfDate;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public String getFileHash() {
		return fileHash;
	}

	public void setFileHash(String fileHash) {
		this.fileHash = fileHash;
	}

	public LocalDateTime getImportedAt() {
		return importedAt;
	}

	public void setImportedAt(LocalDateTime importedAt) {
		this.importedAt = importedAt;
	}
}
