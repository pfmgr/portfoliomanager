package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record KnowledgeBaseQualityGateProfileDto(
		@JsonProperty("display_name") String displayName,
		@JsonProperty("description") String description,
		@JsonProperty("layer_profiles") Map<String, String> layerProfiles,
		@JsonProperty("evidence_profiles") Map<String, List<String>> evidenceProfiles
) {
}
