package my.portfoliomanager.app.rules;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

public class RulesetParser {
	private final ObjectMapper jsonMapper;
	private final ObjectMapper yamlMapper;

	public RulesetParser() {
		this.jsonMapper = JsonMapper.builder()
				.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
				.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
				.build();

		this.yamlMapper = YAMLMapper.builder()
				.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
				.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
				.build();
	}

	public RulesetDefinition parse(String content) throws Exception {
		try {
			return jsonMapper.readValue(content, RulesetDefinition.class);
		} catch (Exception jsonEx) {
			return yamlMapper.readValue(content, RulesetDefinition.class);
		}
	}
}
