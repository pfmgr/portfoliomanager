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
		validateTopLevel(definition, errors);
		if (definition.getRules() == null) {
			errors.add("rules must be provided");
			return errors;
		}
		for (RuleDefinition rule : definition.getRules()) {
			validateRule(rule, errors);
		}
		return errors;
	}

	private void validateTopLevel(RulesetDefinition definition, List<String> errors) {
		if (definition.getSchemaVersion() != 1) {
			errors.add("schema_version must be 1");
		}
		if (definition.getName() == null || definition.getName().isBlank()) {
			errors.add("name is required");
		}
		if (definition.getDefaults() == null || definition.getDefaults().getLayer() == null) {
			return;
		}
		if (!isValidLayer(definition.getDefaults().getLayer())) {
			errors.add("defaults.layer must be between 1 and 5");
		}
	}

	private void validateRule(RuleDefinition rule, List<String> errors) {
		if (rule.getId() == null || rule.getId().isBlank()) {
			errors.add("rule.id is required");
		}
		validateMatch(rule.getMatch(), errors);
		validateActions(rule.getActions(), errors);
	}

	private void validateMatch(MatchDefinition match, List<String> errors) {
		if (match == null) {
			errors.add("rule.match is required");
			return;
		}
		String operator = match.getOperator();
		if (operator == null || !OPERATORS.contains(operator)) {
			errors.add("rule.match.operator must be one of " + OPERATORS);
		} else {
			validateMatchValues(match, operator, errors);
		}
		if (match.getField() == null || match.getField().isBlank()) {
			errors.add("rule.match.field is required");
		}
	}

	private void validateMatchValues(MatchDefinition match, String operator, List<String> errors) {
		if ("IN".equals(operator) || "CONTAINS_ANY".equals(operator)) {
			if (match.getValues() == null || match.getValues().isEmpty()) {
				errors.add("rule.match.values must not be empty for " + operator);
			}
			return;
		}
		if (match.getValue() == null || match.getValue().isBlank()) {
			errors.add("rule.match.value must not be empty for " + operator);
		}
	}

	private void validateActions(ActionsDefinition actions, List<String> errors) {
		if (actions == null) {
			errors.add("rule.actions is required");
			return;
		}
		Integer layer = actions.getLayer();
		if (layer != null && !isValidLayer(layer)) {
			errors.add("rule.actions.layer must be between 1 and 5");
		}
	}

	private boolean isValidLayer(Integer layer) {
		return layer != null && layer >= 1 && layer <= 5;
	}
}
