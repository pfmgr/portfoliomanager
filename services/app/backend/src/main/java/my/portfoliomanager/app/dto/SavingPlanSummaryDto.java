package my.portfoliomanager.app.dto;

import java.util.List;

public record SavingPlanSummaryDto(Double totalActiveAmountEur,
								 Double monthlyTotalAmountEur,
								 Integer activeCount,
								 Integer monthlyCount,
								 List<SavingPlanLayerDto> monthlyByLayer) {
}
