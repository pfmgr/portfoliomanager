package my.portfoliomanager.app.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.domain.KnowledgeBaseConfig;
import my.portfoliomanager.app.dto.KnowledgeBaseConfigDto;
import my.portfoliomanager.app.llm.OpenAiLlmClient;
import my.portfoliomanager.app.repository.KnowledgeBaseConfigRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

	private final KnowledgeBaseConfigRepository repository;
	private final ObjectMapper objectMapper;

	public KnowledgeBaseConfigService(KnowledgeBaseConfigRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
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
				allowedDomains
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
				List.copyOf(snapshot.websearchAllowedDomains())
		);
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
			List<String> websearchAllowedDomains
	) {
	}
}
