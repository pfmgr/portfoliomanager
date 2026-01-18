package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record KnowledgeBaseDossierDeleteRequestDto(
		@JsonProperty("isins") @NotEmpty List<String> isins
) {
}
