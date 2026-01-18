package my.portfoliomanager.app.dto;

import tools.jackson.databind.JsonNode;

public record InstrumentDossierWebsearchResponseDto(
		String contentMd,
		String displayName,
		JsonNode citations,
		String model
) {
}
