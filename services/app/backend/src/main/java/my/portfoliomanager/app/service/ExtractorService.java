package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.InstrumentDossier;

public interface ExtractorService {
	ExtractionResult extract(InstrumentDossier dossier);
}
