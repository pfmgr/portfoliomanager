package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public record RulesetValidateRequest(@NotBlank @JsonAlias("contentYaml") String contentJson) {
}
