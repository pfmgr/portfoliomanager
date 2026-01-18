package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.databind.JsonNode;
import my.portfoliomanager.app.domain.DossierAuthoredBy;
import my.portfoliomanager.app.domain.DossierOrigin;
import my.portfoliomanager.app.domain.DossierStatus;

import java.time.LocalDateTime;

public record InstrumentDossierResponseDto(
		Long dossierId,
		String isin,
		String displayName,
		String createdBy,
		DossierOrigin origin,
		DossierStatus status,
		DossierAuthoredBy authoredBy,
		Integer version,
		String contentMd,
		JsonNode citations,
		String contentHash,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		String approvedBy,
		LocalDateTime approvedAt,
		boolean autoApproved,
		Long supersedesId
) {
}
