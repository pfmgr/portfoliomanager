package my.portfoliomanager.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "instrument_overrides")
public class InstrumentOverride {
	@Id
	@Column(name = "isin")
	private String isin;

	@Column(name = "name", columnDefinition = "TEXT")
	private String name;

	@Column(name = "instrument_type", columnDefinition = "TEXT")
	private String instrumentType;

	@Column(name = "asset_class", columnDefinition = "TEXT")
	private String assetClass;

	@Column(name = "sub_class", columnDefinition = "TEXT")
	private String subClass;

	@Column(name = "layer")
	private Integer layer;

	@Column(name = "layer_last_changed")
	private LocalDate layerLastChanged;

	@Column(name = "layer_notes", columnDefinition = "TEXT")
	private String layerNotes;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

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

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}
}
