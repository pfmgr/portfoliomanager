package my.portfoliomanager.app.dto;

import java.math.BigDecimal;

public record ImpactDto(BigDecimal valueEur, Double portfolioWeightPct, BigDecimal monthlySavingPlanEur) {
}
