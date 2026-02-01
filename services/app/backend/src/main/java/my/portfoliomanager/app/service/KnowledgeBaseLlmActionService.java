package my.portfoliomanager.app.service;

import jakarta.annotation.PreDestroy;
import my.portfoliomanager.app.domain.InstrumentDossier;
import my.portfoliomanager.app.domain.DossierExtractionStatus;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionResponseDto;
import my.portfoliomanager.app.dto.KnowledgeBaseAlternativesResponseDto;
import my.portfoliomanager.app.dto.KnowledgeBaseBulkResearchItemDto;
import my.portfoliomanager.app.dto.KnowledgeBaseBulkResearchItemStatus;
import my.portfoliomanager.app.dto.KnowledgeBaseBulkResearchResponseDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionStatus;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionTrigger;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionType;
import my.portfoliomanager.app.dto.KnowledgeBaseManualApprovalItemDto;
import my.portfoliomanager.app.dto.KnowledgeBaseRefreshBatchRequestDto;
import my.portfoliomanager.app.dto.KnowledgeBaseRefreshBatchResponseDto;
import my.portfoliomanager.app.dto.KnowledgeBaseRefreshItemDto;
import my.portfoliomanager.app.dto.KnowledgeBaseRefreshScopeDto;
import my.portfoliomanager.app.repository.InstrumentDossierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

