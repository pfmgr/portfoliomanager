package my.portfoliomanager.app.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SavingPlanUpsertRequest(
		@NotNull Long depotId,
		@NotBlank String isin,
		String name,
		@NotNull @DecimalMin("0.01") BigDecimal amountEur,
		String frequency,
		Integer dayOfMonth,
		Boolean active,
		LocalDate lastChanged
) {
}
