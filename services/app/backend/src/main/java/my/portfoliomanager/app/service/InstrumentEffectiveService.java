package my.portfoliomanager.app.service;

import my.portfoliomanager.app.dto.InstrumentEffectiveDto;
import my.portfoliomanager.app.dto.InstrumentEffectivePageDto;
import my.portfoliomanager.app.repository.InstrumentRepository;
import my.portfoliomanager.app.repository.projection.InstrumentEffectiveProjection;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class InstrumentEffectiveService {
	private static final int MAX_LIMIT = 1000;
	private final InstrumentRepository instrumentRepository;

	public InstrumentEffectiveService(InstrumentRepository instrumentRepository) {
		this.instrumentRepository = instrumentRepository;
	}

	public InstrumentEffectivePageDto listEffective(String query, boolean onlyOverrides, int limit, int offset) {
		int finalLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
		int finalOffset = Math.max(offset, 0);
		String normalizedQuery = normalizeQuery(query);
		List<InstrumentEffectiveProjection> rows = instrumentRepository.findEffective(normalizedQuery, onlyOverrides, finalLimit, finalOffset);
		long total = instrumentRepository.countEffective(normalizedQuery, onlyOverrides);
		List<InstrumentEffectiveDto> items = rows.stream().map(this::toDto).toList();
		return new InstrumentEffectivePageDto(items, Math.toIntExact(total), finalLimit, finalOffset);
	}

	private InstrumentEffectiveDto toDto(InstrumentEffectiveProjection row) {
		return new InstrumentEffectiveDto(
				row.getIsin(),
				row.getBaseName(),
				row.getBaseInstrumentType(),
				row.getBaseAssetClass(),
				row.getBaseSubClass(),
				row.getBaseLayer(),
				row.getBaseLayerNotes(),
				row.getOverrideName(),
				row.getOverrideInstrumentType(),
				row.getOverrideAssetClass(),
				row.getOverrideSubClass(),
				row.getOverrideLayer(),
				row.getOverrideLayerNotes(),
				row.getEffectiveName(),
				row.getEffectiveInstrumentType(),
				row.getEffectiveAssetClass(),
				row.getEffectiveSubClass(),
				row.getEffectiveLayer(),
				row.getEffectiveLayerNotes(),
				Boolean.TRUE.equals(row.getClassifiedByRule()),
				row.getAppliedRuleId(),
				Boolean.TRUE.equals(row.getHasOverride()),
				row.getEffectiveUpdatedAt()
		);
	}

	private String normalizeQuery(String query) {
		if (query == null) {
			return null;
		}
		String trimmed = query.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		return trimmed.toLowerCase(Locale.ROOT);
	}
}
