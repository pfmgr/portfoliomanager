package my.portfoliomanager.app.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;

public record InstrumentOverrideRequest(
		String name,
		String instrumentType,
		String assetClass,
		String subClass,
		@Min(1) @Max(5) Integer layer,
		LocalDate layerLastChanged,
		String layerNotes
) {
}
