package my.portfoliomanager.app.service;

import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;

public record ExtractionResult(
		InstrumentDossierExtractionPayload payload,
		String model
) {
}
