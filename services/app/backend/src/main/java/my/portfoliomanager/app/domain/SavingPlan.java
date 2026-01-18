package my.portfoliomanager.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "sparplans")
public class SavingPlan {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "sparplan_id")
	private Long savingPlanId;

	@Column(name = "depot_id", nullable = false)
	private Long depotId;

	@Column(name = "isin", nullable = false)
	private String isin;

	@Column(name = "name")
	private String name;

	@Column(name = "amount_eur", nullable = false)
	private BigDecimal amountEur;

	@Column(name = "frequency", nullable = false)
	private String frequency;

	@Column(name = "day_of_month")
	private Integer dayOfMonth;

	@Column(name = "active", nullable = false)
	private boolean active;

	@Column(name = "last_changed")
	private LocalDate lastChanged;

	public Long getSavingPlanId() {
		return savingPlanId;
	}

	public void setSavingPlanId(Long savingPlanId) {
		this.savingPlanId = savingPlanId;
	}

	public Long getDepotId() {
		return depotId;
	}

	public void setDepotId(Long depotId) {
		this.depotId = depotId;
	}

	public String getIsin() {
		return isin;
	}

	public void setIsin(String isin) {
		this.isin = isin;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public BigDecimal getAmountEur() {
		return amountEur;
	}

	public void setAmountEur(BigDecimal amountEur) {
		this.amountEur = amountEur;
	}

	public String getFrequency() {
		return frequency;
	}

	public void setFrequency(String frequency) {
		this.frequency = frequency;
	}

	public Integer getDayOfMonth() {
		return dayOfMonth;
	}

	public void setDayOfMonth(Integer dayOfMonth) {
		this.dayOfMonth = dayOfMonth;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public LocalDate getLastChanged() {
		return lastChanged;
	}

	public void setLastChanged(LocalDate lastChanged) {
		this.lastChanged = lastChanged;
	}
}
