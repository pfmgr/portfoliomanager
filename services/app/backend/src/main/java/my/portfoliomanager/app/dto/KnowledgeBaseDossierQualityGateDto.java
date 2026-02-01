package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KnowledgeBaseDossierQualityGateDto(
		@JsonProperty("passed") boolean passed,
		@JsonProperty("reasons") List<String> reasons
) {
}
