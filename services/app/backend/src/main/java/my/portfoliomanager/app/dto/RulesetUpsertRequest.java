package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public record RulesetUpsertRequest(@NotBlank @JsonAlias("contentYaml") String contentJson, boolean activate) {
}
