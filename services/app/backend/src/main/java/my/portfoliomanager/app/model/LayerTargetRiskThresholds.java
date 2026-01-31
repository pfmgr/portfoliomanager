package my.portfoliomanager.app.model;

public class LayerTargetRiskThresholds {
	private final Integer lowMax;
	private final Integer highMin;

	public LayerTargetRiskThresholds(Integer lowMax, Integer highMin) {
		this.lowMax = lowMax;
		this.highMin = highMin;
	}

	public Integer getLowMax() {
		return lowMax;
	}

	public Integer getHighMin() {
		return highMin;
	}
}
