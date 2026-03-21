package my.portfoliomanager.app.repository.projection;

import java.time.LocalDateTime;

public interface InstrumentDossierSearchProjection {
	String getIsin();
	String getName();
	Integer getEffectiveLayer();
	Long getDossierId();
	String getDossierStatus();
	LocalDateTime getDossierUpdatedAt();
	Integer getDossierVersion();
	LocalDateTime getDossierApprovedAt();
	String getApprovalStatus();
	String getLatestExtractionStatus();
	String getBlacklistScope();
	Boolean getBlacklistPendingChange();
	Boolean getHasApprovedDossier();
	Boolean getHasApprovedExtraction();
	Boolean getStale();
	String getExtractionFreshness();
}
