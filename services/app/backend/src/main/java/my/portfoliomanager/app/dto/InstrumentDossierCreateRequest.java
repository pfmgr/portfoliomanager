package my.portfoliomanager.app.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import my.portfoliomanager.app.domain.DossierOrigin;
import my.portfoliomanager.app.domain.DossierStatus;

public record InstrumentDossierCreateRequest(
		@NotBlank String isin,
		String displayName,
		@NotBlank String contentMd,
		@NotNull DossierOrigin origin,
		@NotNull DossierStatus status,
		@NotNull JsonNode citations
) {
}
