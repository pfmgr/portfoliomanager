package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import my.portfoliomanager.app.domain.DossierStatus;

public record InstrumentDossierUpdateRequest(
		String displayName,
		@NotBlank String contentMd,
		@NotNull DossierStatus status,
		@NotNull JsonNode citations
) {
}
