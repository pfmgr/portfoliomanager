package my.portfoliomanager.app.dto;

import tools.jackson.databind.JsonNode;
import my.portfoliomanager.app.domain.DossierExtractionStatus;

import java.time.LocalDateTime;

public record InstrumentDossierExtractionResponseDto(
		Long extractionId,
		Long dossierId,
		String model,
		JsonNode extractedJson,
		JsonNode missingFieldsJson,
		JsonNode warningsJson,
		DossierExtractionStatus status,
		String error,
		LocalDateTime createdAt,
		String approvedBy,
		LocalDateTime approvedAt,
		String appliedBy,
		LocalDateTime appliedAt,
		boolean autoApproved
) {
}
