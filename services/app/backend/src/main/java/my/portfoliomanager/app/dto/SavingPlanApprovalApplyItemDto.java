package my.portfoliomanager.app.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record SavingPlanApprovalApplyItemDto(
		Long savingPlanId,
		Long depotId,
		@NotBlank String isin,
		String instrumentName,
		Integer layer,
		@NotNull @DecimalMin("0.00") BigDecimal targetAmountEur,
		String rationale
) {
}
