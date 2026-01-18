package my.portfoliomanager.app.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class RulesetValidator {
	private static final Set<String> OPERATORS = Set.of("EQ", "IN", "CONTAINS", "CONTAINS_ANY", "REGEX");

	public List<String> validate(RulesetDefinition definition) {
		List<String> errors = new ArrayList<>();
		if (definition == null) {
			errors.add("Ruleset is empty");
			return errors;
		}
		if (definition.getSchemaVersion() != 1) {
			errors.add("schema_version must be 1");
		}
		if (definition.getName() == null || definition.getName().isBlank()) {
			errors.add("name is required");
		}
		if (definition.getDefaults() != null && definition.getDefaults().getLayer() != null) {
			Integer layer = definition.getDefaults().getLayer();
			if (layer < 1 || layer > 5) {
				errors.add("defaults.layer must be between 1 and 5");
			}
		}
		if (definition.getRules() == null) {
			errors.add("rules must be provided");
			return errors;
		}
		for (RuleDefinition rule : definition.getRules()) {
			if (rule.getId() == null || rule.getId().isBlank()) {
				errors.add("rule.id is required");
			}
			if (rule.getMatch() == null) {
				errors.add("rule.match is required");
			} else {
				String operator = rule.getMatch().getOperator();
				if (operator == null || !OPERATORS.contains(operator)) {
					errors.add("rule.match.operator must be one of " + OPERATORS);
				} else {
					if (operator.equals("IN") || operator.equals("CONTAINS_ANY")) {
						if (rule.getMatch().getValues() == null || rule.getMatch().getValues().isEmpty()) {
							errors.add("rule.match.values must not be empty for " + operator);
						}
					} else {
						if (rule.getMatch().getValue() == null || rule.getMatch().getValue().isBlank()) {
							errors.add("rule.match.value must not be empty for " + operator);
						}
					}
				}
				if (rule.getMatch().getField() == null || rule.getMatch().getField().isBlank()) {
					errors.add("rule.match.field is required");
				}
			}
			if (rule.getActions() == null) {
				errors.add("rule.actions is required");
			} else {
				if (rule.getActions().getLayer() != null) {
					Integer layer = rule.getActions().getLayer();
					if (layer < 1 || layer > 5) {
						errors.add("rule.actions.layer must be between 1 and 5");
					}
				}
			}
		}
		return errors;
	}
}
