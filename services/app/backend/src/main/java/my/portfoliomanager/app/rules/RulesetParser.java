package my.portfoliomanager.app.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.DeserializationFeature;

public class RulesetParser {
	private final ObjectMapper jsonMapper;
	private final ObjectMapper yamlMapper;

	public RulesetParser() {
		this.jsonMapper = new ObjectMapper();
		this.jsonMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		this.jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		this.yamlMapper = new ObjectMapper(new YAMLFactory());
		this.yamlMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		this.yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	}

	public RulesetDefinition parse(String content) throws Exception {
		try {
			return jsonMapper.readValue(content, RulesetDefinition.class);
		} catch (Exception jsonEx) {
			return yamlMapper.readValue(content, RulesetDefinition.class);
		}
	}
}
