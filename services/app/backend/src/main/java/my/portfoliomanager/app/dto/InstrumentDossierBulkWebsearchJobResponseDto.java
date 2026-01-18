package my.portfoliomanager.app.dto;

public record InstrumentDossierBulkWebsearchJobResponseDto(
		String jobId,
		InstrumentDossierBulkWebsearchJobStatus status,
		InstrumentDossierBulkWebsearchResultDto result,
		String error
) {
}

