package my.portfoliomanager.app.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record InstrumentDossierBulkWebsearchRequest(
		@NotEmpty
		@Size(max = 30)
		List<String> isins
) {
}

