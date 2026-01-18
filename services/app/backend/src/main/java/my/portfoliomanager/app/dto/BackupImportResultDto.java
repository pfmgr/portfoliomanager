package my.portfoliomanager.app.dto;

public record BackupImportResultDto(int tablesImported,
									long rowsImported,
									int formatVersion,
									String exportedAt) {
}
