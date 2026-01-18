package my.portfoliomanager.app.dto;

import java.util.List;

public record ReclassificationDto(
									 String isin,
									 String name,
									 String suggestedName,
									 ClassificationDto current,
									 ClassificationDto proposed,
									 ClassificationDto policyAdjusted,
									 double confidence,
									 List<FiredRuleDto> firedRules,
									 List<String> policyNotes,
									 ImpactDto impact
) {
}
