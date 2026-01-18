package my.portfoliomanager.app.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RulesetValidatorTest {
	private final RulesetValidator validator = new RulesetValidator();

	@Test
	void rejectsNullRuleset() {
		List<String> errors = validator.validate(null);
		assertThat(errors).isNotEmpty();
	}

	@Test
	void rejectsUnknownOperator() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("test");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		MatchDefinition match = new MatchDefinition();
		match.setField("name");
		match.setOperator("UNKNOWN");
		match.setValue("test");
		rule.setMatch(match);
		rule.setActions(new ActionsDefinition());
		ruleset.setRules(List.of(rule));

		List<String> errors = validator.validate(ruleset);
		assertThat(errors).anyMatch(e -> e.contains("operator"));
	}

	@Test
	void requiresValuesForContainsAny() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("test");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		MatchDefinition match = new MatchDefinition();
		match.setField("name");
		match.setOperator("CONTAINS_ANY");
		rule.setMatch(match);
		rule.setActions(new ActionsDefinition());
		ruleset.setRules(List.of(rule));

		List<String> errors = validator.validate(ruleset);
		assertThat(errors).anyMatch(e -> e.contains("values"));
	}

	@Test
	void rejectsMissingNameAndWrongSchema() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(2);
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		MatchDefinition match = new MatchDefinition();
		match.setField("name");
		match.setOperator("EQ");
		match.setValue("test");
		rule.setMatch(match);
		rule.setActions(new ActionsDefinition());
		ruleset.setRules(List.of(rule));

		List<String> errors = validator.validate(ruleset);
		assertThat(errors).anyMatch(e -> e.contains("schema_version"));
		assertThat(errors).anyMatch(e -> e.contains("name"));
	}

	@Test
	void rejectsLayerOutOfRange() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("test");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		MatchDefinition match = new MatchDefinition();
		match.setField("name");
		match.setOperator("EQ");
		match.setValue("test");
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(7);
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));

		List<String> errors = validator.validate(ruleset);
		assertThat(errors).anyMatch(e -> e.contains("layer"));
	}

	@Test
	void requiresValueForContains() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("test");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		MatchDefinition match = new MatchDefinition();
		match.setField("name");
		match.setOperator("CONTAINS");
		rule.setMatch(match);
		rule.setActions(new ActionsDefinition());
		ruleset.setRules(List.of(rule));

		List<String> errors = validator.validate(ruleset);
		assertThat(errors).anyMatch(e -> e.contains("value"));
	}

	@Test
	void requiresRulesList() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("test");

		List<String> errors = validator.validate(ruleset);
		assertThat(errors).anyMatch(e -> e.contains("rules"));
	}

	@Test
	void validatesDefaultsLayerRange() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("test");
		RulesetDefaults defaults = new RulesetDefaults();
		defaults.setLayer(9);
		ruleset.setDefaults(defaults);

		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		MatchDefinition match = new MatchDefinition();
		match.setField("name");
		match.setOperator("EQ");
		match.setValue("test");
		rule.setMatch(match);
		rule.setActions(new ActionsDefinition());
		ruleset.setRules(List.of(rule));

		List<String> errors = validator.validate(ruleset);
		assertThat(errors).anyMatch(e -> e.contains("defaults.layer"));
	}

	@Test
	void rejectsMissingMatchAndActions() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("test");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		ruleset.setRules(List.of(rule));

		List<String> errors = validator.validate(ruleset);
		assertThat(errors).anyMatch(e -> e.contains("rule.match"));
		assertThat(errors).anyMatch(e -> e.contains("rule.actions"));
	}

	@Test
	void rejectsMissingFieldAndValuesForIn() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("test");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		MatchDefinition match = new MatchDefinition();
		match.setOperator("IN");
		rule.setMatch(match);
		rule.setActions(new ActionsDefinition());
		ruleset.setRules(List.of(rule));

		List<String> errors = validator.validate(ruleset);
		assertThat(errors).anyMatch(e -> e.contains("values"));
		assertThat(errors).anyMatch(e -> e.contains("field"));
	}

	@Test
	void validDefinitionProducesNoErrors() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("valid");
		RulesetDefaults defaults = new RulesetDefaults();
		defaults.setLayer(3);
		ruleset.setDefaults(defaults);
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		MatchDefinition match = new MatchDefinition();
		match.setField("name");
		match.setOperator("CONTAINS_ANY");
		match.setValues(List.of("global", "fund"));
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(4);
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));

		List<String> errors = validator.validate(ruleset);

		assertThat(errors).isEmpty();
	}

	@Test
	void inOperatorWithValuesDoesNotProduceErrors() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("valid-in");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		MatchDefinition match = new MatchDefinition();
		match.setField("name");
		match.setOperator("IN");
		match.setValues(List.of("alpha"));
		rule.setMatch(match);
		rule.setActions(new ActionsDefinition());
		ruleset.setRules(List.of(rule));

		List<String> errors = validator.validate(ruleset);

		assertThat(errors).isEmpty();
	}

	@Test
	void containsOperatorWithValueDoesNotProduceErrors() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("valid-contains");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		MatchDefinition match = new MatchDefinition();
		match.setField("name");
		match.setOperator("CONTAINS");
		match.setValue("fund");
		rule.setMatch(match);
		rule.setActions(new ActionsDefinition());
		ruleset.setRules(List.of(rule));

		List<String> errors = validator.validate(ruleset);

		assertThat(errors).isEmpty();
	}
}
