package my.portfoliomanager.app.dto;

public record OverridesImportResultDto(int imported,
									   int skippedMissing,
									   int skippedEmpty) {
}
