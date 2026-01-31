package my.portfoliomanager.app.model;

import java.time.OffsetDateTime;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LayerTargetConfigModel {
	private final String activeProfile;
	private final Map<String, LayerTargetProfile> profiles;
	private final Map<Integer, String> layerNames;
	private final Map<Integer, Integer> maxSavingPlansPerLayer;
	private final LayerTargetCustomOverrides customOverrides;
	private final OffsetDateTime updatedAt;
}
