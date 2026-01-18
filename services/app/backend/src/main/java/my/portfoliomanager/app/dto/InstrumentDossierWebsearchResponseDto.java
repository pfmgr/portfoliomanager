package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record InstrumentDossierWebsearchResponseDto(
		String contentMd,
		String displayName,
		JsonNode citations,
		String model
) {
}
