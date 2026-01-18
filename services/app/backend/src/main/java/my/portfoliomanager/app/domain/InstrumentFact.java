package my.portfoliomanager.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "instrument_facts")
@IdClass(InstrumentFactId.class)
public class InstrumentFact {
	@Id
	@Column(name = "isin")
	private String isin;

	@Id
	@Column(name = "fact_key")
	private String factKey;

	@Id
	@Column(name = "as_of_date")
	private LocalDate asOfDate;

	@Column(name = "fact_value_text", columnDefinition = "TEXT")
	private String factValueText;

	@Column(name = "fact_value_num")
	private BigDecimal factValueNum;

	@Column(name = "unit")
	private String unit;

	@Column(name = "source_ref")
	private String sourceRef;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	public String getIsin() {
		return isin;
	}

	public void setIsin(String isin) {
		this.isin = isin;
	}

	public String getFactKey() {
		return factKey;
	}

	public void setFactKey(String factKey) {
		this.factKey = factKey;
	}

	public LocalDate getAsOfDate() {
		return asOfDate;
	}

	public void setAsOfDate(LocalDate asOfDate) {
		this.asOfDate = asOfDate;
	}

	public String getFactValueText() {
		return factValueText;
	}

	public void setFactValueText(String factValueText) {
		this.factValueText = factValueText;
	}

	public BigDecimal getFactValueNum() {
		return factValueNum;
	}

	public void setFactValueNum(BigDecimal factValueNum) {
		this.factValueNum = factValueNum;
	}

	public String getUnit() {
		return unit;
	}

	public void setUnit(String unit) {
		this.unit = unit;
	}

	public String getSourceRef() {
		return sourceRef;
	}

	public void setSourceRef(String sourceRef) {
		this.sourceRef = sourceRef;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}
}
