package my.portfoliomanager.app.dto;

import java.util.List;

public record AssessorRunRequestDto(String profile,
									String assessmentType,
									Double savingPlanAmountDeltaEur,
									Double oneTimeAmountEur,
									Integer minimumInstrumentAmountEur,
									List<String> depotScope,
									String gapDetectionPolicy,
									List<String> instruments,
									Integer instrumentAmountEur) {
}
