package my.portfoliomanager.app.dto;

public record SavingPlanImportResultDto(int created,
									  int updated,
									  int skippedMissing,
									  int skippedUnchanged,
									  int skippedEmpty) {
}
