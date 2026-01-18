package my.portfoliomanager.app.dto;

import java.time.LocalDateTime;

public record InstrumentEffectiveDto(
		String isin,
		String baseName,
		String baseInstrumentType,
		String baseAssetClass,
		String baseSubClass,
		Integer baseLayer,
		String baseLayerNotes,
		String overrideName,
		String overrideInstrumentType,
		String overrideAssetClass,
		String overrideSubClass,
		Integer overrideLayer,
		String overrideLayerNotes,
		String effectiveName,
		String effectiveInstrumentType,
		String effectiveAssetClass,
		String effectiveSubClass,
		Integer effectiveLayer,
		String effectiveLayerNotes,
		boolean classifiedByRule,
		Long appliedRuleId,
		boolean hasOverride,
		LocalDateTime effectiveUpdatedAt
) {
}
