package my.portfoliomanager.app.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LayerTargetRiskThresholds {
	private final Double lowMax;
	private final Double highMin;
}
