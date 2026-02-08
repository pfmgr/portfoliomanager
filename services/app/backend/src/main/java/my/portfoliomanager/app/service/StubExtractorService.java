package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.InstrumentDossier;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import org.springframework.stereotype.Service;

@Service
public class StubExtractorService implements ExtractorService {
	private final DossierPreParser preParser;

	public StubExtractorService(DossierPreParser preParser) {
		this.preParser = preParser;
	}

	@Override
	public ExtractionResult extract(InstrumentDossier dossier) {
		InstrumentDossierExtractionPayload payload = preParser.parse(dossier);
		return new ExtractionResult(payload, "parser");
	}
}
