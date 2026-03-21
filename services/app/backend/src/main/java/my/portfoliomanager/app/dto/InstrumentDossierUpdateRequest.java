package my.portfoliomanager.app.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import my.portfoliomanager.app.domain.DossierStatus;
import my.portfoliomanager.app.domain.InstrumentBlacklistScope;

public record InstrumentDossierUpdateRequest(
		String displayName,
		@NotBlank String contentMd,
		@NotNull DossierStatus status,
		@NotNull JsonNode citations,
		InstrumentBlacklistScope blacklistScope
) {
	public InstrumentDossierUpdateRequest(String displayName,
								 String contentMd,
								 DossierStatus status,
								 JsonNode citations) {
		this(displayName, contentMd, status, citations, null);
	}
}
