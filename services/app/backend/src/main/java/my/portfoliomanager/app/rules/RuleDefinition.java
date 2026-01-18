package my.portfoliomanager.app.rules;

public class RuleDefinition {
	private String id;
	private Integer priority;
	private Integer score;
	private MatchDefinition match;
	private ActionsDefinition actions;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public Integer getPriority() {
		return priority;
	}

	public void setPriority(Integer priority) {
		this.priority = priority;
	}

	public Integer getScore() {
		return score;
	}

	public void setScore(Integer score) {
		this.score = score;
	}

	public MatchDefinition getMatch() {
		return match;
	}

	public void setMatch(MatchDefinition match) {
		this.match = match;
	}

	public ActionsDefinition getActions() {
		return actions;
	}

	public void setActions(ActionsDefinition actions) {
		this.actions = actions;
	}
}
