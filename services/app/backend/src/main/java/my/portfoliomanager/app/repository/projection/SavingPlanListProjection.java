package my.portfoliomanager.app.repository.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface SavingPlanListProjection {
	Long getSavingPlanId();
	Long getDepotId();
	String getDepotCode();
	String getDepotName();
	String getIsin();
	String getName();
	BigDecimal getAmountEur();
	String getFrequency();
	Integer getDayOfMonth();
	Boolean getActive();
	LocalDate getLastChanged();
	Integer getLayer();
}
