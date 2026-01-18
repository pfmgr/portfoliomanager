package my.portfoliomanager.app.rules;

import java.util.List;

public class RulesetDefinition {
	private int schemaVersion;
	private String name;
	private String mode;
	private RulesetDefaults defaults;
	private List<String> facts;
	private List<RuleDefinition> rules;
	private PoliciesDefinition policies;

	public int getSchemaVersion() {
		return schemaVersion;
	}

	public void setSchemaVersion(int schemaVersion) {
		this.schemaVersion = schemaVersion;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getMode() {
		return mode;
	}

	public void setMode(String mode) {
		this.mode = mode;
	}

	public RulesetDefaults getDefaults() {
		return defaults;
	}

	public void setDefaults(RulesetDefaults defaults) {
		this.defaults = defaults;
	}

	public List<String> getFacts() {
		return facts;
	}

	public void setFacts(List<String> facts) {
		this.facts = facts;
	}

	public List<RuleDefinition> getRules() {
		return rules;
	}

	public void setRules(List<RuleDefinition> rules) {
		this.rules = rules;
	}

	public PoliciesDefinition getPolicies() {
		return policies;
	}

	public void setPolicies(PoliciesDefinition policies) {
		this.policies = policies;
	}
}
