package my.portfoliomanager.app.rules;

import my.portfoliomanager.app.domain.Instrument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RulesEngineTest {
	private final RulesEngine engine = new RulesEngine();

	@Test
	void highestScoreWinsForLayer() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("test");

		RuleDefinition low = rule("low", "name_norm", "CONTAINS", "bond", 10, 2);
		RuleDefinition high = rule("high", "name_norm", "CONTAINS", "bond", 50, 1);
		ruleset.setRules(List.of(low, high));

		Instrument instrument = new Instrument();
		instrument.setIsin("X");
		instrument.setName("Bond Fund");
		instrument.setLayer(5);

		var result = engine.evaluate(instrument, false, ruleset);
		assertThat(result.proposed().layer()).isEqualTo(1);
	}

	@Test
	void containsAnyMatches() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("test");

		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		rule.setPriority(10);
		rule.setScore(20);
		MatchDefinition match = new MatchDefinition();
		match.setField("name_norm");
		match.setOperator("CONTAINS_ANY");
		match.setValues(List.of("etf", "bond"));
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setAssetClass("Equity");
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));

		Instrument instrument = new Instrument();
		instrument.setIsin("Y");
		instrument.setName("Global ETF");

		var result = engine.evaluate(instrument, false, ruleset);
		assertThat(result.proposed().assetClass()).isEqualTo("Equity");
	}

	private RuleDefinition rule(String id, String field, String operator, String value, int score, int layer) {
		RuleDefinition rule = new RuleDefinition();
		rule.setId(id);
		rule.setPriority(10);
		rule.setScore(score);
		MatchDefinition match = new MatchDefinition();
		match.setField(field);
		match.setOperator(operator);
		match.setValue(value);
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(layer);
		rule.setActions(actions);
		return rule;
	}
}
