package my.portfoliomanager.app.service;

public interface LlmNarrativeService {
	boolean isEnabled();

	String suggestSavingPlanNarrative(String prompt);
}
