package my.portfoliomanager.app.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import my.portfoliomanager.app.domain.KnowledgeBaseConfig;
import my.portfoliomanager.app.llm.OpenAiLlmClient;
import my.portfoliomanager.app.repository.KnowledgeBaseConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseConfigServiceTest {
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void getSnapshot_normalizesAndDeduplicatesAllowedDomains() {
		KnowledgeBaseConfigRepository repository = mock(KnowledgeBaseConfigRepository.class);
		KnowledgeBaseConfig config = new KnowledgeBaseConfig();
		config.setId(1);
		ObjectNode json = objectMapper.createObjectNode();
		json.putArray("websearch_allowed_domains")
				.add("Example.com")
				.add("example.com.")
				.add("sub.domain.co.uk");
		config.setConfigJson(json);
		when(repository.findById(1)).thenReturn(Optional.of(config));

		KnowledgeBaseConfigService service = new KnowledgeBaseConfigService(
				repository,
				objectMapper,
				new DefaultResourceLoader(),
				null
		);

		assertThat(service.getSnapshot().websearchAllowedDomains())
				.containsExactly("example.com", "sub.domain.co.uk");
	}

	@Test
	void getSnapshot_preservesExplicitEmptyAllowedDomains() {
		KnowledgeBaseConfigRepository repository = mock(KnowledgeBaseConfigRepository.class);
		KnowledgeBaseConfig config = new KnowledgeBaseConfig();
		config.setId(1);
		ObjectNode json = objectMapper.createObjectNode();
		json.putArray("websearch_allowed_domains");
		config.setConfigJson(json);
		when(repository.findById(1)).thenReturn(Optional.of(config));

		KnowledgeBaseConfigService service = new KnowledgeBaseConfigService(
				repository,
				objectMapper,
				new DefaultResourceLoader(),
				null
		);

		assertThat(service.getSnapshot().websearchAllowedDomains()).isEmpty();
	}

	@Test
	void getSnapshot_usesSeededDefaultsWhenDomainsAreAbsent() {
		KnowledgeBaseConfigRepository repository = mock(KnowledgeBaseConfigRepository.class);
		KnowledgeBaseConfig config = new KnowledgeBaseConfig();
		config.setId(1);
		config.setConfigJson(objectMapper.createObjectNode());
		when(repository.findById(1)).thenReturn(Optional.of(config));

		KnowledgeBaseConfigService service = new KnowledgeBaseConfigService(
				repository,
				objectMapper,
				new DefaultResourceLoader(),
				null
		);

		assertThat(service.getSnapshot().websearchAllowedDomains())
				.containsExactlyElementsOf(OpenAiLlmClient.allowedWebSearchDomains);
	}
}
