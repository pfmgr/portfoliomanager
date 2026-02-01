package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record KnowledgeBaseQualityGateConfigDto(
		@JsonProperty("active_profile") String activeProfile,
		@JsonProperty("profiles") Map<String, KnowledgeBaseQualityGateProfileDto> profiles,
		@JsonProperty("custom_overrides_enabled") Boolean customOverridesEnabled,
		@JsonProperty("custom_profiles") Map<String, KnowledgeBaseQualityGateProfileDto> customProfiles
) {
}
