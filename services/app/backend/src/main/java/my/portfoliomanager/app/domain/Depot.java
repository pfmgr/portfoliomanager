package my.portfoliomanager.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "depots")
public class Depot {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "depot_id")
	private Long depotId;

	@Column(name = "depot_code", nullable = false, unique = true)
	private String depotCode;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "provider", nullable = false)
	private String provider;

	@Column(name = "active_snapshot_id")
	private Long activeSnapshotId;

	public Long getDepotId() {
		return depotId;
	}

	public void setDepotId(Long depotId) {
		this.depotId = depotId;
	}

	public String getDepotCode() {
		return depotCode;
	}

	public void setDepotCode(String depotCode) {
		this.depotCode = depotCode;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public Long getActiveSnapshotId() {
		return activeSnapshotId;
	}

	public void setActiveSnapshotId(Long activeSnapshotId) {
		this.activeSnapshotId = activeSnapshotId;
	}
}
