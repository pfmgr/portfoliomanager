package my.portfoliomanager.app.dto;

import jakarta.validation.constraints.NotBlank;

public record InstrumentDossierWebsearchRequest(
		@NotBlank String isin
) {
}

