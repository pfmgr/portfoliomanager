package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Gating information for instrument-level proposals.")
public record InstrumentProposalGatingDto(
		@JsonProperty("knowledgeBaseEnabled") boolean knowledgeBaseEnabled,
		@JsonProperty("kbComplete") boolean kbComplete,
		@JsonProperty("missingIsins") List<String> missingIsins
) {
}
