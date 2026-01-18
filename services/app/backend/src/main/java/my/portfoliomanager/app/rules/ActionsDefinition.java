package my.portfoliomanager.app.rules;

public class ActionsDefinition {
	private Integer layer;
	private String instrumentType;
	private String assetClass;
	private String subClass;
	private Integer scoreBoost;

	public Integer getLayer() {
		return layer;
	}

	public void setLayer(Integer layer) {
		this.layer = layer;
	}

	public String getInstrumentType() {
		return instrumentType;
	}

	public void setInstrumentType(String instrumentType) {
		this.instrumentType = instrumentType;
	}

	public String getAssetClass() {
		return assetClass;
	}

	public void setAssetClass(String assetClass) {
		this.assetClass = assetClass;
	}

	public String getSubClass() {
		return subClass;
	}

	public void setSubClass(String subClass) {
		this.subClass = subClass;
	}

	public Integer getScoreBoost() {
		return scoreBoost;
	}

	public void setScoreBoost(Integer scoreBoost) {
		this.scoreBoost = scoreBoost;
	}
}
