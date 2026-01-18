package my.portfoliomanager.app.dto;

public record InstrumentDossierWebsearchJobResponseDto(
		String jobId,
		InstrumentDossierWebsearchJobStatus status,
		InstrumentDossierWebsearchResponseDto result,
		String error
) {
}

