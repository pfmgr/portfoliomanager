package my.portfoliomanager.app.llm;

public interface LlmActionSupport {
	boolean isConfiguredFor(LlmActionType actionType);

	boolean isExternalProviderFor(LlmActionType actionType);

	static LlmActionSupport disabled() {
		return new LlmActionSupport() {
			@Override
			public boolean isConfiguredFor(LlmActionType actionType) {
				return false;
			}

			@Override
			public boolean isExternalProviderFor(LlmActionType actionType) {
				return false;
			}
		};
	}
}
