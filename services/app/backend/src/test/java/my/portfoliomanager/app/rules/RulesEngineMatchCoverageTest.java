package my.portfoliomanager.app.rules;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RulesEngineMatchCoverageTest {
	private final RulesEngine engine = new RulesEngine();
	private final Map<String, Object> facts = Map.of(
			"name_norm", "Global ETF Fund",
			"asset_class", "Equity",
			"isin", "DE0001234567",
			"layer", 3
	);

	private boolean matches(MatchDefinition match) {
		return ReflectionTestUtils.invokeMethod(engine, "matches", match, facts);
	}

	@Test
	void matchNullReturnsFalse() {
		assertThat(matches(null)).isFalse();
	}

	@Test
	void matchWithoutFieldReturnsFalse() {
		MatchDefinition match = new MatchDefinition();
		match.setOperator("EQ");
		match.setValue("ETF");

		assertThat(matches(match)).isFalse();
	}

	@Test
	void eqOperatorMatchesValue() {
		MatchDefinition match = new MatchDefinition();
		match.setField("asset_class");
		match.setOperator("EQ");
		match.setValue("Equity");

		assertThat(matches(match)).isTrue();
	}

	@Test
	void inOperatorMatchesList() {
		MatchDefinition match = new MatchDefinition();
		match.setField("asset_class");
		match.setOperator("IN");
		match.setValues(List.of("Equity", "Bonds"));

		assertThat(matches(match)).isTrue();
	}

	@Test
	void containsOperatorMatchesFragment() {
		MatchDefinition match = new MatchDefinition();
		match.setField("name_norm");
		match.setOperator("CONTAINS");
		match.setValue("ETF");

		assertThat(matches(match)).isTrue();
	}

	@Test
	void containsAnyOperatorMatchesOneValue() {
		MatchDefinition match = new MatchDefinition();
		match.setField("name_norm");
		match.setOperator("CONTAINS_ANY");
		match.setValues(List.of("Bond", "ETF"));

		assertThat(matches(match)).isTrue();
	}

	@Test
	void regexOperatorMatchesPattern() {
		MatchDefinition match = new MatchDefinition();
		match.setField("name_norm");
		match.setOperator("REGEX");
		match.setValue("global.*fund$");

		assertThat(matches(match)).isTrue();
	}

	@Test
	void regexOperatorRejectsNonMatchingPattern() {
		MatchDefinition match = new MatchDefinition();
		match.setField("name_norm");
		match.setOperator("REGEX");
		match.setValue("^bond");

		assertThat(matches(match)).isFalse();
	}

	@Test
	void matchIgnoresMissingValuesInInOperator() {
		MatchDefinition match = new MatchDefinition();
		match.setField("asset_class");
		match.setOperator("IN");

		assertThat(matches(match)).isFalse();
	}

	@Test
	void matchIgnoresMissingValueInEqOperator() {
		MatchDefinition match = new MatchDefinition();
		match.setField("asset_class");
		match.setOperator("EQ");

		assertThat(matches(match)).isFalse();
	}
}
