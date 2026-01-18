package my.portfoliomanager.app.dto;

import my.portfoliomanager.app.domain.DossierOrigin;
import my.portfoliomanager.app.domain.DossierStatus;

import java.time.LocalDateTime;

public record KnowledgeBaseDossierVersionDto(
		Long dossierId,
		Integer version,
		DossierStatus status,
		String displayName,
		String createdBy,
		DossierOrigin origin,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		LocalDateTime approvedAt,
		boolean autoApproved
) {
}
