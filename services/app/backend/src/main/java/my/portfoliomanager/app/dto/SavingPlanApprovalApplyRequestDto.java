package my.portfoliomanager.app.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SavingPlanApprovalApplyRequestDto(
		@NotBlank String source,
		@NotEmpty List<@Valid SavingPlanApprovalApplyItemDto> items
) {
}
