package my.portfoliomanager.app.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.domain.KnowledgeBaseConfig;
import my.portfoliomanager.app.dto.KnowledgeBaseConfigDto;
import my.portfoliomanager.app.dto.KnowledgeBaseQualityGateConfigDto;
import my.portfoliomanager.app.dto.KnowledgeBaseQualityGateProfileDto;
import my.portfoliomanager.app.llm.OpenAiLlmClient;
import my.portfoliomanager.app.repository.KnowledgeBaseConfigRepository;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class KnowledgeBaseConfigService {
	private static final int CONFIG_ID = 1;
	private static final int DEFAULT_REFRESH_INTERVAL_DAYS = 30;
	private static final int DEFAULT_BATCH_SIZE = 10;
	private static final int DEFAULT_BATCH_MAX_CHARS = 120000;
	private static final int DEFAULT_MAX_PARALLEL_BULK_BATCHES = 2;
	private static final int DEFAULT_MAX_BATCHES_PER_RUN = 5;
	private static final int DEFAULT_POLL_INTERVAL_SECONDS = 300;
	private static final int DEFAULT_MAX_INSTRUMENTS_PER_RUN = 100;
	private static final int DEFAULT_MAX_RETRIES = 3;
	private static final int DEFAULT_BASE_BACKOFF = 2;
	private static final int DEFAULT_MAX_BACKOFF = 30;
	private static final int DEFAULT_DOSSIER_MAX_CHARS = 15000;
	private static final int DEFAULT_MIN_DAYS_BETWEEN_RUNS = 7;
	private static final int DEFAULT_RUN_TIMEOUT_MINUTES = 30;
	private static final String DEFAULT_WEBSEARCH_REASONING_EFFORT = "low";
	private static final int DEFAULT_BULK_MIN_CITATIONS = 2;
	private static final boolean DEFAULT_BULK_REQUIRE_PRIMARY_SOURCE = true;
	private static final double DEFAULT_ALTERNATIVES_MIN_SIMILARITY_SCORE = 0.6;
	private static final boolean DEFAULT_EXTRACTION_EVIDENCE_REQUIRED = true;
	private static final String DEFAULT_QUALITY_GATE_PROFILE_KEY = "BALANCED";

	private final KnowledgeBaseConfigRepository repository;
	private final ObjectMapper objectMapper;
	private final ResourceLoader resourceLoader;
	private final LayerTargetConfigService layerTargetConfigService;
	private final KnowledgeBaseQualityGateConfigSnapshot defaultQualityGateConfig;

	public KnowledgeBaseConfigService(KnowledgeBaseConfigRepository repository,
									 ObjectMapper objectMapper,
									 ResourceLoader resourceLoader,
									 LayerTargetConfigService layerTargetConfigService) {
		this.repository = repository;
		this.objectMapper = objectMapper;
		this.resourceLoader = resourceLoader;
		this.layerTargetConfigService = layerTargetConfigService;
		this.defaultQualityGateConfig = loadDefaultQualityGateConfig();
	}

	public KnowledgeBaseConfigDto getConfig() {
		return toDto(getSnapshot());
	}

	public KnowledgeBaseConfigDto updateConfig(KnowledgeBaseConfigDto request) {
		KnowledgeBaseConfigSnapshot normalized = applyDefaults(request);
		KnowledgeBaseConfig entity = repository.findById(CONFIG_ID).orElseGet(KnowledgeBaseConfig::new);
		entity.setId(CONFIG_ID);
		entity.setConfigJson(objectMapper.valueToTree(toDto(normalized)));
		entity.setUpdatedAt(LocalDateTime.now());
		repository.save(entity);
		return toDto(normalized);
	}

	public KnowledgeBaseConfigSnapshot getSnapshot() {
		KnowledgeBaseConfig entity = repository.findById(CONFIG_ID).orElse(null);
		if (entity == null || entity.getConfigJson() == null) {
			return applyDefaults(null);
		}
		KnowledgeBaseConfigDto raw = parseDto(entity.getConfigJson());
		return applyDefaults(raw);
	}

	private KnowledgeBaseConfigDto parseDto(JsonNode json) {
		try {
			return objectMapper.treeToValue(json, KnowledgeBaseConfigDto.class);
		} catch (Exception ex) {
			return null;
		}
	}

	private KnowledgeBaseConfigSnapshot applyDefaults(KnowledgeBaseConfigDto raw) {
		boolean enabled = raw != null && raw.enabled() != null ? raw.enabled() : false;
		boolean autoApprove = raw != null && raw.autoApprove() != null && raw.autoApprove();
		boolean applyExtractions = raw != null && raw.applyExtractionsToOverrides() != null && raw.applyExtractionsToOverrides();
		boolean overwriteOverrides = raw != null && raw.overwriteExistingOverrides() != null && raw.overwriteExistingOverrides();

		int refreshInterval = positiveOrDefault(raw == null ? null : raw.refreshIntervalDays(), DEFAULT_REFRESH_INTERVAL_DAYS);
		int batchSize = positiveOrDefault(raw == null ? null : raw.batchSizeInstruments(), DEFAULT_BATCH_SIZE);
		int batchMaxChars = positiveOrDefault(raw == null ? null : raw.batchMaxInputChars(), DEFAULT_BATCH_MAX_CHARS);
		int maxParallelBulkBatches = positiveOrDefault(raw == null ? null : raw.maxParallelBulkBatches(), DEFAULT_MAX_PARALLEL_BULK_BATCHES);
		int maxBatchesPerRun = positiveOrDefault(raw == null ? null : raw.maxBatchesPerRun(), DEFAULT_MAX_BATCHES_PER_RUN);
		int pollInterval = positiveOrDefault(raw == null ? null : raw.pollIntervalSeconds(), DEFAULT_POLL_INTERVAL_SECONDS);
		int maxInstrumentsPerRun = positiveOrDefault(raw == null ? null : raw.maxInstrumentsPerRun(), DEFAULT_MAX_INSTRUMENTS_PER_RUN);
		int maxRetries = positiveOrDefault(raw == null ? null : raw.maxRetriesPerInstrument(), DEFAULT_MAX_RETRIES);
		int baseBackoff = positiveOrDefault(raw == null ? null : raw.baseBackoffSeconds(), DEFAULT_BASE_BACKOFF);
		int maxBackoff = positiveOrDefault(raw == null ? null : raw.maxBackoffSeconds(), DEFAULT_MAX_BACKOFF);
		if (maxBackoff < baseBackoff) {
			maxBackoff = baseBackoff;
		}
		int dossierMaxChars = positiveOrDefault(raw == null ? null : raw.dossierMaxChars(), DEFAULT_DOSSIER_MAX_CHARS);
		int minDaysBetweenRuns = positiveOrDefault(raw == null ? null : raw.kbRefreshMinDaysBetweenRunsPerInstrument(), DEFAULT_MIN_DAYS_BETWEEN_RUNS);
		int runTimeout = positiveOrDefault(raw == null ? null : raw.runTimeoutMinutes(), DEFAULT_RUN_TIMEOUT_MINUTES);
		String reasoningEffort = normalizeReasoningEffort(raw == null ? null : raw.websearchReasoningEffort());
		int bulkMinCitations = positiveOrDefault(raw == null ? null : raw.bulkMinCitations(), DEFAULT_BULK_MIN_CITATIONS);
		boolean bulkRequirePrimarySource = raw != null && raw.bulkRequirePrimarySource() != null
				? raw.bulkRequirePrimarySource()
				: DEFAULT_BULK_REQUIRE_PRIMARY_SOURCE;
		double alternativesMinSimilarityScore = raw != null && raw.alternativesMinSimilarityScore() != null
				? raw.alternativesMinSimilarityScore()
				: DEFAULT_ALTERNATIVES_MIN_SIMILARITY_SCORE;
		if (alternativesMinSimilarityScore < 0 || alternativesMinSimilarityScore > 1) {
			alternativesMinSimilarityScore = DEFAULT_ALTERNATIVES_MIN_SIMILARITY_SCORE;
		}
		boolean extractionEvidenceRequired = raw != null && raw.extractionEvidenceRequired() != null
				? raw.extractionEvidenceRequired()
				: DEFAULT_EXTRACTION_EVIDENCE_REQUIRED;
		KnowledgeBaseQualityGateConfigSnapshot qualityGateProfiles = normalizeQualityGateConfig(
				raw == null ? null : raw.qualityGateProfiles()
		);

		List<String> allowedDomains = normalizeDomains(raw == null ? null : raw.websearchAllowedDomains());
		if (allowedDomains.isEmpty()) {
			allowedDomains = new ArrayList<>(OpenAiLlmClient.allowedWebSearchDomains);
		}

		return new KnowledgeBaseConfigSnapshot(
				enabled,
				refreshInterval,
				autoApprove,
				applyExtractions,
				overwriteOverrides,
				batchSize,
				batchMaxChars,
				maxParallelBulkBatches,
				maxBatchesPerRun,
				pollInterval,
				maxInstrumentsPerRun,
				maxRetries,
				baseBackoff,
				maxBackoff,
				dossierMaxChars,
				minDaysBetweenRuns,
				runTimeout,
				reasoningEffort,
				allowedDomains,
				bulkMinCitations,
				bulkRequirePrimarySource,
				alternativesMinSimilarityScore,
				extractionEvidenceRequired,
				qualityGateProfiles
		);
	}

	private int positiveOrDefault(Integer value, int fallback) {
		if (value == null || value <= 0) {
			return fallback;
		}
		return value;
	}

	private List<String> normalizeDomains(List<String> domains) {
		if (domains == null) {
			return new ArrayList<>();
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

	private String normalizeReasoningEffort(String raw) {
		if (raw == null || raw.isBlank()) {
			return DEFAULT_WEBSEARCH_REASONING_EFFORT;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "low", "medium", "high" -> normalized;
			default -> DEFAULT_WEBSEARCH_REASONING_EFFORT;
		};
	}

	private KnowledgeBaseQualityGateConfigSnapshot loadDefaultQualityGateConfig() {
		KnowledgeBaseQualityGateConfigSnapshot fallback = createDefaultQualityGateConfig();
		if (resourceLoader == null) {
			return fallback;
		}
		Resource resource = resourceLoader.getResource("classpath:kb_quality_gate_profiles.json");
		if (!resource.exists()) {
			return fallback;
		}
		try (InputStream inputStream = resource.getInputStream()) {
			String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			KnowledgeBaseQualityGateConfigDto parsed = parseQualityGateConfig(json);
			KnowledgeBaseQualityGateConfigSnapshot normalized = normalizeQualityGateConfig(parsed, fallback);
			return normalized == null ? fallback : normalized;
		} catch (Exception ex) {
			return fallback;
		}
	}

	private KnowledgeBaseQualityGateConfigDto parseQualityGateConfig(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			JsonNode root = objectMapper.readTree(json);
			return objectMapper.treeToValue(root, KnowledgeBaseQualityGateConfigDto.class);
		} catch (Exception ex) {
			return null;
		}
	}

	private KnowledgeBaseQualityGateConfigSnapshot normalizeQualityGateConfig(KnowledgeBaseQualityGateConfigDto raw) {
		return normalizeQualityGateConfig(raw, defaultQualityGateConfig);
	}

	private KnowledgeBaseQualityGateConfigSnapshot normalizeQualityGateConfig(KnowledgeBaseQualityGateConfigDto raw,
														KnowledgeBaseQualityGateConfigSnapshot defaults) {
		KnowledgeBaseQualityGateConfigSnapshot fallback = defaults == null ? createDefaultQualityGateConfig() : defaults;
		if (raw == null) {
			return fallback;
		}
		String activeProfile = normalizeProfileKey(raw.activeProfile());
		if (activeProfile == null) {
			activeProfile = fallback.activeProfile();
		}
		boolean customOverridesEnabled = raw.customOverridesEnabled() != null && raw.customOverridesEnabled();
		if (!customOverridesEnabled) {
			String layerTargetProfile = resolveLayerTargetProfile();
			if (layerTargetProfile != null) {
				activeProfile = layerTargetProfile;
			}
		}
		Map<String, KnowledgeBaseQualityGateProfileSnapshot> mergedProfiles = new LinkedHashMap<>(fallback.profiles());
		if (raw.profiles() != null) {
			raw.profiles().forEach((key, profileDto) -> {
				String normalizedKey = normalizeProfileKey(key);
				if (normalizedKey == null) {
					return;
				}
				KnowledgeBaseQualityGateProfileSnapshot baseProfile = fallback.profiles().get(normalizedKey);
				KnowledgeBaseQualityGateProfileSnapshot normalized = normalizeQualityGateProfile(normalizedKey, profileDto, baseProfile);
				mergedProfiles.put(normalizedKey, normalized);
			});
		}
		Map<String, KnowledgeBaseQualityGateProfileSnapshot> mergedCustomProfiles = normalizeCustomProfiles(
				raw.customProfiles(), mergedProfiles, fallback
		);
		if (!mergedProfiles.containsKey(activeProfile)) {
			activeProfile = fallback.activeProfile();
		}
		return new KnowledgeBaseQualityGateConfigSnapshot(activeProfile, mergedProfiles, customOverridesEnabled, mergedCustomProfiles);
	}

	private String resolveLayerTargetProfile() {
		if (layerTargetConfigService == null) {
			return null;
		}
		try {
			var effective = layerTargetConfigService.loadEffectiveConfig();
			if (effective == null || effective.selectedProfileKey() == null) {
				return null;
			}
			return normalizeProfileKey(effective.selectedProfileKey());
		} catch (Exception ex) {
			return null;
		}
	}

	private KnowledgeBaseQualityGateProfileSnapshot normalizeQualityGateProfile(String key,
														KnowledgeBaseQualityGateProfileDto raw,
														KnowledgeBaseQualityGateProfileSnapshot defaults) {
		KnowledgeBaseQualityGateProfileSnapshot fallback = defaults == null
				? new KnowledgeBaseQualityGateProfileSnapshot(key, "", Map.of(), Map.of())
				: defaults;
		if (raw == null) {
			return fallback;
		}
		String displayName = raw.displayName() == null || raw.displayName().isBlank()
				? fallback.displayName()
				: raw.displayName().trim();
		String description = raw.description() == null || raw.description().isBlank()
				? fallback.description()
				: raw.description().trim();
		Map<Integer, String> layerProfiles = normalizeLayerProfiles(raw.layerProfiles(), fallback.layerProfiles());
		Map<String, List<String>> evidenceProfiles = normalizeEvidenceProfiles(raw.evidenceProfiles(), fallback.evidenceProfiles());
		return new KnowledgeBaseQualityGateProfileSnapshot(displayName, description, layerProfiles, evidenceProfiles);
	}

	private Map<Integer, String> normalizeLayerProfiles(Map<String, String> raw, Map<Integer, String> defaults) {
		Map<Integer, String> normalized = new LinkedHashMap<>();
		if (defaults != null && !defaults.isEmpty()) {
			normalized.putAll(defaults);
		}
		if (raw != null) {
			raw.forEach((key, value) -> {
				Integer layer = parseLayerKey(key);
				String profileKey = normalizeProfileKey(value);
				if (layer != null && profileKey != null) {
					normalized.put(layer, profileKey);
				}
			});
		}
		return normalized;
	}

	private Map<String, List<String>> normalizeEvidenceProfiles(Map<String, List<String>> raw,
															Map<String, List<String>> defaults) {
		Map<String, List<String>> normalized = new LinkedHashMap<>();
		if (defaults != null && !defaults.isEmpty()) {
			defaults.forEach((key, list) -> normalized.put(key, List.copyOf(list)));
		}
		if (raw != null) {
			raw.forEach((key, list) -> {
				String profileKey = normalizeProfileKey(key);
				List<String> evidenceKeys = normalizeEvidenceKeys(list);
				if (profileKey != null && !evidenceKeys.isEmpty()) {
					normalized.put(profileKey, evidenceKeys);
				}
			});
		}
		return normalized;
	}

	private Map<String, KnowledgeBaseQualityGateProfileSnapshot> normalizeCustomProfiles(
			Map<String, KnowledgeBaseQualityGateProfileDto> raw,
			Map<String, KnowledgeBaseQualityGateProfileSnapshot> baseProfiles,
			KnowledgeBaseQualityGateConfigSnapshot fallback
	) {
		Map<String, KnowledgeBaseQualityGateProfileSnapshot> normalized = new LinkedHashMap<>();
		if (raw == null || raw.isEmpty()) {
			return normalized;
		}
		raw.forEach((key, profileDto) -> {
			String normalizedKey = normalizeProfileKey(key);
			if (normalizedKey == null) {
				return;
			}
			KnowledgeBaseQualityGateProfileSnapshot baseProfile = baseProfiles == null ? null : baseProfiles.get(normalizedKey);
			if (baseProfile == null && fallback != null && fallback.profiles() != null) {
				baseProfile = fallback.profiles().get(normalizedKey);
			}
			KnowledgeBaseQualityGateProfileSnapshot normalizedProfile =
					normalizeQualityGateProfile(normalizedKey, profileDto, baseProfile);
			normalized.put(normalizedKey, normalizedProfile);
		});
		return normalized;
	}

	private Integer parseLayerKey(String key) {
		if (key == null || key.isBlank()) {
			return null;
		}
		try {
			return Integer.parseInt(key.trim());
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private String normalizeProfileKey(String key) {
		if (key == null || key.isBlank()) {
			return null;
		}
		return key.trim().toUpperCase(Locale.ROOT);
	}

	private List<String> normalizeEvidenceKeys(List<String> raw) {
		if (raw == null || raw.isEmpty()) {
			return List.of();
		}
		List<String> normalized = new ArrayList<>();
		for (String key : raw) {
			if (key == null || key.isBlank()) {
				continue;
			}
			String cleaned = key.trim().toLowerCase(Locale.ROOT);
			if (!normalized.contains(cleaned)) {
				normalized.add(cleaned);
			}
		}
		return normalized;
	}

	private KnowledgeBaseQualityGateConfigSnapshot createDefaultQualityGateConfig() {
		Map<Integer, String> layerProfiles = new LinkedHashMap<>();
		layerProfiles.put(1, "FUND");
		layerProfiles.put(2, "FUND");
		layerProfiles.put(3, "FUND");
		layerProfiles.put(4, "EQUITY");
		layerProfiles.put(5, "UNKNOWN");

		Map<String, List<String>> evidenceProfiles = new LinkedHashMap<>();
		evidenceProfiles.put("FUND", List.of(
				"benchmark_index",
				"ongoing_charges_pct",
				"sri",
				"price",
				"pe_current",
				"pb_current",
				"pe_ttm_holdings",
				"earnings_yield_ttm_holdings",
				"holdings_coverage_weight_pct",
				"holdings_coverage_count",
				"holdings_asof"
		));
		evidenceProfiles.put("EQUITY", List.of(
				"price",
				"pe_current",
				"pb_current",
				"dividend_per_share",
				"revenue",
				"net_income",
				"ebitda",
				"eps_history"
		));
		evidenceProfiles.put("REIT", List.of(
				"price",
				"pe_current",
				"pb_current",
				"dividend_per_share",
				"revenue",
				"net_income",
				"ebitda",
				"eps_history",
				"net_rent",
				"noi",
				"affo",
				"ffo"
		));
		evidenceProfiles.put("UNKNOWN", List.of(
				"price",
				"pe_current",
				"pb_current"
		));

		Map<String, KnowledgeBaseQualityGateProfileSnapshot> profiles = new LinkedHashMap<>();
		profiles.put("CLASSIC", new KnowledgeBaseQualityGateProfileSnapshot(
				"Classic",
				"Default quality gates for Classic profiles.",
				layerProfiles,
				evidenceProfiles
		));
		profiles.put("BALANCED", new KnowledgeBaseQualityGateProfileSnapshot(
				"Balanced",
				"Default quality gates for Balanced profiles.",
				layerProfiles,
				evidenceProfiles
		));
		profiles.put("GROWTH", new KnowledgeBaseQualityGateProfileSnapshot(
				"Growth",
				"Default quality gates for Growth profiles.",
				layerProfiles,
				evidenceProfiles
		));
		profiles.put("AGGRESSIVE", new KnowledgeBaseQualityGateProfileSnapshot(
				"Aggressive",
				"Default quality gates for Aggressive profiles.",
				layerProfiles,
				evidenceProfiles
		));
		profiles.put("OPPORTUNITY", new KnowledgeBaseQualityGateProfileSnapshot(
				"Opportunity",
				"Default quality gates for Opportunity profiles.",
				layerProfiles,
				evidenceProfiles
		));
		return new KnowledgeBaseQualityGateConfigSnapshot(DEFAULT_QUALITY_GATE_PROFILE_KEY, profiles, false, Map.of());
	}

	private KnowledgeBaseQualityGateConfigDto toQualityGateDto(KnowledgeBaseQualityGateConfigSnapshot snapshot) {
		if (snapshot == null) {
			return null;
		}
		Map<String, KnowledgeBaseQualityGateProfileDto> profiles = new LinkedHashMap<>();
		if (snapshot.profiles() != null) {
			snapshot.profiles().forEach((key, value) -> profiles.put(key, toQualityGateProfileDto(value)));
		}
		Map<String, KnowledgeBaseQualityGateProfileDto> customProfiles = new LinkedHashMap<>();
		if (snapshot.customProfiles() != null) {
			snapshot.customProfiles().forEach((key, value) -> customProfiles.put(key, toQualityGateProfileDto(value)));
		}
		return new KnowledgeBaseQualityGateConfigDto(
				snapshot.activeProfile(),
				profiles,
				snapshot.customOverridesEnabled(),
				customProfiles
		);
	}

	private KnowledgeBaseQualityGateProfileDto toQualityGateProfileDto(KnowledgeBaseQualityGateProfileSnapshot snapshot) {
		if (snapshot == null) {
			return null;
		}
		Map<String, String> layerProfiles = new LinkedHashMap<>();
		if (snapshot.layerProfiles() != null) {
			snapshot.layerProfiles().forEach((key, value) -> layerProfiles.put(String.valueOf(key), value));
		}
		Map<String, List<String>> evidenceProfiles = new LinkedHashMap<>();
		if (snapshot.evidenceProfiles() != null) {
			snapshot.evidenceProfiles().forEach((key, value) -> evidenceProfiles.put(key, List.copyOf(value)));
		}
		return new KnowledgeBaseQualityGateProfileDto(
				snapshot.displayName(),
				snapshot.description(),
				layerProfiles,
				evidenceProfiles
		);
	}

	private KnowledgeBaseConfigDto toDto(KnowledgeBaseConfigSnapshot snapshot) {
		return new KnowledgeBaseConfigDto(
				snapshot.enabled(),
				snapshot.refreshIntervalDays(),
				snapshot.autoApprove(),
				snapshot.applyExtractionsToOverrides(),
				snapshot.overwriteExistingOverrides(),
				snapshot.batchSizeInstruments(),
				snapshot.batchMaxInputChars(),
				snapshot.maxParallelBulkBatches(),
				snapshot.maxBatchesPerRun(),
				snapshot.pollIntervalSeconds(),
				snapshot.maxInstrumentsPerRun(),
				snapshot.maxRetriesPerInstrument(),
				snapshot.baseBackoffSeconds(),
				snapshot.maxBackoffSeconds(),
				snapshot.dossierMaxChars(),
				snapshot.kbRefreshMinDaysBetweenRunsPerInstrument(),
				snapshot.runTimeoutMinutes(),
				snapshot.websearchReasoningEffort(),
				List.copyOf(snapshot.websearchAllowedDomains()),
				snapshot.bulkMinCitations(),
				snapshot.bulkRequirePrimarySource(),
				snapshot.alternativesMinSimilarityScore(),
				snapshot.extractionEvidenceRequired(),
				toQualityGateDto(snapshot.qualityGateProfiles())
		);
	}

	public record KnowledgeBaseQualityGateProfileSnapshot(
			String displayName,
			String description,
			Map<Integer, String> layerProfiles,
			Map<String, List<String>> evidenceProfiles
	) {
	}

	public record KnowledgeBaseQualityGateConfigSnapshot(
			String activeProfile,
			Map<String, KnowledgeBaseQualityGateProfileSnapshot> profiles,
			boolean customOverridesEnabled,
			Map<String, KnowledgeBaseQualityGateProfileSnapshot> customProfiles
	) {
		public KnowledgeBaseQualityGateProfileSnapshot activeProfileSnapshot() {
			if (activeProfile == null) {
				return null;
			}
			if (customOverridesEnabled && customProfiles != null) {
				KnowledgeBaseQualityGateProfileSnapshot custom = customProfiles.get(activeProfile);
				if (custom != null) {
					return custom;
				}
			}
			return profiles == null ? null : profiles.get(activeProfile);
		}
	}

	public record KnowledgeBaseConfigSnapshot(
			boolean enabled,
			int refreshIntervalDays,
			boolean autoApprove,
			boolean applyExtractionsToOverrides,
			boolean overwriteExistingOverrides,
			int batchSizeInstruments,
			int batchMaxInputChars,
			int maxParallelBulkBatches,
			int maxBatchesPerRun,
			int pollIntervalSeconds,
			int maxInstrumentsPerRun,
			int maxRetriesPerInstrument,
			int baseBackoffSeconds,
			int maxBackoffSeconds,
			int dossierMaxChars,
			int kbRefreshMinDaysBetweenRunsPerInstrument,
			int runTimeoutMinutes,
			String websearchReasoningEffort,
			List<String> websearchAllowedDomains,
			int bulkMinCitations,
			boolean bulkRequirePrimarySource,
			double alternativesMinSimilarityScore,
			boolean extractionEvidenceRequired,
			KnowledgeBaseQualityGateConfigSnapshot qualityGateProfiles
	) {
	}
}
