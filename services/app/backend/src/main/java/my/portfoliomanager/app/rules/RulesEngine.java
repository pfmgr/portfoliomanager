package my.portfoliomanager.app.rules;

import my.portfoliomanager.app.domain.Instrument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class RulesEngine {
public static final String FACT_IS_SAVINGPLAN_ACTIVE = "is_savingPlan_active";
	public static final String FACT_NAME_NORM = "name_norm";
	public static final String FACT_IS_STOCK = "is_stock";
	public static final String FACT_IS_ETF = "is_etf";

	public EvaluationResult evaluate(Instrument instrument, boolean isSavingPlanActive, RulesetDefinition ruleset) {
		Map<String, Object> facts = buildFacts(instrument, isSavingPlanActive);
		List<RuleDefinition> rules = ruleset.getRules();
		if (rules == null) {
			rules = List.of();
		}
		List<RuleDefinition> sorted = new ArrayList<>(rules);
		sorted.sort(Comparator.comparing((RuleDefinition r) -> r.getPriority() == null ? 0 : r.getPriority()).reversed());

		Candidate<String> instrumentTypeCandidate = null;
		Candidate<String> assetClassCandidate = null;
		Candidate<String> subClassCandidate = null;
		Candidate<Integer> layerCandidate = null;
		List<FiredRule> firedRules = new ArrayList<>();

		for (RuleDefinition rule : sorted) {
			if (!matches(rule.getMatch(), facts)) {
				continue;
			}
			ActionsDefinition actions = rule.getActions();
			int score = score(rule, actions);
			if (actions != null) {
				if (actions.getInstrumentType() != null && instrumentTypeCandidate == null) {
					instrumentTypeCandidate = new Candidate<>(actions.getInstrumentType(), score, rule);
				}
				if (actions.getAssetClass() != null) {
					assetClassCandidate = pickBest(assetClassCandidate, new Candidate<>(actions.getAssetClass(), score, rule));
				}
				if (actions.getSubClass() != null) {
					subClassCandidate = pickBest(subClassCandidate, new Candidate<>(actions.getSubClass(), score, rule));
				}
				if (actions.getLayer() != null) {
					layerCandidate = pickBest(layerCandidate, new Candidate<>(actions.getLayer(), score, rule));
				}
			}
			firedRules.add(new FiredRule(rule.getId(), rule.getPriority(), score));
		}

		RulesetDefaults defaults = ruleset.getDefaults();
		String proposedInstrumentType = pickValue(instrumentTypeCandidate, instrument.getInstrumentType(), defaults == null ? null : defaults.getInstrumentType());
		String proposedAssetClass = pickValue(assetClassCandidate, instrument.getAssetClass(), defaults == null ? null : defaults.getAssetClass());
		String proposedSubClass = pickValue(subClassCandidate, instrument.getSubClass(), defaults == null ? null : defaults.getSubClass());
		Integer proposedLayer = pickValue(layerCandidate, instrument.getLayer(), defaults == null ? null : defaults.getLayer());

		int confidenceScore = layerCandidate == null ? 0 : layerCandidate.score;
		double confidence = Math.min(1.0d, confidenceScore / 100.0d);

		return new EvaluationResult(
				new Classification(proposedInstrumentType, proposedAssetClass, proposedSubClass, proposedLayer),
				confidence,
				firedRules,
				facts
		);
	}

	private int score(RuleDefinition rule, ActionsDefinition actions) {
		int base = rule.getScore() == null ? 0 : rule.getScore();
		if (actions != null && actions.getScoreBoost() != null) {
			base += actions.getScoreBoost();
		}
		return base;
	}

	private <T> Candidate<T> pickBest(Candidate<T> current, Candidate<T> next) {
		if (current == null) {
			return next;
		}
		if (next.score > current.score) {
			return next;
		}
		return current;
	}

	private <T> T pickValue(Candidate<T> candidate, T current, T fallback) {
		if (candidate != null && candidate.value != null) {
			return candidate.value;
		}
		if (fallback != null) {
			return fallback;
		}
		return current;
	}

	private Map<String, Object> buildFacts(Instrument instrument, boolean isSavingPlanActive) {
		Map<String, Object> facts = new HashMap<>();
		facts.put("isin", instrument.getIsin());
		facts.put("name", instrument.getName());
		facts.put("instrument_type", instrument.getInstrumentType());
		facts.put("asset_class", instrument.getAssetClass());
		facts.put("sub_class", instrument.getSubClass());
		facts.put("layer", instrument.getLayer());
		facts.put(FACT_IS_SAVINGPLAN_ACTIVE, isSavingPlanActive);
		facts.put(FACT_NAME_NORM, normalizeName(instrument.getName()));
		facts.put(FACT_IS_STOCK, isStock(instrument));
		facts.put(FACT_IS_ETF, isEtf(instrument));
		return facts;
	}

	private String normalizeName(String name) {
		if (name == null) {
			return "";
		}
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
	}

	private boolean isStock(Instrument instrument) {
		String type = instrument.getInstrumentType();
		String name = instrument.getName();
		return containsToken(type, "stock") || containsToken(name, "aktie") || containsToken(name, "stock");
	}

	private boolean isEtf(Instrument instrument) {
		String type = instrument.getInstrumentType();
		String name = instrument.getName();
		return containsToken(type, "etf") || containsToken(name, "etf");
	}

	private boolean containsToken(String value, String token) {
		if (value == null) {
			return false;
		}
		return value.toLowerCase(Locale.ROOT).contains(token);
	}

	private boolean matches(MatchDefinition match, Map<String, Object> facts) {
		if (match == null) {
			return false;
		}
		String field = match.getField();
		if (field == null) {
			return false;
		}
		Object factValue = facts.get(field);
		String operator = match.getOperator();
		if (operator == null) {
			return false;
		}
		return switch (operator) {
			case "EQ" -> equalsValue(factValue, match.getValue());
			case "IN" -> inValues(factValue, match.getValues());
			case "CONTAINS" -> containsValue(factValue, match.getValue());
			case "CONTAINS_ANY" -> containsAny(factValue, match.getValues());
			case "REGEX" -> regexMatch(factValue, match.getValue());
			default -> false;
		};
	}

	private boolean equalsValue(Object factValue, String expected) {
		if (factValue == null || expected == null) {
			return false;
		}
		return factValue.toString().equalsIgnoreCase(expected);
	}

	private boolean inValues(Object factValue, List<String> values) {
		if (factValue == null || values == null) {
			return false;
		}
		return values.stream().anyMatch(v -> v != null && v.equalsIgnoreCase(factValue.toString()));
	}

	private boolean containsValue(Object factValue, String value) {
		if (factValue == null || value == null) {
			return false;
		}
		return factValue.toString().toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
	}

	private boolean containsAny(Object factValue, List<String> values) {
		if (factValue == null || values == null) {
			return false;
		}
		String text = factValue.toString().toLowerCase(Locale.ROOT);
		for (String value : values) {
			if (value != null && text.contains(value.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	private boolean regexMatch(Object factValue, String pattern) {
		if (factValue == null || pattern == null) {
			return false;
		}
		return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(factValue.toString()).find();
	}

	public record Classification(String instrumentType, String assetClass, String subClass, Integer layer) {
	}

	public record FiredRule(String id, Integer priority, Integer score) {
	}

	public record EvaluationResult(Classification proposed, double confidence, List<FiredRule> firedRules,
							Map<String, Object> facts) {
	}

	private record Candidate<T>(T value, int score, RuleDefinition rule) {
	}
}