@Service
public class KnowledgeBaseLlmActionService {
	private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseLlmActionService.class);
	private static final Pattern ISIN_RE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");
	private static final Duration JOB_TTL = Duration.ofMinutes(90);
	private static final int MAX_CONCURRENT_ACTIONS = 2;

	private final KnowledgeBaseMaintenanceService maintenanceService;
	private final KnowledgeBaseRefreshService refreshService;
	private final KnowledgeBaseService knowledgeBaseService;
	private final InstrumentDossierRepository dossierRepository;
	private final Map<String, LlmActionState> actions = new ConcurrentHashMap<>();
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Semaphore concurrency = new Semaphore(MAX_CONCURRENT_ACTIONS);

	public KnowledgeBaseLlmActionService(KnowledgeBaseMaintenanceService maintenanceService,
										 KnowledgeBaseRefreshService refreshService,
										 KnowledgeBaseService knowledgeBaseService,
										 InstrumentDossierRepository dossierRepository) {
		this.maintenanceService = maintenanceService;
		this.refreshService = refreshService;
		this.knowledgeBaseService = knowledgeBaseService;
		this.dossierRepository = dossierRepository;
	}

	public List<KnowledgeBaseLlmActionDto> listActions() {
		cleanupExpired();
		return actions.values().stream()
				.sorted(Comparator.comparing(LlmActionState::updatedAt).reversed())
				.map(state -> toDto(state, false))
				.toList();
	}

	public boolean hasRunningType(KnowledgeBaseLlmActionType type) {
		for (LlmActionState state : actions.values()) {
			if (state.status == KnowledgeBaseLlmActionStatus.RUNNING && state.type == type) {
				return true;
			}
		}
		return false;
	}

	public KnowledgeBaseLlmActionDto getAction(String actionId) {
		cleanupExpired();
		LlmActionState state = actions.get(actionId);
		if (state == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LLM action not found");
		}
		return toDto(state, true);
	}

	public KnowledgeBaseLlmActionDto startBulkResearch(List<String> isins,
													   Boolean autoApprove,
													   Boolean applyOverrides,
													   String actor,
													   KnowledgeBaseLlmActionTrigger trigger) {
		List<String> normalized = normalizeIsins(isins, true);
		cleanupExpired();
		Set<String> active = resolveActiveIsins();
		Set<String> blocked = intersect(active, normalized);
		List<String> runnable = normalized.stream()
				.filter(isin -> !blocked.contains(isin))
				.toList();

		Set<String> activeSet = ConcurrentHashMap.newKeySet();
		activeSet.addAll(runnable);
		LlmActionState state = new LlmActionState(UUID.randomUUID().toString(),
				KnowledgeBaseLlmActionType.RESEARCH,
				trigger,
				List.copyOf(normalized),
				activeSet);
		actions.put(state.actionId, state);

		if (runnable.isEmpty()) {
			state.status = KnowledgeBaseLlmActionStatus.DONE;
			KnowledgeBaseBulkResearchResponseDto result = mergeBulkResults(null, blocked);
			state.bulkResearchResult = result;
			state.message = bulkSummary(result);
			state.updatedAt = LocalDateTime.now();
			return toDto(state, true);
		}

		state.message = blocked.isEmpty() ? "Queued bulk research" : "Queued; some ISINs already running";
		state.future = executor.submit(() -> runBulkResearch(state, runnable, blocked, autoApprove, applyOverrides, actor));
		return toDto(state, false);
	}

	public KnowledgeBaseLlmActionDto startAlternatives(String baseIsin,
													   Boolean autoApprove,
													   String actor,
													   KnowledgeBaseLlmActionTrigger trigger) {
		String normalized = normalizeIsin(baseIsin);
		cleanupExpired();
		ensureNotActive(List.of(normalized));
		Set<String> activeSet = ConcurrentHashMap.newKeySet();
		activeSet.add(normalized);
		LlmActionState state = new LlmActionState(UUID.randomUUID().toString(),
				KnowledgeBaseLlmActionType.ALTERNATIVES,
				trigger,
				List.of(normalized),
				activeSet);
		actions.put(state.actionId, state);
		state.message = "Queued alternatives search";
		state.future = executor.submit(() -> runAlternatives(state, normalized, autoApprove, actor));
		return toDto(state, false);
	}

	public KnowledgeBaseLlmActionDto startRefreshBatch(KnowledgeBaseRefreshBatchRequestDto request,
													   String actor,
													   KnowledgeBaseLlmActionTrigger trigger) {
		cleanupExpired();
		LlmActionState state = new LlmActionState(UUID.randomUUID().toString(),
				KnowledgeBaseLlmActionType.REFRESH,
				trigger,
				List.of(),
				ConcurrentHashMap.newKeySet());
		actions.put(state.actionId, state);
		state.message = "Queued refresh batch";
		state.future = executor.submit(() -> runRefreshBatch(state, request, actor));
		return toDto(state, false);
	}

	public KnowledgeBaseLlmActionDto startRefreshSingle(String isin,
														String actor,
														Boolean autoApprove,
														KnowledgeBaseLlmActionTrigger trigger) {
		String normalized = normalizeIsin(isin);
		cleanupExpired();
		ensureNotActive(List.of(normalized));
		Set<String> activeSet = ConcurrentHashMap.newKeySet();
		activeSet.add(normalized);
		LlmActionState state = new LlmActionState(UUID.randomUUID().toString(),
				KnowledgeBaseLlmActionType.REFRESH,
				trigger,
				List.of(normalized),
				activeSet);
		actions.put(state.actionId, state);
		state.message = "Queued refresh";
		state.future = executor.submit(() -> runRefreshSingle(state, normalized, autoApprove, actor));
		return toDto(state, false);
	}

	public KnowledgeBaseLlmActionDto startExtraction(Long dossierId,
													 String actor,
													 KnowledgeBaseLlmActionTrigger trigger) {
		InstrumentDossier dossier = dossierRepository.findById(dossierId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier not found"));
		String isin = dossier.getIsin();
		cleanupExpired();
		ensureNotActive(List.of(isin));
		Set<String> activeSet = ConcurrentHashMap.newKeySet();
		activeSet.add(isin);
		LlmActionState state = new LlmActionState(UUID.randomUUID().toString(),
				KnowledgeBaseLlmActionType.EXTRACTION,
				trigger,
				List.of(isin),
				activeSet);
		actions.put(state.actionId, state);
		state.message = "Queued extraction";
		state.future = executor.submit(() -> runExtraction(state, dossierId, actor));
		return toDto(state, false);
	}

	public KnowledgeBaseLlmActionDto cancel(String actionId) {
		LlmActionState state = actions.get(actionId);
		if (state == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LLM action not found");
		}
		if (state.status != KnowledgeBaseLlmActionStatus.RUNNING) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Only running actions can be canceled");
		}
		state.message = "Cancel requested";
		state.updatedAt = LocalDateTime.now();
		if (state.future != null) {
			state.future.cancel(true);
		}
		return toDto(state, false);
	}

	public void dismiss(String actionId) {
		LlmActionState state = actions.get(actionId);
		if (state == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LLM action not found");
		}
		if (state.status == KnowledgeBaseLlmActionStatus.RUNNING) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Running actions cannot be dismissed");
		}
		actions.remove(actionId);
	}

	@PreDestroy
	public void shutdown() {
		executor.shutdownNow();
	}

	private void runBulkResearch(LlmActionState state,
								 List<String> runnable,
								 Set<String> blocked,
								 Boolean autoApprove,
								 Boolean applyOverrides,
								 String actor) {
		if (!acquireSlot(state)) {
			return;
		}
		try {
			state.status = KnowledgeBaseLlmActionStatus.RUNNING;
			state.message = "Running bulk research";
			state.updatedAt = LocalDateTime.now();
			KnowledgeBaseBulkResearchResponseDto result =
					maintenanceService.bulkResearch(runnable, autoApprove, applyOverrides, actor);
			KnowledgeBaseBulkResearchResponseDto merged = mergeBulkResults(result, blocked);
			state.bulkResearchResult = merged;
			state.status = KnowledgeBaseLlmActionStatus.DONE;
			state.message = bulkSummary(merged);
		} catch (CancellationException ex) {
			state.status = KnowledgeBaseLlmActionStatus.CANCELED;
			state.message = "Canceled";
		} catch (Exception ex) {
			state.status = KnowledgeBaseLlmActionStatus.FAILED;
			state.message = failWithReference(state, ex);
		} finally {
			state.updatedAt = LocalDateTime.now();
			concurrency.release();
		}
	}

	private void runAlternatives(LlmActionState state,
								 String baseIsin,
								 Boolean autoApprove,
								 String actor) {
		if (!acquireSlot(state)) {
			return;
		}
		try {
			state.status = KnowledgeBaseLlmActionStatus.RUNNING;
			state.message = "Running alternatives search";
			state.updatedAt = LocalDateTime.now();
			Set<String> blocked = resolveActiveIsins();
			blocked.remove(baseIsin);
			KnowledgeBaseAlternativesResponseDto result =
					maintenanceService.findAlternatives(baseIsin, autoApprove, actor, blocked);
			state.alternativesResult = result;
			Set<String> allIsins = new LinkedHashSet<>();
			allIsins.add(baseIsin);
			if (result.alternatives() != null) {
				for (var item : result.alternatives()) {
					if (item != null && item.isin() != null) {
						allIsins.add(item.isin());
					}
				}
			}
			state.isins = List.copyOf(allIsins);
			state.status = KnowledgeBaseLlmActionStatus.DONE;
			state.message = "Alternatives: " + (result.alternatives() == null ? 0 : result.alternatives().size());
		} catch (CancellationException ex) {
			state.status = KnowledgeBaseLlmActionStatus.CANCELED;
			state.message = "Canceled";
		} catch (Exception ex) {
			state.status = KnowledgeBaseLlmActionStatus.FAILED;
			state.message = failWithReference(state, ex);
		} finally {
			state.updatedAt = LocalDateTime.now();
			concurrency.release();
		}
	}

	private void runRefreshBatch(LlmActionState state,
								 KnowledgeBaseRefreshBatchRequestDto request,
								 String actor) {
		if (!acquireSlot(state)) {
			return;
		}
		try {
			state.status = KnowledgeBaseLlmActionStatus.RUNNING;
			state.message = "Running refresh batch";
			state.updatedAt = LocalDateTime.now();
			List<String> candidates = refreshService.previewCandidates(request);
			Set<String> blocked = resolveActiveIsins();
			state.isins = List.copyOf(candidates);
			state.activeIsins.clear();
			for (String isin : candidates) {
				if (!blocked.contains(isin)) {
					state.activeIsins.add(isin);
				}
			}
			KnowledgeBaseRefreshBatchRequestDto scopedRequest = withScope(request, candidates);
			KnowledgeBaseRefreshBatchResponseDto result = refreshService.refreshBatch(scopedRequest, actor, blocked);
			state.refreshBatchResult = result;
			state.status = KnowledgeBaseLlmActionStatus.DONE;
			state.message = refreshSummary(result);
		} catch (CancellationException ex) {
			state.status = KnowledgeBaseLlmActionStatus.CANCELED;
			state.message = "Canceled";
		} catch (Exception ex) {
			state.status = KnowledgeBaseLlmActionStatus.FAILED;
			state.message = failWithReference(state, ex);
		} finally {
			state.updatedAt = LocalDateTime.now();
			concurrency.release();
		}
	}

	private void runRefreshSingle(LlmActionState state,
								  String isin,
								  Boolean autoApprove,
								  String actor) {
		if (!acquireSlot(state)) {
			return;
		}
		try {
			state.status = KnowledgeBaseLlmActionStatus.RUNNING;
			state.message = "Running refresh";
			state.updatedAt = LocalDateTime.now();
			KnowledgeBaseRefreshItemDto result = refreshService.refreshSingle(isin, autoApprove, actor, Set.of());
			state.refreshItemResult = result;
			state.status = KnowledgeBaseLlmActionStatus.DONE;
			state.message = "Refresh " + (result.status() == null ? "done" : result.status().name().toLowerCase(Locale.ROOT));
		} catch (CancellationException ex) {
			state.status = KnowledgeBaseLlmActionStatus.CANCELED;
			state.message = "Canceled";
		} catch (Exception ex) {
			state.status = KnowledgeBaseLlmActionStatus.FAILED;
			state.message = failWithReference(state, ex);
		} finally {
			state.updatedAt = LocalDateTime.now();
			concurrency.release();
		}
	}

	private void runExtraction(LlmActionState state, Long dossierId, String actor) {
		if (!acquireSlot(state)) {
			return;
		}
		try {
			state.status = KnowledgeBaseLlmActionStatus.RUNNING;
			state.message = "Running extraction";
			state.updatedAt = LocalDateTime.now();
			InstrumentDossierExtractionResponseDto extraction = knowledgeBaseService.runExtraction(dossierId);
			state.extractionResult = extraction;
			if (extraction.status() == null) {
				state.status = KnowledgeBaseLlmActionStatus.DONE;
				state.message = "Extraction completed";
			} else if (extraction.status() == DossierExtractionStatus.FAILED) {
				state.status = KnowledgeBaseLlmActionStatus.FAILED;
				state.message = failWithReference(state, extraction.error());
			} else {
				state.status = KnowledgeBaseLlmActionStatus.DONE;
				state.message = "Extraction completed";
			}
		} catch (CancellationException ex) {
			state.status = KnowledgeBaseLlmActionStatus.CANCELED;
			state.message = "Canceled";
		} catch (Exception ex) {
			state.status = KnowledgeBaseLlmActionStatus.FAILED;
			state.message = failWithReference(state, ex);
		} finally {
			state.updatedAt = LocalDateTime.now();
			concurrency.release();
		}
	}

	private boolean acquireSlot(LlmActionState state) {
		try {
			concurrency.acquire();
			return true;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			state.status = KnowledgeBaseLlmActionStatus.FAILED;
			state.message = failWithReference(state, ex);
			state.updatedAt = LocalDateTime.now();
			return false;
		}
	}

	private void ensureNotActive(List<String> isins) {
		Set<String> active = resolveActiveIsins();
		Set<String> blocked = intersect(active, isins);
		if (!blocked.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "LLM action already running for " + String.join(", ", blocked));
		}
	}

	private Set<String> resolveActiveIsins() {
		Set<String> active = new HashSet<>();
		for (LlmActionState state : actions.values()) {
			if (state.status == KnowledgeBaseLlmActionStatus.RUNNING) {
				active.addAll(state.activeIsins);
			}
		}
		return active;
	}

	private Set<String> intersect(Set<String> active, List<String> isins) {
		Set<String> blocked = new HashSet<>();
		for (String isin : isins) {
			if (active.contains(isin)) {
				blocked.add(isin);
			}
		}
		return blocked;
	}

	private List<String> normalizeIsins(List<String> values, boolean required) {
		if (values == null || values.isEmpty()) {
			if (required) {
				throw new IllegalArgumentException("At least one ISIN is required");
			}
			return List.of();
		}
		Set<String> seen = new HashSet<>();
		List<String> normalized = new ArrayList<>();
		for (String raw : values) {
			String isin = normalizeIsin(raw);
			if (seen.add(isin)) {
				normalized.add(isin);
			}
		}
		if (required && normalized.isEmpty()) {
			throw new IllegalArgumentException("At least one ISIN is required");
		}
		return normalized;
	}

	private String normalizeIsin(String value) {
		String trimmed = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
		if (!ISIN_RE.matcher(trimmed).matches()) {
			throw new IllegalArgumentException("Invalid ISIN: " + trimmed);
		}
		return trimmed;
	}

	private KnowledgeBaseBulkResearchResponseDto mergeBulkResults(KnowledgeBaseBulkResearchResponseDto base,
																  Set<String> blocked) {
		List<KnowledgeBaseBulkResearchItemDto> items = new ArrayList<>();
		if (base != null && base.items() != null) {
			items.addAll(base.items());
		}
		if (blocked != null && !blocked.isEmpty()) {
			for (String isin : blocked) {
				items.add(new KnowledgeBaseBulkResearchItemDto(
						isin,
						KnowledgeBaseBulkResearchItemStatus.SKIPPED,
						null,
						null,
						"already_running",
						null
				));
			}
		}
		int succeeded = 0;
		int skipped = 0;
		int failed = 0;
		for (KnowledgeBaseBulkResearchItemDto item : items) {
			if (item == null || item.status() == null) {
				continue;
			}
			switch (item.status()) {
				case SUCCEEDED -> succeeded++;
				case FAILED -> failed++;
				case SKIPPED -> skipped++;
			}
		}
		int total = items.size();
		return new KnowledgeBaseBulkResearchResponseDto(total, succeeded, skipped, failed, List.copyOf(items));
	}

	private String bulkSummary(KnowledgeBaseBulkResearchResponseDto result) {
		if (result == null) {
			return "Bulk research completed";
		}
		return "Total " + result.total()
				+ " | Succeeded " + result.succeeded()
				+ " | Skipped " + result.skipped()
				+ " | Failed " + result.failed();
	}

	private String refreshSummary(KnowledgeBaseRefreshBatchResponseDto result) {
		if (result == null) {
			return "Refresh completed";
		}
		return "Processed " + result.processed()
				+ " of " + result.totalCandidates()
				+ " | Succeeded " + result.succeeded()
				+ " | Skipped " + result.skipped()
				+ " | Failed " + result.failed();
	}

	private KnowledgeBaseLlmActionDto toDto(LlmActionState state, boolean includeResults) {
		List<KnowledgeBaseManualApprovalItemDto> manualApprovals = knowledgeBaseService.resolveManualApprovals(state.isins);
		return new KnowledgeBaseLlmActionDto(
				state.actionId,
				state.type,
				state.status,
				state.trigger,
				state.isins,
				state.createdAt,
				state.updatedAt,
				state.message,
				manualApprovals,
				includeResults ? state.bulkResearchResult : null,
				includeResults ? state.alternativesResult : null,
				includeResults ? state.refreshBatchResult : null,
				includeResults ? state.refreshItemResult : null,
				includeResults ? state.extractionResult : null
		);
	}

	private void cleanupExpired() {
		LocalDateTime now = LocalDateTime.now();
		actions.entrySet().removeIf(entry -> {
			LlmActionState state = entry.getValue();
			if (state == null || state.status == KnowledgeBaseLlmActionStatus.RUNNING) {
				return false;
			}
			return state.updatedAt.plus(JOB_TTL).isBefore(now);
		});
	}

	private String failWithReference(LlmActionState state, Exception ex) {
		String message = ex == null ? null : ex.getMessage();
		return failWithReference(state, message, ex);
	}

	private String failWithReference(LlmActionState state, String error) {
		return failWithReference(state, error, null);
	}

	private String failWithReference(LlmActionState state, String error, Exception ex) {
		String reference = errorReference();
		if (ex != null) {
			logger.error("KB LLM action failed (ref={}, actionId={}, type={}, isins={}, error={})",
					reference, state.actionId, state.type, state.isins, error, ex);
		} else {
			logger.error("KB LLM action failed (ref={}, actionId={}, type={}, isins={}, error={})",
					reference, state.actionId, state.type, state.isins, error);
		}
		return "Error ref " + reference;
	}

	private String errorReference() {
		return "KB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
	}

	private KnowledgeBaseRefreshBatchRequestDto withScope(KnowledgeBaseRefreshBatchRequestDto request, List<String> isins) {
		Integer limit = request == null ? null : request.limit();
		Integer batchSize = request == null ? null : request.batchSize();
		Boolean dryRun = request == null ? null : request.dryRun();
		KnowledgeBaseRefreshScopeDto scope = new KnowledgeBaseRefreshScopeDto(isins);
		return new KnowledgeBaseRefreshBatchRequestDto(limit, batchSize, dryRun, scope);
	}

	private static final class LlmActionState {
		private final String actionId;
		private final KnowledgeBaseLlmActionType type;
		private final KnowledgeBaseLlmActionTrigger trigger;
		private volatile List<String> isins;
		private final Set<String> activeIsins;
		private final LocalDateTime createdAt;
		private volatile LocalDateTime updatedAt;
		private volatile KnowledgeBaseLlmActionStatus status;
		private volatile String message;
		private volatile KnowledgeBaseBulkResearchResponseDto bulkResearchResult;
		private volatile KnowledgeBaseAlternativesResponseDto alternativesResult;
		private volatile KnowledgeBaseRefreshBatchResponseDto refreshBatchResult;
		private volatile KnowledgeBaseRefreshItemDto refreshItemResult;
		private volatile InstrumentDossierExtractionResponseDto extractionResult;
		private volatile Future<?> future;

		private LlmActionState(String actionId,
							   KnowledgeBaseLlmActionType type,
							   KnowledgeBaseLlmActionTrigger trigger,
							   List<String> isins,
							   Set<String> activeIsins) {
			this.actionId = actionId;
			this.type = type;
			this.trigger = trigger;
			this.isins = isins;
			this.activeIsins = activeIsins;
			this.createdAt = LocalDateTime.now();
			this.updatedAt = this.createdAt;
			this.status = KnowledgeBaseLlmActionStatus.RUNNING;
		}

		private LocalDateTime updatedAt() {
			return updatedAt == null ? createdAt : updatedAt;
		}
	}
}
