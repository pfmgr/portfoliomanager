package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.Depot;
import my.portfoliomanager.app.domain.Instrument;
import my.portfoliomanager.app.dto.ClassificationDto;
import my.portfoliomanager.app.repository.InstrumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;

@Service
public class InstrumentMaterializationService {
	private final InstrumentRepository instrumentRepository;
	private final KnowledgeBaseClassificationService knowledgeBaseClassificationService;

	public InstrumentMaterializationService(InstrumentRepository instrumentRepository,
									   KnowledgeBaseClassificationService knowledgeBaseClassificationService) {
		this.instrumentRepository = instrumentRepository;
		this.knowledgeBaseClassificationService = knowledgeBaseClassificationService;
	}

	@Transactional
	public MaterializationResult ensureInstrument(String isin,
									 Depot depot,
									 Integer preferredLayer,
									 String preferredName) {
		String normalizedIsin = normalizeIsin(isin);
		if (depot == null) {
			throw new IllegalArgumentException("Depot not found");
		}
		if (normalizedIsin == null) {
			throw new IllegalArgumentException("ISIN is required");
		}

		Instrument instrument = instrumentRepository.findById(normalizedIsin).orElse(null);
		KnowledgeBaseClassificationService.Suggestion suggestion = knowledgeBaseClassificationService.findSuggestion(normalizedIsin);
		ClassificationDto classification = suggestion == null ? null : suggestion.classification();

		boolean created = false;
		boolean reactivated = false;
		if (instrument == null) {
			instrument = new Instrument();
			instrument.setIsin(normalizedIsin);
			created = true;
		} else if (instrument.isDeleted()) {
			instrument.setDeleted(false);
			reactivated = true;
		}

		String resolvedName = firstNonBlank(suggestion == null ? null : suggestion.name(), preferredName, instrument.getName());
		Integer resolvedLayer = normalizeLayer(firstNonNull(preferredLayer, classification == null ? null : classification.layer(), instrument.getLayer()));
		String instrumentType = firstNonBlank(classification == null ? null : classification.instrumentType(), instrument.getInstrumentType());
		String assetClass = firstNonBlank(classification == null ? null : classification.assetClass(), instrument.getAssetClass());
		String subClass = firstNonBlank(classification == null ? null : classification.subClass(), instrument.getSubClass());
		String layerNotes = firstNonBlank(suggestion == null ? null : suggestion.layerNotes(), instrument.getLayerNotes());

		if (resolvedName == null) {
			throw new IllegalArgumentException("Knowledge Base is missing a name for ISIN " + normalizedIsin);
		}
		if (resolvedLayer == null) {
			throw new IllegalArgumentException("Knowledge Base is missing a valid layer for ISIN " + normalizedIsin);
		}

		boolean layerChanged = instrument.getLayer() == null || !instrument.getLayer().equals(resolvedLayer);
		instrument.setName(resolvedName);
		if (created || reactivated || instrument.getDepotCode() == null || instrument.getDepotCode().isBlank()) {
			instrument.setDepotCode(depot.getDepotCode());
		}
		instrument.setInstrumentType(instrumentType);
		instrument.setAssetClass(assetClass);
		instrument.setSubClass(subClass);
		instrument.setLayer(resolvedLayer);
		if (layerChanged) {
			instrument.setLayerLastChanged(LocalDate.now());
		}
		instrument.setLayerNotes(layerNotes);
		instrument.setDeleted(false);

		Instrument saved = instrumentRepository.save(instrument);
		return new MaterializationResult(saved, created, reactivated);
	}

	private String normalizeIsin(String isin) {
		if (isin == null) {
			return null;
		}
		String value = isin.trim().toUpperCase(Locale.ROOT);
		return value.isEmpty() ? null : value;
	}

	private Integer normalizeLayer(Integer layer) {
		if (layer == null || layer < 1 || layer > 5) {
			return null;
		}
		return layer;
	}

	private <T> T firstNonNull(T... values) {
		for (T value : values) {
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null) {
				String trimmed = value.trim();
				if (!trimmed.isEmpty()) {
					return trimmed;
				}
			}
		}
		return null;
	}

	public record MaterializationResult(Instrument instrument, boolean created, boolean reactivated) {
	}
}
