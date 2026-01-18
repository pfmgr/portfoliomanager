package my.portfoliomanager.app.dto;

public record ImportResultDto(int instrumentsImported,
							  String snapshotStatus,
							  Long snapshotId,
							  int positions,
							  int rulesApplied) {
}
