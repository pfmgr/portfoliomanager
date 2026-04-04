package my.portfoliomanager.app.service;

import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.domain.LlmConfig;
import my.portfoliomanager.app.dto.LlmRuntimeConfigDto;
import my.portfoliomanager.app.dto.LlmRuntimeConfigUpdateDto;
import my.portfoliomanager.app.llm.LlmActionType;
import my.portfoliomanager.app.repository.LlmConfigRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRuntimeConfigServiceTest {
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void returnsDefaultConfigWhenMissing() {
		LlmConfigRepository repository = repositoryWithStore(new AtomicReference<>());
		LlmRuntimeConfigService service = new LlmRuntimeConfigService(
				repository,
				objectMapper,
				new LlmConfigCryptoService(buildProperties("")),
				buildProperties("")
		);

		LlmRuntimeConfigDto config = service.getConfig();

		assertThat(config.editable()).isFalse();
		assertThat(config.passwordSet()).isFalse();
		assertThat(config.standard().provider()).isEqualTo(LlmRuntimeConfigService.DEFAULT_PROVIDER);
		assertThat(config.standard().baseUrl()).isEqualTo(LlmRuntimeConfigService.DEFAULT_BASE_URL);
		assertThat(config.standard().model()).isEqualTo(LlmRuntimeConfigService.DEFAULT_MODEL);
		assertThat(config.websearch().mode()).isEqualTo("STANDARD");
		assertThat(config.extraction().mode()).isEqualTo("STANDARD");
		assertThat(config.narrative().mode()).isEqualTo("STANDARD");
		assertThat(config.websearch().enabled()).isFalse();
		assertThat(config.extraction().enabled()).isFalse();
		assertThat(config.narrative().enabled()).isFalse();
	}

	@Test
	void rejectsUpdatesWhenEncryptionPasswordMissing() {
		LlmConfigRepository repository = repositoryWithStore(new AtomicReference<>());
		LlmRuntimeConfigService service = new LlmRuntimeConfigService(
				repository,
				objectMapper,
				new LlmConfigCryptoService(buildProperties("")),
				buildProperties("")
		);

		assertThatThrownBy(() -> service.updateConfig(new LlmRuntimeConfigUpdateDto(null, null, null, null)))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("read-only");
	}

	@Test
	void switchesCustomToStandardAndClearsCustomApiKey() {
		AtomicReference<LlmConfig> store = new AtomicReference<>();
		LlmConfigRepository repository = repositoryWithStore(store);
		LlmRuntimeConfigService service = new LlmRuntimeConfigService(
				repository,
				objectMapper,
				new LlmConfigCryptoService(buildProperties("test-password")),
				buildProperties("test-password")
		);

		LlmRuntimeConfigUpdateDto updateCustom = new LlmRuntimeConfigUpdateDto(
				new LlmRuntimeConfigUpdateDto.StandardUpdateDto("openai", "https://api.openai.com/v1", "gpt-5-mini", "standard-key"),
				new LlmRuntimeConfigUpdateDto.ActionUpdateDto("CUSTOM", "openai", "https://custom.example/v1", "gpt-custom", "custom-key"),
				null,
				null
		);

		LlmRuntimeConfigDto custom = service.updateConfig(updateCustom);
		assertThat(custom.standard().apiKeySet()).isTrue();
		assertThat(custom.websearch().mode()).isEqualTo("CUSTOM");
		assertThat(custom.websearch().apiKeySet()).isTrue();
		assertThat(service.resolveAction(LlmActionType.WEBSEARCH).enabled()).isTrue();
		assertThat(service.resolveAction(LlmActionType.WEBSEARCH).apiKey()).isEqualTo("custom-key");

		String persistedJson = store.get().getConfigJson().toString();
		assertThat(persistedJson).doesNotContain("standard-key");
		assertThat(persistedJson).doesNotContain("custom-key");

		LlmRuntimeConfigUpdateDto switchToStandard = new LlmRuntimeConfigUpdateDto(
				new LlmRuntimeConfigUpdateDto.StandardUpdateDto(null, null, null, ""),
				new LlmRuntimeConfigUpdateDto.ActionUpdateDto("STANDARD", null, null, null, null),
				null,
				null
		);
		LlmRuntimeConfigDto standard = service.updateConfig(switchToStandard);
		assertThat(standard.standard().apiKeySet()).isFalse();
		assertThat(standard.websearch().mode()).isEqualTo("STANDARD");
		assertThat(standard.websearch().apiKeySet()).isFalse();
		assertThat(standard.websearch().enabled()).isFalse();

		LlmRuntimeConfigUpdateDto customWithoutKey = new LlmRuntimeConfigUpdateDto(
				null,
				new LlmRuntimeConfigUpdateDto.ActionUpdateDto("CUSTOM", "openai", "https://custom.example/v1", "gpt-custom", null),
				null,
				null
		);
		LlmRuntimeConfigDto customAgain = service.updateConfig(customWithoutKey);
		assertThat(customAgain.websearch().apiKeySet()).isFalse();
		assertThat(customAgain.websearch().enabled()).isFalse();
	}

	@Test
	void importsLegacyEnvConfigIntoStandardConfigWhenDbIsEmpty() {
		AtomicReference<LlmConfig> store = new AtomicReference<>();
		AppProperties properties = buildPropertiesWithLegacy("test-password", "openai", "https://api.openai.com/v1", "gpt-5-nano", "legacy-key");
		LlmRuntimeConfigService service = new LlmRuntimeConfigService(
				repositoryWithStore(store),
				objectMapper,
				new LlmConfigCryptoService(properties),
				properties
		);

		assertThat(service.migrateLegacyConfigIfNeeded()).isTrue();

		LlmRuntimeConfigDto config = service.getConfig();

		assertThat(config.standard().provider()).isEqualTo("openai");
		assertThat(config.standard().baseUrl()).isEqualTo("https://api.openai.com/v1");
		assertThat(config.standard().model()).isEqualTo("gpt-5-nano");
		assertThat(config.standard().apiKeySet()).isTrue();
		assertThat(config.websearch().mode()).isEqualTo("STANDARD");
		assertThat(service.resolveAction(LlmActionType.WEBSEARCH).apiKey()).isEqualTo("legacy-key");
		assertThat(store.get()).isNotNull();
		assertThat(store.get().getConfigJson().toString()).doesNotContain("legacy-key");
	}

	@Test
	void exportBackupDoesNotMaterializeLegacyEnvConfigWhenDbConfigMissing() {
		AtomicReference<LlmConfig> store = new AtomicReference<>();
		AppProperties properties = buildPropertiesWithLegacy("test-password", "openai", "https://api.openai.com/v1", "gpt-5-nano", "legacy-key");
		LlmRuntimeConfigService service = new LlmRuntimeConfigService(
				repositoryWithStore(store),
				objectMapper,
				new LlmConfigCryptoService(properties),
				properties
		);

		assertThat(service.exportBackupConfig()).isNull();
		assertThat(store.get()).isNull();
	}

	@Test
	void doesNotPersistLegacyConfigDuringNormalReadPath() {
		AtomicReference<LlmConfig> store = new AtomicReference<>();
		AppProperties properties = buildPropertiesWithLegacy("test-password", "openai", "https://api.openai.com/v1", "gpt-5-nano", "legacy-key");
		LlmRuntimeConfigService service = new LlmRuntimeConfigService(
				repositoryWithStore(store),
				objectMapper,
				new LlmConfigCryptoService(properties),
				properties
		);

		LlmRuntimeConfigDto config = service.getConfig();

		assertThat(config.standard().apiKeySet()).isFalse();
		assertThat(store.get()).isNull();
	}

	@Test
	void decryptsLegacyEncryptedApiKeyWhenProvidedWithoutPlaintextField() {
		AppProperties properties = buildProperties("test-password");
		LlmConfigCryptoService cryptoService = new LlmConfigCryptoService(properties);
		LlmRuntimeConfigService service = new LlmRuntimeConfigService(
				repositoryWithStore(new AtomicReference<>()),
				objectMapper,
				cryptoService,
				properties
		);

		String encrypted = cryptoService.encrypt("legacy-key");

		assertThat(service.resolveLegacyBackupApiKey(null, encrypted)).isEqualTo("legacy-key");
	}

	@Test
	void treatsDotLocalAsExternalAndRejectsHttpBaseUrl() {
		AtomicReference<LlmConfig> store = new AtomicReference<>();
		LlmRuntimeConfigService service = new LlmRuntimeConfigService(
				repositoryWithStore(store),
				objectMapper,
				new LlmConfigCryptoService(buildProperties("test-password")),
				buildProperties("test-password")
		);

		service.updateConfig(new LlmRuntimeConfigUpdateDto(
				new LlmRuntimeConfigUpdateDto.StandardUpdateDto("openai", "https://api.openai.com/v1", "gpt-5-mini", "standard-key"),
				new LlmRuntimeConfigUpdateDto.ActionUpdateDto("CUSTOM", "openai", "http://service.local/v1", "gpt-5-mini", "custom-key"),
				null,
				null
		));

		assertThat(service.resolveAction(LlmActionType.WEBSEARCH).enabled()).isFalse();
		assertThat(service.resolveAction(LlmActionType.WEBSEARCH).disableReason()).isEqualTo("invalid base url");
	}

	private LlmConfigRepository repositoryWithStore(AtomicReference<LlmConfig> store) {
		LlmConfigRepository repository = Mockito.mock(LlmConfigRepository.class);
		Mockito.when(repository.findById(1)).thenAnswer(invocation -> Optional.ofNullable(store.get()));
		Mockito.when(repository.save(Mockito.any(LlmConfig.class))).thenAnswer(invocation -> {
			LlmConfig value = invocation.getArgument(0);
			store.set(value);
			return value;
		});
		return repository;
	}

	private AppProperties buildProperties(String password) {
		AppProperties.Security security = new AppProperties.Security("admin", "admin");
		AppProperties.Jwt jwt = new AppProperties.Jwt("secretsecretsecretsecretsecretsecret", "hashhashhashhashhashhashhashhash", "issuer", 3600L, 300L, 1000, true);
		AppProperties.LegacyLlm legacyLlm = new AppProperties.LegacyLlm(null, null, null, null);
		AppProperties.Kb kb = new AppProperties.Kb(true, true);
		return new AppProperties(security, jwt, password, legacyLlm, kb);
	}

	private AppProperties buildPropertiesWithLegacy(String password, String provider, String baseUrl, String model, String apiKey) {
		AppProperties.Security security = new AppProperties.Security("admin", "admin");
		AppProperties.Jwt jwt = new AppProperties.Jwt("secretsecretsecretsecretsecretsecret", "hashhashhashhashhashhashhashhash", "issuer", 3600L, 300L, 1000, true);
		AppProperties.LegacyLlm legacyLlm = new AppProperties.LegacyLlm(provider, baseUrl, model, apiKey);
		AppProperties.Kb kb = new AppProperties.Kb(true, true);
		return new AppProperties(security, jwt, password, legacyLlm, kb);
	}
}
