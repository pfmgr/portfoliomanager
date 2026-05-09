package my.portfoliomanager.app.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SavingPlanUpsertRequest(
		@NotNull Long depotId,
		@NotBlank @Size(max = 32) String isin,
		String name,
		@NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amountEur,
		@Size(max = 32) String frequency,
		@Min(1) @Max(31) Integer dayOfMonth,
		Boolean active,
		LocalDate lastChanged
) {
}
