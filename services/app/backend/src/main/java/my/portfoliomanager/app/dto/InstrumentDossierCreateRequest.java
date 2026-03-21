package my.portfoliomanager.app.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import my.portfoliomanager.app.domain.DossierOrigin;
import my.portfoliomanager.app.domain.DossierStatus;
import my.portfoliomanager.app.domain.InstrumentBlacklistScope;

public record InstrumentDossierCreateRequest(
		@NotBlank String isin,
		String displayName,
		@NotBlank String contentMd,
		@NotNull DossierOrigin origin,
		@NotNull DossierStatus status,
		@NotNull JsonNode citations,
		InstrumentBlacklistScope blacklistScope
) {
	public InstrumentDossierCreateRequest(String isin,
								 String displayName,
								 String contentMd,
								 DossierOrigin origin,
								 DossierStatus status,
								 JsonNode citations) {
		this(isin, displayName, contentMd, origin, status, citations, null);
	}
}
