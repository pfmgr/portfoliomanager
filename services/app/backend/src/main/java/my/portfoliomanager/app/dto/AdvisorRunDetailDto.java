package my.portfoliomanager.app.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record AdvisorRunDetailDto(Long runId,
								  OffsetDateTime createdAt,
								  LocalDate asOfDate,
								  List<String> depotScope,
								  String narrativeMd,
								  AdvisorSummaryDto summary) {
}
