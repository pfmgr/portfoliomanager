package my.portfoliomanager.app.dto;

import java.util.List;

public record RulesetValidateResponse(boolean valid, List<String> errors) {
}
