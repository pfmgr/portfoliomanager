package my.portfoliomanager.app.dto;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;

public record AdvisorRunDto(Long runId,
							OffsetDateTime createdAt,
							LocalDate asOfDate,
							List<String> depotScope) {
}
