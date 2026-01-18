package my.portfoliomanager.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "snapshot_positions")
public class SnapshotPosition {
	@EmbeddedId
	private SnapshotPositionId id;

	@Column(name = "name")
	private String name;

	@Column(name = "shares")
	private BigDecimal shares;

	@Column(name = "value_eur")
	private BigDecimal valueEur;

	@Column(name = "currency")
	private String currency;

	public SnapshotPositionId getId() {
		return id;
	}

	public void setId(SnapshotPositionId id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public BigDecimal getShares() {
		return shares;
	}

	public void setShares(BigDecimal shares) {
		this.shares = shares;
	}

	public BigDecimal getValueEur() {
		return valueEur;
	}

	public void setValueEur(BigDecimal valueEur) {
		this.valueEur = valueEur;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}
}
