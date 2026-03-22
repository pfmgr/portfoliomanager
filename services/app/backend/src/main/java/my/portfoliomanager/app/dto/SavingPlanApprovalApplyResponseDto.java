package my.portfoliomanager.app.dto;

public record SavingPlanApprovalApplyResponseDto(
		int applied,
		int created,
		int updated,
		int deactivated,
		int skipped,
		int instrumentsCreated,
		int instrumentsReactivated
) {
}
