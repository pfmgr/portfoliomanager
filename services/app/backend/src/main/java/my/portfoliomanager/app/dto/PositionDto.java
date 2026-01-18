package my.portfoliomanager.app.dto;

public record PositionDto(String isin, String name, Double valueEur, Double weightPct) {
}
