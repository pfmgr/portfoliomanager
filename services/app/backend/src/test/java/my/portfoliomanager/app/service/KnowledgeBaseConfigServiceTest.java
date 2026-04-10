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
				.add(" HTTPS://WWW.Example.COM/research?q=1 ")
				.add("example.com.")
				.add("sub.domain.co.uk")
				.add("issuer.example/path")
				.add("127.0.0.1")
				.add("localhost")
				.add("service.local")
				.add("example.com:443")
				.add("*.example.com");
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
	void getSnapshot_fallsBackToDefaultAllowedDomainsWhenNormalizedListIsEmpty() {
		KnowledgeBaseConfigRepository repository = mock(KnowledgeBaseConfigRepository.class);
		KnowledgeBaseConfig config = new KnowledgeBaseConfig();
		config.setId(1);
		ObjectNode json = objectMapper.createObjectNode();
		json.putArray("websearch_allowed_domains")
				.add("127.0.0.1")
				.add("localhost")
				.add("service.local")
				.add("example.com:443")
				.add("*.example.com");
		config.setConfigJson(json);
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
