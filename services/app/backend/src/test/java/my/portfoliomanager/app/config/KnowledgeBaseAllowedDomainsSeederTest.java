package my.portfoliomanager.app.config;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import my.portfoliomanager.app.domain.KnowledgeBaseConfig;
import my.portfoliomanager.app.repository.KnowledgeBaseConfigRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class KnowledgeBaseAllowedDomainsSeederTest {
	@Test
	void seedsDefaultAllowedDomainsWhenMissing() {
		KnowledgeBaseConfigRepository repository = mock(KnowledgeBaseConfigRepository.class);
		ResourceLoader resourceLoader = mock(ResourceLoader.class);
		ObjectMapper objectMapper = new ObjectMapper();
		when(repository.findById(1)).thenReturn(Optional.empty());
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(resourceLoader.getResource(anyString())).thenReturn(new ClassPathResource("kb_websearch_allowed_domains.json"));

		KnowledgeBaseAllowedDomainsSeeder seeder = new KnowledgeBaseAllowedDomainsSeeder(repository, objectMapper, resourceLoader);
		seeder.run(null);

		ArgumentCaptor<KnowledgeBaseConfig> captor = ArgumentCaptor.forClass(KnowledgeBaseConfig.class);
		verify(repository).save(captor.capture());
		KnowledgeBaseConfig saved = captor.getValue();
		assertThat(saved.getId()).isEqualTo(1);
		JsonNode allowedDomains = saved.getConfigJson().get("websearch_allowed_domains");
		assertThat(allowedDomains).isNotNull();
		assertThat(allowedDomains.isArray()).isTrue();
		List<String> domains = new ArrayList<>();
		allowedDomains.forEach(node -> domains.add(node.asText()));
		assertThat(domains).contains("marketscreener.com", "statista.com", "finbox.com");
	}

	@Test
	void skipsSeedingWhenAllowedDomainsAlreadyPresent() {
		KnowledgeBaseConfigRepository repository = mock(KnowledgeBaseConfigRepository.class);
		ResourceLoader resourceLoader = mock(ResourceLoader.class);
		ObjectMapper objectMapper = new ObjectMapper();
		ObjectNode configJson = objectMapper.createObjectNode();
		configJson.putArray("websearch_allowed_domains").add("example.com");
		KnowledgeBaseConfig config = new KnowledgeBaseConfig();
		config.setId(1);
		config.setConfigJson(configJson);
		when(repository.findById(1)).thenReturn(Optional.of(config));

		KnowledgeBaseAllowedDomainsSeeder seeder = new KnowledgeBaseAllowedDomainsSeeder(repository, objectMapper, resourceLoader);
		seeder.run(null);

		verify(repository, never()).save(any());
		verifyNoInteractions(resourceLoader);
	}
}
