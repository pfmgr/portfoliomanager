package my.portfoliomanager.app.model;

import java.time.OffsetDateTime;
import java.util.Map;

public class LayerTargetConfigModel {
	private final String activeProfile;
	private final Map<String, LayerTargetProfile> profiles;
	private final Map<Integer, String> layerNames;
	private final Map<Integer, Integer> maxSavingPlansPerLayer;
	private final LayerTargetCustomOverrides customOverrides;
	private final OffsetDateTime updatedAt;

	public LayerTargetConfigModel(String activeProfile,
								  Map<String, LayerTargetProfile> profiles,
								  Map<Integer, String> layerNames,
								  Map<Integer, Integer> maxSavingPlansPerLayer,
								  LayerTargetCustomOverrides customOverrides,
								  OffsetDateTime updatedAt) {
		this.activeProfile = activeProfile;
		this.profiles = profiles;
		this.layerNames = layerNames;
		this.maxSavingPlansPerLayer = maxSavingPlansPerLayer;
		this.customOverrides = customOverrides;
		this.updatedAt = updatedAt;
	}

	public String getActiveProfile() {
		return activeProfile;
	}

	public Map<String, LayerTargetProfile> getProfiles() {
		return profiles;
	}

	public Map<Integer, String> getLayerNames() {
		return layerNames;
	}

	public Map<Integer, Integer> getMaxSavingPlansPerLayer() {
		return maxSavingPlansPerLayer;
	}

	public LayerTargetCustomOverrides getCustomOverrides() {
		return customOverrides;
	 }

	public OffsetDateTime getUpdatedAt() {
		return updatedAt;
	}
}
