package my.portfoliomanager.app.config;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import my.portfoliomanager.app.domain.KnowledgeBaseConfig;
import my.portfoliomanager.app.repository.KnowledgeBaseConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class KnowledgeBaseAllowedDomainsSeeder implements ApplicationRunner {
	private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseAllowedDomainsSeeder.class);
	private static final int CONFIG_ID = 1;
	private static final String RESOURCE_PATH = "classpath:kb_websearch_allowed_domains.json";

	private final KnowledgeBaseConfigRepository repository;
	private final ObjectMapper objectMapper;
	private final ResourceLoader resourceLoader;

	public KnowledgeBaseAllowedDomainsSeeder(KnowledgeBaseConfigRepository repository,
												ObjectMapper objectMapper,
												ResourceLoader resourceLoader) {
		this.repository = repository;
		this.objectMapper = objectMapper;
		this.resourceLoader = resourceLoader;
	}

	@Override
	public void run(ApplicationArguments args) {
		KnowledgeBaseConfig config = repository.findById(CONFIG_ID).orElse(null);
		if (hasAllowedDomains(config)) {
			return;
		}
		List<String> domains = loadDomains();
		if (domains.isEmpty()) {
			logger.warn("KB allowed domains empty or missing: {}", RESOURCE_PATH);
			return;
		}
		KnowledgeBaseConfig entity = config == null ? new KnowledgeBaseConfig() : config;
		ObjectNode configJson = toConfigObject(config == null ? null : config.getConfigJson());
		configJson.set("websearch_allowed_domains", objectMapper.valueToTree(domains));
		entity.setId(CONFIG_ID);
		entity.setConfigJson(configJson);
		entity.setUpdatedAt(LocalDateTime.now());
		repository.save(entity);
		logger.info("Seeded KB websearch allowed domains from {}", RESOURCE_PATH);
	}

	private boolean hasAllowedDomains(KnowledgeBaseConfig config) {
		if (config == null || config.getConfigJson() == null) {
			return false;
		}
		JsonNode allowedDomains = config.getConfigJson().get("websearch_allowed_domains");
		if (allowedDomains == null || allowedDomains.isNull()) {
			return false;
		}
		if (allowedDomains.isArray()) {
			for (JsonNode item : allowedDomains) {
				if (item == null || item.isNull()) {
					continue;
				}
				String value = item.asText();
				if (value != null && !value.trim().isBlank()) {
					return true;
				}
			}
			return false;
		}
		if (allowedDomains.isObject()) {
			return allowedDomains.size() > 0;
		}
		String value = allowedDomains.asText();
		return value != null && !value.isBlank();
	}

	private ObjectNode toConfigObject(JsonNode existing) {
		if (existing != null && existing.isObject()) {
			return ((ObjectNode) existing).deepCopy();
		}
		return objectMapper.createObjectNode();
	}

	private List<String> loadDomains() {
		Resource resource = resourceLoader.getResource(RESOURCE_PATH);
		if (!resource.exists()) {
			logger.warn("KB allowed domains resource not found: {}", RESOURCE_PATH);
			return List.of();
		}
		try (InputStream inputStream = resource.getInputStream()) {
			List<String> domains = objectMapper.readValue(inputStream, new TypeReference<>() {
			});
			return normalizeDomains(domains);
		} catch (Exception ex) {
			logger.error("Failed to load KB allowed domains from {}: {}", RESOURCE_PATH, ex.getMessage());
			return List.of();
		}
	}

	private List<String> normalizeDomains(List<String> domains) {
		if (domains == null) {
			return List.of();
		}
		List<String> cleaned = new ArrayList<>();
		for (String domain : domains) {
			if (domain == null) {
				continue;
			}
			String trimmed = domain.trim().toLowerCase(Locale.ROOT);
			if (trimmed.isBlank()) {
				continue;
			}
			cleaned.add(trimmed);
		}
		return cleaned;
	}
}
