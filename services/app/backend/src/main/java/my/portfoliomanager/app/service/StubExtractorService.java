package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.InstrumentDossier;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class StubExtractorService implements ExtractorService {
	private final DossierPreParser preParser;

	public StubExtractorService(DossierPreParser preParser) {
		this.preParser = preParser;
	}

	@Override
	public ExtractionResult extract(InstrumentDossier dossier) {
		InstrumentDossierExtractionPayload payload = preParser.parse(dossier);
		List<InstrumentDossierExtractionPayload.WarningPayload> warnings = new ArrayList<>();
		if (payload != null && payload.warnings() != null) {
			warnings.addAll(payload.warnings());
		}
		warnings.add(new InstrumentDossierExtractionPayload.WarningPayload("LLM extraction disabled; parser-only result."));
		InstrumentDossierExtractionPayload updated = payload == null
				? null
				: new InstrumentDossierExtractionPayload(
						payload.isin(),
						payload.name(),
						payload.instrumentType(),
						payload.assetClass(),
						payload.subClass(),
						payload.layer(),
						payload.layerNotes(),
						payload.etf(),
						payload.risk(),
						payload.regions(),
						payload.topHoldings(),
						payload.financials(),
						payload.valuation(),
						payload.sources(),
						payload.missingFields(),
						warnings
				);
		return new ExtractionResult(updated, "parser");
	}
}
