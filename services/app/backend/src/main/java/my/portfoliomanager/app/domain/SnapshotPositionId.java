package my.portfoliomanager.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class SnapshotPositionId implements Serializable {
	@Column(name = "snapshot_id")
	private Long snapshotId;

	@Column(name = "isin")
	private String isin;

	public SnapshotPositionId() {
	}

	public SnapshotPositionId(Long snapshotId, String isin) {
		this.snapshotId = snapshotId;
		this.isin = isin;
	}

	public Long getSnapshotId() {
		return snapshotId;
	}

	public void setSnapshotId(Long snapshotId) {
		this.snapshotId = snapshotId;
	}

	public String getIsin() {
		return isin;
	}

	public void setIsin(String isin) {
		this.isin = isin;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		SnapshotPositionId that = (SnapshotPositionId) o;
		return Objects.equals(snapshotId, that.snapshotId) && Objects.equals(isin, that.isin);
	}

	@Override
	public int hashCode() {
		return Objects.hash(snapshotId, isin);
	}
}
