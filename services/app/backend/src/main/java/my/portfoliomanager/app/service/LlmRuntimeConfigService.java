package my.portfoliomanager.app.service;

import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.domain.LlmConfig;
import my.portfoliomanager.app.dto.LlmConfigBackupDto;
import my.portfoliomanager.app.dto.LlmRuntimeConfigDto;
import my.portfoliomanager.app.dto.LlmRuntimeConfigUpdateDto;
import my.portfoliomanager.app.llm.LlmActionType;
import my.portfoliomanager.app.repository.LlmConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class LlmRuntimeConfigService {
	private static final int CONFIG_ID = 1;
	public static final String DEFAULT_PROVIDER = "openai";
	public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
	public static final String DEFAULT_MODEL = "gpt-5-mini";

	private final LlmConfigRepository repository;
	private final ObjectMapper objectMapper;
	private final LlmConfigCryptoService cryptoService;
	private final AppProperties.LegacyLlm legacyLlm;

	public LlmRuntimeConfigService(LlmConfigRepository repository,
							   ObjectMapper objectMapper,
							   LlmConfigCryptoService cryptoService,
							   AppProperties properties) {
		this.repository = repository;
		this.objectMapper = objectMapper;
		this.cryptoService = cryptoService;
		this.legacyLlm = properties == null ? null : properties.legacyLlm();
	}

	public LlmRuntimeConfigDto getConfig() {
		StoredConfig config = loadStoredConfig();
		return toDto(config);
	}

	public LlmRuntimeConfigDto updateConfig(LlmRuntimeConfigUpdateDto request) {
		if (!cryptoService.isPasswordSet()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"LLM config is read-only: set LLM_CONFIG_ENCRYPTION_PASSWORD to enable updates.");
		}
		StoredConfig updated = merge(loadStoredConfig(), request);
		persist(updated);
		return toDto(updated);
	}

	public ResolvedActionConfig resolveAction(LlmActionType actionType) {
		StoredConfig stored = loadStoredConfig();
		StoredAction action = switch (actionType) {
			case WEBSEARCH -> stored.websearch();
			case EXTRACTION -> stored.extraction();
			case NARRATIVE -> stored.narrative();
		};

		boolean standardMode = "STANDARD".equals(action.mode());
		String provider = standardMode ? stored.standardProvider() : action.provider();
		String baseUrl = standardMode ? stored.standardBaseUrl() : action.baseUrl();
		String model = standardMode ? stored.standardModel() : action.model();
		String apiKey = standardMode
				? cryptoService.decrypt(stored.standardApiKeyEncrypted())
				: cryptoService.decrypt(action.apiKeyEncrypted());

		String disableReason = resolveDisableReason(provider, baseUrl, apiKey);
		boolean enabled = disableReason == null;
		boolean external = enabled && !isLocalBaseUrl(baseUrl);

		return new ResolvedActionConfig(actionType, provider, baseUrl, model, apiKey, enabled, external, disableReason);
	}

	public LlmConfigBackupDto exportBackupConfig() {
		LlmConfig entity = repository.findById(CONFIG_ID).orElse(null);
		if (entity == null || entity.getConfigJson() == null) {
			return null;
		}
		StoredConfig stored = parse(entity.getConfigJson());
		if (stored == null) {
			throw new IllegalStateException("Stored LLM config could not be parsed for backup export.");
		}
		stored = normalize(stored);
		return new LlmConfigBackupDto(
				new LlmConfigBackupDto.StandardBackupDto(
						stored.standardProvider(),
						stored.standardBaseUrl(),
						stored.standardModel(),
						decryptForBackup(stored.standardApiKeyEncrypted())
				),
				toBackupAction(stored.websearch()),
				toBackupAction(stored.extraction()),
				toBackupAction(stored.narrative())
		);
	}

	public boolean migrateLegacyConfigIfNeeded() {
		LlmConfig entity = repository.findById(CONFIG_ID).orElse(null);
		if (entity != null && entity.getConfigJson() != null) {
			return false;
		}
		StoredConfig legacyImported = importLegacyConfigIfAvailable();
		return legacyImported != null;
	}

	public void importBackupConfig(LlmConfigBackupDto backupConfig) {
		if (backupConfig == null) {
			return;
		}
		if (containsApiKeys(backupConfig) && !cryptoService.isPasswordSet()) {
			throw new IllegalStateException(
					"LLM config backup import requires LLM_CONFIG_ENCRYPTION_PASSWORD when API keys are present.");
		}
		StoredConfig imported = new StoredConfig(
				normalizeProvider(backupConfig.standard() == null ? null : backupConfig.standard().provider()),
				normalizeBaseUrl(backupConfig.standard() == null ? null : backupConfig.standard().baseUrl()),
				normalizeModel(backupConfig.standard() == null ? null : backupConfig.standard().model()),
				encryptImportedApiKey(backupConfig.standard() == null ? null : backupConfig.standard().apiKey()),
				fromBackupAction(backupConfig.websearch()),
				fromBackupAction(backupConfig.extraction()),
				fromBackupAction(backupConfig.narrative())
		);
		persist(imported);
	}

	public String resolveLegacyBackupApiKey(String plaintextCandidate, String encryptedCandidate) {
		String plaintext = trimToNull(plaintextCandidate);
		if (plaintext != null) {
			return plaintext;
		}
		String encrypted = trimToNull(encryptedCandidate);
		if (encrypted == null) {
			return null;
		}
		if (!cryptoService.isPasswordSet()) {
			throw new IllegalStateException(
					"Legacy LLM config backup import requires LLM_CONFIG_ENCRYPTION_PASSWORD to decrypt encrypted API keys.");
		}
		String decrypted = cryptoService.decrypt(encrypted);
		if (!hasText(decrypted)) {
			throw new IllegalStateException(
					"Legacy LLM config backup import failed: unable to decrypt encrypted API keys with current LLM_CONFIG_ENCRYPTION_PASSWORD.");
		}
		return decrypted;
	}

	private StoredConfig merge(StoredConfig current, LlmRuntimeConfigUpdateDto request) {
		if (request == null) {
			return current;
		}

		String standardProvider = normalizeProvider(firstNonBlank(
				request.standard() == null ? null : request.standard().provider(),
				current.standardProvider()
		));
		String standardBaseUrl = normalizeBaseUrl(firstNonBlank(
				request.standard() == null ? null : request.standard().baseUrl(),
				current.standardBaseUrl()
		));
		String standardModel = normalizeModel(firstNonBlank(
				request.standard() == null ? null : request.standard().model(),
				current.standardModel()
		));

		String standardKeyEncrypted = applyApiKeyUpdate(
				current.standardApiKeyEncrypted(),
				request.standard() == null ? null : request.standard().apiKey()
		);

		StoredAction websearch = mergeAction(current.websearch(), request.websearch());
		StoredAction extraction = mergeAction(current.extraction(), request.extraction());
		StoredAction narrative = mergeAction(current.narrative(), request.narrative());

		return new StoredConfig(
				standardProvider,
				standardBaseUrl,
				standardModel,
				standardKeyEncrypted,
				websearch,
				extraction,
				narrative
		);
	}

	private StoredAction mergeAction(StoredAction current, LlmRuntimeConfigUpdateDto.ActionUpdateDto request) {
		if (request == null) {
			return current;
		}
		String mode = normalizeMode(firstNonBlank(request.mode(), current.mode()));
		if ("STANDARD".equals(mode)) {
			return new StoredAction("STANDARD", null, null, null, null);
		}
		String provider = normalizeProvider(firstNonBlank(request.provider(), current.provider()));
		String baseUrl = normalizeBaseUrl(firstNonBlank(request.baseUrl(), current.baseUrl()));
		String model = normalizeModel(firstNonBlank(request.model(), current.model()));
		String apiKeyEncrypted = applyApiKeyUpdate(current.apiKeyEncrypted(), request.apiKey());
		return new StoredAction("CUSTOM", provider, baseUrl, model, apiKeyEncrypted);
	}

	private LlmConfigBackupDto.ActionBackupDto toBackupAction(StoredAction action) {
		if (action == null) {
			return new LlmConfigBackupDto.ActionBackupDto("STANDARD", null, null, null, null);
		}
		if ("STANDARD".equals(action.mode())) {
			return new LlmConfigBackupDto.ActionBackupDto("STANDARD", null, null, null, null);
		}
		return new LlmConfigBackupDto.ActionBackupDto(
				action.mode(),
				action.provider(),
				action.baseUrl(),
				action.model(),
				decryptForBackup(action.apiKeyEncrypted())
		);
	}

	private StoredAction fromBackupAction(LlmConfigBackupDto.ActionBackupDto backupAction) {
		if (backupAction == null) {
			return new StoredAction("STANDARD", null, null, null, null);
		}
		String mode = normalizeMode(backupAction.mode());
		if ("STANDARD".equals(mode)) {
			return new StoredAction("STANDARD", null, null, null, null);
		}
		return new StoredAction(
				"CUSTOM",
				normalizeProvider(backupAction.provider()),
				normalizeBaseUrl(backupAction.baseUrl()),
				normalizeModel(backupAction.model()),
				encryptImportedApiKey(backupAction.apiKey())
		);
	}

	private String applyApiKeyUpdate(String currentEncryptedValue, String requestApiKey) {
		if (requestApiKey == null) {
			return currentEncryptedValue;
		}
		if (requestApiKey.isBlank()) {
			return null;
		}
		return cryptoService.encrypt(requestApiKey.trim());
	}

	private String decryptForBackup(String encryptedValue) {
		if (!hasText(encryptedValue)) {
			return null;
		}
		if (!cryptoService.isPasswordSet()) {
			throw new IllegalStateException(
					"LLM config backup export requires LLM_CONFIG_ENCRYPTION_PASSWORD to decrypt API keys.");
		}
		String decrypted = cryptoService.decrypt(encryptedValue);
		if (!hasText(decrypted)) {
			throw new IllegalStateException(
					"LLM config backup export failed: unable to decrypt API keys with current LLM_CONFIG_ENCRYPTION_PASSWORD.");
		}
		return decrypted;
	}

	private String encryptImportedApiKey(String plaintextApiKey) {
		String trimmed = trimToNull(plaintextApiKey);
		if (trimmed == null) {
			return null;
		}
		if (!cryptoService.isPasswordSet()) {
			throw new IllegalStateException(
					"LLM config backup import requires LLM_CONFIG_ENCRYPTION_PASSWORD when API keys are present.");
		}
		return cryptoService.encrypt(trimmed);
	}

	private boolean containsApiKeys(LlmConfigBackupDto backupConfig) {
		return hasText(backupConfig.standard() == null ? null : backupConfig.standard().apiKey())
				|| hasText(backupConfig.websearch() == null ? null : backupConfig.websearch().apiKey())
				|| hasText(backupConfig.extraction() == null ? null : backupConfig.extraction().apiKey())
				|| hasText(backupConfig.narrative() == null ? null : backupConfig.narrative().apiKey());
	}

	private void persist(StoredConfig config) {
		LlmConfig entity = repository.findById(CONFIG_ID).orElseGet(LlmConfig::new);
		entity.setId(CONFIG_ID);
		entity.setConfigJson(objectMapper.valueToTree(config));
		entity.setUpdatedAt(LocalDateTime.now());
		repository.save(entity);
	}

	private LlmRuntimeConfigDto toDto(StoredConfig stored) {
		boolean passwordSet = cryptoService.isPasswordSet();
		boolean standardApiKeySet = hasText(stored.standardApiKeyEncrypted());
		return new LlmRuntimeConfigDto(
				passwordSet,
				passwordSet,
				new LlmRuntimeConfigDto.StandardConfigDto(
						stored.standardProvider(),
						stored.standardBaseUrl(),
						stored.standardModel(),
						standardApiKeySet
				),
				toActionDto(stored, LlmActionType.WEBSEARCH),
				toActionDto(stored, LlmActionType.EXTRACTION),
				toActionDto(stored, LlmActionType.NARRATIVE)
		);
	}

	private LlmRuntimeConfigDto.ActionConfigDto toActionDto(StoredConfig stored, LlmActionType actionType) {
		StoredAction action = switch (actionType) {
			case WEBSEARCH -> stored.websearch();
			case EXTRACTION -> stored.extraction();
			case NARRATIVE -> stored.narrative();
		};
		boolean standardMode = "STANDARD".equals(action.mode());
		String provider = standardMode ? stored.standardProvider() : action.provider();
		String baseUrl = standardMode ? stored.standardBaseUrl() : action.baseUrl();
		String model = standardMode ? stored.standardModel() : action.model();
		boolean apiKeySet = standardMode ? hasText(stored.standardApiKeyEncrypted()) : hasText(action.apiKeyEncrypted());
		ResolvedActionConfig resolved = resolveAction(actionType);
		return new LlmRuntimeConfigDto.ActionConfigDto(
				action.mode(),
				provider,
				baseUrl,
				model,
				apiKeySet,
				resolved.enabled(),
				resolved.disableReason()
		);
	}

	private StoredConfig loadStoredConfig() {
		LlmConfig entity = repository.findById(CONFIG_ID).orElse(null);
		if (entity == null || entity.getConfigJson() == null) {
			return defaults();
		}
		StoredConfig parsed = parse(entity.getConfigJson());
		return parsed == null ? defaults() : normalize(parsed);
	}

	private StoredConfig parse(JsonNode json) {
		try {
			return objectMapper.treeToValue(json, StoredConfig.class);
		} catch (Exception ex) {
			return null;
		}
	}

	private StoredConfig defaults() {
		return new StoredConfig(
				DEFAULT_PROVIDER,
				DEFAULT_BASE_URL,
				DEFAULT_MODEL,
				null,
				new StoredAction("STANDARD", null, null, null, null),
				new StoredAction("STANDARD", null, null, null, null),
				new StoredAction("STANDARD", null, null, null, null)
		);
	}

	private StoredConfig importLegacyConfigIfAvailable() {
		if (!cryptoService.isPasswordSet() || legacyLlm == null) {
			return null;
		}
		String legacyApiKey = trimToNull(legacyLlm.apiKey());
		if (legacyApiKey == null) {
			return null;
		}
		StoredConfig imported = new StoredConfig(
				normalizeProvider(legacyLlm.provider()),
				normalizeBaseUrl(legacyLlm.baseUrl()),
				normalizeModel(legacyLlm.model()),
				cryptoService.encrypt(legacyApiKey),
				new StoredAction("STANDARD", null, null, null, null),
				new StoredAction("STANDARD", null, null, null, null),
				new StoredAction("STANDARD", null, null, null, null)
		);
		persist(imported);
		return imported;
	}

	private StoredConfig normalize(StoredConfig raw) {
		if (raw == null) {
			return defaults();
		}
		return new StoredConfig(
				normalizeProvider(raw.standardProvider()),
				normalizeBaseUrl(raw.standardBaseUrl()),
				normalizeModel(raw.standardModel()),
				trimToNull(raw.standardApiKeyEncrypted()),
				normalizeAction(raw.websearch()),
				normalizeAction(raw.extraction()),
				normalizeAction(raw.narrative())
		);
	}

	private StoredAction normalizeAction(StoredAction raw) {
		if (raw == null) {
			return new StoredAction("STANDARD", null, null, null, null);
		}
		String mode = normalizeMode(raw.mode());
		if ("STANDARD".equals(mode)) {
			return new StoredAction("STANDARD", null, null, null, null);
		}
		return new StoredAction(
				"CUSTOM",
				normalizeProvider(raw.provider()),
				normalizeBaseUrl(raw.baseUrl()),
				normalizeModel(raw.model()),
				trimToNull(raw.apiKeyEncrypted())
		);
	}

	private String normalizeMode(String raw) {
		if (raw == null || raw.isBlank()) {
			return "STANDARD";
		}
		return "CUSTOM".equalsIgnoreCase(raw.trim()) ? "CUSTOM" : "STANDARD";
	}

	private String normalizeProvider(String value) {
		String normalized = value == null ? DEFAULT_PROVIDER : value.trim().toLowerCase(Locale.ROOT);
		return normalized.isBlank() ? DEFAULT_PROVIDER : normalized;
	}

	private String normalizeBaseUrl(String value) {
		String trimmed = trimToNull(value);
		return trimmed == null ? DEFAULT_BASE_URL : trimmed;
	}

	private String normalizeModel(String value) {
		String trimmed = trimToNull(value);
		return trimmed == null ? DEFAULT_MODEL : trimmed;
	}

	private String resolveDisableReason(String provider, String baseUrl, String apiKey) {
		if (isDisabledProvider(provider)) {
			return "provider disabled";
		}
		if (!DEFAULT_PROVIDER.equals(provider)) {
			return "provider unsupported";
		}
		if (!isValidBaseUrl(baseUrl)) {
			return "invalid base url";
		}
		if (apiKey == null || apiKey.isBlank()) {
			return "missing api key";
		}
		return null;
	}

	private boolean isDisabledProvider(String value) {
		if (value == null) {
			return false;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		return normalized.equals("none") || normalized.equals("noop") || normalized.equals("disabled") || normalized.equals("off");
	}

	private boolean isValidBaseUrl(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return false;
		}
		try {
			URI uri = URI.create(baseUrl);
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (scheme == null || host == null || host.isBlank()) {
				return false;
			}
			if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
				return false;
			}
			String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
			if (normalizedScheme.equals("https")) {
				return true;
			}
			return normalizedScheme.equals("http") && isLocalHost(host);
		} catch (Exception ex) {
			return false;
		}
	}

	private boolean isLocalHost(String host) {
		if (host == null || host.isBlank()) {
			return false;
		}
		String normalized = host.toLowerCase(Locale.ROOT);
		return normalized.equals("localhost")
				|| normalized.equals("127.0.0.1")
				|| normalized.equals("::1");
	}

	private boolean isLocalBaseUrl(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return false;
		}
		try {
			URI uri = URI.create(baseUrl);
			String host = uri.getHost();
			if (host == null || host.isBlank()) {
				return false;
			}
			String normalized = host.toLowerCase(Locale.ROOT);
			return normalized.equals("localhost")
					|| normalized.equals("127.0.0.1")
					|| normalized.equals("::1");
		} catch (Exception ex) {
			return false;
		}
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isBlank() ? null : trimmed;
	}

	private String firstNonBlank(String first, String fallback) {
		String normalizedFirst = trimToNull(first);
		return normalizedFirst == null ? fallback : normalizedFirst;
	}

	private record StoredConfig(
			String standardProvider,
			String standardBaseUrl,
			String standardModel,
			String standardApiKeyEncrypted,
			StoredAction websearch,
			StoredAction extraction,
			StoredAction narrative
	) {
	}

	private record StoredAction(
			String mode,
			String provider,
			String baseUrl,
			String model,
			String apiKeyEncrypted
	) {
	}

	public record ResolvedActionConfig(
			LlmActionType actionType,
			String provider,
			String baseUrl,
			String model,
			String apiKey,
			boolean enabled,
			boolean externalProvider,
			String disableReason
	) {
	}
}
