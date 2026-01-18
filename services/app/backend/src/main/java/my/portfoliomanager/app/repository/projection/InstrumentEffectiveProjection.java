package my.portfoliomanager.app.repository.projection;

import java.time.LocalDateTime;

public interface InstrumentEffectiveProjection {
	String getIsin();
	String getBaseName();
	String getBaseInstrumentType();
	String getBaseAssetClass();
	String getBaseSubClass();
	Integer getBaseLayer();
	String getBaseLayerNotes();
	String getOverrideName();
	String getOverrideInstrumentType();
	String getOverrideAssetClass();
	String getOverrideSubClass();
	Integer getOverrideLayer();
	String getOverrideLayerNotes();
	String getEffectiveName();
	String getEffectiveInstrumentType();
	String getEffectiveAssetClass();
	String getEffectiveSubClass();
	Integer getEffectiveLayer();
	String getEffectiveLayerNotes();
	Boolean getClassifiedByRule();
	Long getAppliedRuleId();
	Boolean getHasOverride();
	LocalDateTime getEffectiveUpdatedAt();
}
