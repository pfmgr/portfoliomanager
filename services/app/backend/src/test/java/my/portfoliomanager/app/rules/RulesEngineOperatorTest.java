package my.portfoliomanager.app.rules;

import my.portfoliomanager.app.domain.Instrument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RulesEngineOperatorTest {
	private final RulesEngine engine = new RulesEngine();

	@Test
	void matchesEqAndInOperators() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("ops");

		RuleDefinition eqRule = rule("eq", "instrument_type", "EQ", "ETF", 20, 2);
		RuleDefinition inRule = new RuleDefinition();
		inRule.setId("in");
		inRule.setPriority(5);
		inRule.setScore(40);
		MatchDefinition match = new MatchDefinition();
		match.setField("asset_class");
		match.setOperator("IN");
		match.setValues(List.of("Equity", "Bonds"));
		inRule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(3);
		inRule.setActions(actions);

		ruleset.setRules(List.of(eqRule, inRule));

		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN1");
		instrument.setName("Global ETF Fund");
		instrument.setInstrumentType("ETF");
		instrument.setAssetClass("Equity");
		instrument.setLayer(5);

		var result = engine.evaluate(instrument, false, ruleset);
		assertThat(result.proposed().layer()).isEqualTo(3);
	}

	@Test
	void matchesRegexAndContainsOperators() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("ops");

		RuleDefinition regex = rule("regex", "name_norm", "REGEX", "etf$", 50, 1);
		RuleDefinition contains = rule("contains", "name_norm", "CONTAINS", "fund", 10, 4);
		ruleset.setRules(List.of(regex, contains));

		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN2");
		instrument.setName("Global ETF");
		instrument.setLayer(5);

		var result = engine.evaluate(instrument, false, ruleset);
		assertThat(result.proposed().layer()).isEqualTo(1);
	}

	@Test
	void usesDefaultsWhenNoRuleMatches() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("ops");
		RulesetDefaults defaults = new RulesetDefaults();
		defaults.setLayer(4);
		ruleset.setDefaults(defaults);
		ruleset.setRules(List.of(rule("miss", "name_norm", "CONTAINS", "bond", 5, 2)));

		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN3");
		instrument.setName("Crypto Fund");
		instrument.setLayer(5);

		var result = engine.evaluate(instrument, false, ruleset);
		assertThat(result.proposed().layer()).isEqualTo(4);
	}

	@Test
	void containsAnyNoMatchKeepsCurrent() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("ops");

		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		rule.setPriority(5);
		rule.setScore(10);
		MatchDefinition match = new MatchDefinition();
		match.setField("name_norm");
		match.setOperator("CONTAINS_ANY");
		match.setValues(List.of("bond", "gold"));
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(2);
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));

		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN4");
		instrument.setName("Equity Fund");
		instrument.setLayer(5);

		var result = engine.evaluate(instrument, false, ruleset);
		assertThat(result.proposed().layer()).isEqualTo(5);
	}

	@Test
	void regexNoMatchKeepsCurrent() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("ops");

		RuleDefinition rule = rule("regex", "name_norm", "REGEX", "^bond", 10, 1);
		ruleset.setRules(List.of(rule));

		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN5");
		instrument.setName("Equity Fund");
		instrument.setLayer(5);

		var result = engine.evaluate(instrument, false, ruleset);
		assertThat(result.proposed().layer()).isEqualTo(5);
	}

	@Test
	void eqWithNullValueDoesNotMatch() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("ops");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		rule.setPriority(5);
		rule.setScore(10);
		MatchDefinition match = new MatchDefinition();
		match.setField("instrument_type");
		match.setOperator("EQ");
		match.setValue(null);
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(2);
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));

		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN6");
		instrument.setName("Equity Fund");
		instrument.setInstrumentType("ETF");
		instrument.setLayer(5);

		var result = engine.evaluate(instrument, false, ruleset);
		assertThat(result.proposed().layer()).isEqualTo(5);
	}

	@Test
	void inWithNullValuesDoesNotMatch() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("ops");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		rule.setPriority(5);
		rule.setScore(10);
		MatchDefinition match = new MatchDefinition();
		match.setField("asset_class");
		match.setOperator("IN");
		match.setValues(null);
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(2);
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));

		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN7");
		instrument.setName("Equity Fund");
		instrument.setAssetClass("Equity");
		instrument.setLayer(5);

		var result = engine.evaluate(instrument, false, ruleset);
		assertThat(result.proposed().layer()).isEqualTo(5);
	}

	@Test
	void unknownOperatorDoesNotMatch() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("ops");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		rule.setPriority(5);
		rule.setScore(10);
		MatchDefinition match = new MatchDefinition();
		match.setField("name_norm");
		match.setOperator("UNKNOWN");
		match.setValue("fund");
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(2);
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));

		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN8");
		instrument.setName("Equity Fund");
		instrument.setLayer(5);

		var result = engine.evaluate(instrument, false, ruleset);
		assertThat(result.proposed().layer()).isEqualTo(5);
	}

	@Test
	void nullMatchIsIgnored() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("ops");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		rule.setPriority(5);
		rule.setScore(10);
		rule.setMatch(null);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(2);
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));

		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN9");
		instrument.setName("Equity Fund");
		instrument.setLayer(5);

		var result = engine.evaluate(instrument, false, ruleset);
		assertThat(result.proposed().layer()).isEqualTo(5);
	}

	@Test
	void containsWithNullFactDoesNotMatch() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("ops");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		rule.setPriority(5);
		rule.setScore(10);
		MatchDefinition match = new MatchDefinition();
		match.setField("unknown_field");
		match.setOperator("CONTAINS");
		match.setValue("fund");
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(2);
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));

		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN10");
		instrument.setName("Equity Fund");
		instrument.setLayer(5);

		var result = engine.evaluate(instrument, false, ruleset);
		assertThat(result.proposed().layer()).isEqualTo(5);
	}

	@Test
	void regexWithNullPatternDoesNotMatch() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("ops");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		rule.setPriority(5);
		rule.setScore(10);
		MatchDefinition match = new MatchDefinition();
		match.setField("name_norm");
		match.setOperator("REGEX");
		match.setValue(null);
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(2);
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));

		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN11");
		instrument.setName("Equity Fund");
		instrument.setLayer(5);

		var result = engine.evaluate(instrument, false, ruleset);
		assertThat(result.proposed().layer()).isEqualTo(5);
	}

	@Test
	void containsAnyWithNullValuesDoesNotMatch() {
		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("ops");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		rule.setPriority(5);
		rule.setScore(10);
		MatchDefinition match = new MatchDefinition();
		match.setField("name_norm");
		match.setOperator("CONTAINS_ANY");
		match.setValues(null);
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(2);
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));

		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN12");
		instrument.setName("Equity Fund");
		instrument.setLayer(5);

		var result = engine.evaluate(instrument, false, ruleset);
		assertThat(result.proposed().layer()).isEqualTo(5);
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
