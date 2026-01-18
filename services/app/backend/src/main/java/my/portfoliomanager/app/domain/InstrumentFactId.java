package my.portfoliomanager.app.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

public class InstrumentFactId implements Serializable {
	private String isin;
	private String factKey;
	private LocalDate asOfDate;

	public InstrumentFactId() {
	}

	public InstrumentFactId(String isin, String factKey, LocalDate asOfDate) {
		this.isin = isin;
		this.factKey = factKey;
		this.asOfDate = asOfDate;
	}

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

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof InstrumentFactId that)) {
			return false;
		}
		return Objects.equals(isin, that.isin)
				&& Objects.equals(factKey, that.factKey)
				&& Objects.equals(asOfDate, that.asOfDate);
	}

	@Override
	public int hashCode() {
		return Objects.hash(isin, factKey, asOfDate);
	}
}
