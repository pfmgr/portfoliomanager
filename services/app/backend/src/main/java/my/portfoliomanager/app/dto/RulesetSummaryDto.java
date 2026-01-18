package my.portfoliomanager.app.dto;

import java.time.LocalDateTime;

public record RulesetSummaryDto(String name, int version, boolean active, LocalDateTime createdAt,
									 LocalDateTime updatedAt) {
}
