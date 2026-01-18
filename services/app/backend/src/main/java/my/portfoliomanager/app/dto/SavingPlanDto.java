package my.portfoliomanager.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SavingPlanDto(Long savingPlanId,
						  Long depotId,
						  String depotCode,
						  String depotName,
						  String isin,
						  String name,
						  BigDecimal amountEur,
						  String frequency,
						  Integer dayOfMonth,
						  boolean active,
						  LocalDate lastChanged,
						  Integer layer) {
}
