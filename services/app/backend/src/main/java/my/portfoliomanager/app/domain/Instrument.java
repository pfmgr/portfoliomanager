package my.portfoliomanager.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "instruments")
public class Instrument {
	@Id
	@Column(name = "isin")
	private String isin;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "depot_code", nullable = false)
	private String depotCode;

	@Column(name = "instrument_type")
	private String instrumentType;

	@Column(name = "asset_class")
	private String assetClass;

	@Column(name = "sub_class")
	private String subClass;

	@Column(name = "layer", nullable = false)
	private Integer layer;

	@Column(name = "layer_last_changed")
	private LocalDate layerLastChanged;

	@Column(name = "layer_notes")
	private String layerNotes;

	@Column(name = "is_deleted", nullable = false)
	private boolean deleted;

	public String getIsin() {
		return isin;
	}

	public void setIsin(String isin) {
		this.isin = isin;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDepotCode() {
		return depotCode;
	}

	public void setDepotCode(String depotCode) {
		this.depotCode = depotCode;
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

	public Integer getLayer() {
		return layer;
	}

	public void setLayer(Integer layer) {
		this.layer = layer;
	}

	public LocalDate getLayerLastChanged() {
		return layerLastChanged;
	}

	public void setLayerLastChanged(LocalDate layerLastChanged) {
		this.layerLastChanged = layerLastChanged;
	}

	public String getLayerNotes() {
		return layerNotes;
	}

	public void setLayerNotes(String layerNotes) {
		this.layerNotes = layerNotes;
	}

	public boolean isDeleted() {
		return deleted;
	}

	public void setDeleted(boolean deleted) {
		this.deleted = deleted;
	}
}
