package my.portfoliomanager.app.service;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SavingPlanInstrument(String isin, String name, BigDecimal monthlyAmount, int layer, LocalDate lastChanged) {
}
