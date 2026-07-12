package my.portfoliomanager.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import my.portfoliomanager.app.domain.DossierExtractionStatus;
import my.portfoliomanager.app.domain.InstrumentDossier;
import my.portfoliomanager.app.domain.InstrumentDossierExtraction;
import my.portfoliomanager.app.domain.KnowledgeBaseAlternative;
import my.portfoliomanager.app.domain.KnowledgeBaseAlternativeStatus;
import my.portfoliomanager.app.domain.KnowledgeBaseRun;
import my.portfoliomanager.app.domain.KnowledgeBaseRunAction;
import my.portfoliomanager.app.domain.KnowledgeBaseRunStatus;
import my.portfoliomanager.app.dto.*;
import my.portfoliomanager.app.llm.LlmRequestException;
import my.portfoliomanager.app.repository.InstrumentDossierRepository;
import my.portfoliomanager.app.repository.InstrumentDossierExtractionRepository;
import my.portfoliomanager.app.repository.KnowledgeBaseAlternativeRepository;
import my.portfoliomanager.app.dto.InstrumentDossierWebsearchResponseDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Durable facade for the old LLM-action API.  The executor only wakes runs up;
 * kb_runs is the source of truth. Input is immutable; retained output is bounded separately.
 */
@Service
public class KnowledgeBaseLlmActionService {
    private static final Pattern ISIN_RE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");
    private static final Duration LEASE = Duration.ofMinutes(10);
    private static final int MAX_PAYLOAD = 60_000;
    private static final int MAX_FACTS = 12;
    private static final int MAX_FACT_VALUE = 8_000;
    private static final tools.jackson.databind.json.JsonMapper TOOLS_JSON = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults().build();
    private static final Duration RETRY_WAKEUP_INTERVAL = Duration.ofSeconds(1);
    private final KnowledgeBaseMaintenanceService maintenanceService;
    private final KnowledgeBaseRefreshService refreshService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final InstrumentDossierRepository dossierRepository;
    private final InstrumentDossierExtractionRepository extractionRepository;
    private final KnowledgeBaseAlternativeRepository alternativeRepository;
    private final KnowledgeBaseRunService runService;
    private final KnowledgeBaseConfigService configService;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    /* Keeps a valid lease while an opaque provider/domain call exceeds a boundary heartbeat. */
    private final ScheduledExecutorService leaseHeartbeats = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService retryDispatcher = Executors.newSingleThreadScheduledExecutor();
    /* Optimization only: losing this set simulates a process restart safely. */
    private final Set<Long> submitted = ConcurrentHashMap.newKeySet();

    public KnowledgeBaseLlmActionService(KnowledgeBaseMaintenanceService maintenanceService,
            KnowledgeBaseRefreshService refreshService, KnowledgeBaseService knowledgeBaseService,
            InstrumentDossierRepository dossierRepository,
            InstrumentDossierExtractionRepository extractionRepository,
            KnowledgeBaseAlternativeRepository alternativeRepository,
            KnowledgeBaseRunService runService, KnowledgeBaseConfigService configService) {
        this.maintenanceService = maintenanceService;
        this.refreshService = refreshService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.dossierRepository = dossierRepository;
        this.extractionRepository = extractionRepository;
        this.alternativeRepository = alternativeRepository;
        this.runService = runService;
        this.configService = configService;
    }

    public List<KnowledgeBaseLlmActionDto> listActions() {
        recoverAndDispatch();
        return runService.findActionRuns().stream().filter(r -> r.getParentRun() == null).map(r -> toDto(r, false)).toList();
    }

    /** The database, rather than the in-memory submitted set, reconstructs work after restart. */
    @PostConstruct
    void recoverDurableActionsOnStartup() {
        retryDispatcher.scheduleWithFixedDelay(() -> {
            try { dispatchDueRetries(LocalDateTime.now()); } catch (RuntimeException ignored) { /* next bounded wake-up retries */ }
        }, 0, RETRY_WAKEUP_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        recoverAndDispatch();
    }

    /** Bounded DB-driven wake-up: every instance may submit, but only the lease claimant executes. */
    void dispatchDueRetries(LocalDateTime now) {
        runService.dueWaitingRetryRunIds(now).forEach(this::dispatch);
    }

    public boolean hasRunningType(KnowledgeBaseLlmActionType type) {
        return runService.findActionRuns().stream().anyMatch(r -> typeOf(r) == type && r.getStatus().isActive());
    }

    public KnowledgeBaseLlmActionDto getAction(String actionId) {
        recoverAndDispatch();
        return toDto(requireRun(actionId), true);
    }
    public InstrumentDossierWebsearchResponseDto legacyWebsearchResult(String actionId) {
        return convert(outputPayload(requireRun(actionId)).path("result"), InstrumentDossierWebsearchResponseDto.class);
    }

    public KnowledgeBaseLlmActionDto startBulkResearch(List<String> isins, Boolean autoApprove, Boolean applyOverrides,
            String actor, KnowledgeBaseLlmActionTrigger trigger) {
        return startBulkResearch(isins, autoApprove, applyOverrides, actor, trigger, null);
    }
    private KnowledgeBaseLlmActionDto startBulkResearch(List<String> isins, Boolean autoApprove, Boolean applyOverrides,
            String actor, KnowledgeBaseLlmActionTrigger trigger, String callerKey) {
        List<String> normalized = normalizeIsins(isins, true).stream().sorted().toList();
        ObjectNode payload = payload(KnowledgeBaseLlmActionType.RESEARCH, trigger, normalized, actor, "Queued bulk research");
        payload.put("autoApprove", Boolean.TRUE.equals(autoApprove)); payload.put("applyOverrides", Boolean.TRUE.equals(applyOverrides));
        Map<String, String> children = new LinkedHashMap<>();
        for (String isin : normalized) {
            ObjectNode child = payload(KnowledgeBaseLlmActionType.RESEARCH, trigger, List.of(isin), actor, "Queued bulk research item");
            child.put("bulkChild", true); child.put("autoApprove", Boolean.TRUE.equals(autoApprove)); child.put("applyOverrides", Boolean.TRUE.equals(applyOverrides));
            children.put(isin, encode(child, null));
        }
        KnowledgeBaseRun parent = runService.enqueueBulkAction(encode(payload, null), callerKey == null ? idempotencyKey(KnowledgeBaseRunAction.BULK_CREATE, payload) : callerKey, children, KnowledgeBaseRunAction.BULK_CREATE);
        dispatch(parent.getRunId());
        return toDto(parent, false);
    }

    public KnowledgeBaseLlmActionDto create(KnowledgeBaseLlmActionCreateRequestDto request, String callerIdempotencyKey) {
        if (request == null || request.type() == null) throw new IllegalArgumentException("Action type is required");
        String key = scopedCallerKey(request.actor(), callerIdempotencyKey);
        return switch (request.type()) {
            case RESEARCH -> startBulkResearch(request.isins(), request.autoApprove(), request.applyOverrides(), request.actor(), request.trigger(), key);
            case ALTERNATIVES -> startAlternatives(oneIsin(request), request.autoApprove(), request.actor(), request.trigger(), key);
            case REFRESH -> request.refreshRequest() != null ? startRefreshBatch(request.refreshRequest(), request.actor(), request.trigger(), key) : startRefreshSingle(oneIsin(request), request.actor(), request.autoApprove(), request.force(), request.trigger(), key);
            case EXTRACTION -> startDossier(requiredDossierId(request), request.actor(), request.trigger(), KnowledgeBaseLlmActionType.EXTRACTION, KnowledgeBaseRunAction.EXTRACT, "Queued extraction", key);
            case MISSING_METRICS -> startDossier(requiredDossierId(request), request.actor(), request.trigger(), KnowledgeBaseLlmActionType.MISSING_METRICS, KnowledgeBaseRunAction.MISSING_DATA, "Queued missing metrics completion", key);
        };
    }

    public KnowledgeBaseLlmActionDto create(KnowledgeBaseLlmActionCreateRequestDto request) { return create(request, request == null ? null : request.idempotencyKey()); }

    public KnowledgeBaseLlmActionDto startAlternatives(String isin, Boolean autoApprove, String actor, KnowledgeBaseLlmActionTrigger trigger) {
        return startAlternatives(isin, autoApprove, actor, trigger, null);
    }
    private KnowledgeBaseLlmActionDto startAlternatives(String isin, Boolean autoApprove, String actor, KnowledgeBaseLlmActionTrigger trigger, String key) {
        String value = normalizeIsin(isin);
        ObjectNode p = payload(KnowledgeBaseLlmActionType.ALTERNATIVES, trigger, List.of(value), actor, "Queued alternatives search"); p.put("autoApprove", Boolean.TRUE.equals(autoApprove));
        return start(value, KnowledgeBaseRunAction.ALTERNATIVES, p, key);
    }

    public KnowledgeBaseLlmActionDto startRefreshBatch(KnowledgeBaseRefreshBatchRequestDto request, String actor, KnowledgeBaseLlmActionTrigger trigger) {
        return startRefreshBatch(request, actor, trigger, null);
    }
    private KnowledgeBaseLlmActionDto startRefreshBatch(KnowledgeBaseRefreshBatchRequestDto request, String actor, KnowledgeBaseLlmActionTrigger trigger, String key) {
        ObjectNode p = payload(KnowledgeBaseLlmActionType.REFRESH, trigger, List.of(), actor, "Queued refresh batch"); p.set("request", mapper.valueToTree(request));
        return start(null, KnowledgeBaseRunAction.REFRESH, p, key);
    }

    public KnowledgeBaseLlmActionDto startRefreshSingle(String isin, String actor, Boolean autoApprove, Boolean force, KnowledgeBaseLlmActionTrigger trigger) {
        return startRefreshSingle(isin, actor, autoApprove, force, trigger, null);
    }
    private KnowledgeBaseLlmActionDto startRefreshSingle(String isin, String actor, Boolean autoApprove, Boolean force, KnowledgeBaseLlmActionTrigger trigger, String key) {
        String value = normalizeIsin(isin);
        ObjectNode p = payload(KnowledgeBaseLlmActionType.REFRESH, trigger, List.of(value), actor, "Queued refresh"); p.put("autoApprove", Boolean.TRUE.equals(autoApprove)); p.put("force", Boolean.TRUE.equals(force));
        return start(value, KnowledgeBaseRunAction.REFRESH, p, key);
    }

    public KnowledgeBaseLlmActionDto startExtraction(Long dossierId, String actor, KnowledgeBaseLlmActionTrigger trigger) {
        return startDossier(dossierId, actor, trigger, KnowledgeBaseLlmActionType.EXTRACTION, KnowledgeBaseRunAction.EXTRACT, "Queued extraction", null);
    }

    public KnowledgeBaseLlmActionDto startMissingMetrics(Long dossierId, String actor, KnowledgeBaseLlmActionTrigger trigger) {
        return startDossier(dossierId, actor, trigger, KnowledgeBaseLlmActionType.MISSING_METRICS, KnowledgeBaseRunAction.MISSING_DATA, "Queued missing metrics completion", null);
    }

    /** Compatibility entry point. Legacy jobId is the canonical persisted action id. */
    public KnowledgeBaseLlmActionDto startLegacyWebsearch(String isin, String actor) {
        String value = normalizeIsin(isin);
        ObjectNode p = payload(KnowledgeBaseLlmActionType.RESEARCH, KnowledgeBaseLlmActionTrigger.USER, List.of(value), actor, "Queued legacy websearch");
        p.put("legacyWebsearch", true);
        return start(value, KnowledgeBaseRunAction.BULK_CREATE, p);
    }

    public KnowledgeBaseLlmActionDto cancel(String actionId) {
        KnowledgeBaseRun run = requireRun(actionId);
        if (!run.getStatus().isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only active actions can be canceled");
        return runService.requestCancellation(run, LocalDateTime.now()).map(r -> toDto(r, false))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Action cannot be canceled"));
    }

    /** Dismiss remains a compatibility operation: durable audit records are not deleted. */
    public void dismiss(String actionId) {
        KnowledgeBaseRun run = requireRun(actionId);
        if (run.getStatus().isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Running actions cannot be dismissed");
    }

    public void recoverAndDispatch() {
        runService.recoverExpiredLeases(LocalDateTime.now());
        List<KnowledgeBaseRun> active = runService.findActionRuns().stream().filter(r -> r.getStatus().isActive()).toList();
        // Children are independently redispatched: a valid parent lease must never hide due child work.
        active.forEach(r -> { if (r.getParentRun() != null) dispatch(r.getRunId()); });
        active.forEach(r -> { if (r.getParentRun() == null) dispatch(r.getRunId()); });
    }

    private KnowledgeBaseLlmActionDto start(String isin, KnowledgeBaseRunAction action, ObjectNode payload) { return start(isin, action, payload, null); }
    private KnowledgeBaseLlmActionDto start(String isin, KnowledgeBaseRunAction action, ObjectNode payload, String callerKey) {
        KnowledgeBaseRun run = create(isin, action, payload, callerKey); dispatch(run.getRunId()); return toDto(run, false);
    }
    private KnowledgeBaseRun create(String isin, KnowledgeBaseRunAction action, ObjectNode payload, String callerKey) {
        return runService.enqueueActionRun(isin, action, encode(payload, null), null, callerKey == null ? idempotencyKey(action, payload) : callerKey);
    }
    private KnowledgeBaseLlmActionDto startDossier(Long id, String actor, KnowledgeBaseLlmActionTrigger trigger,
            KnowledgeBaseLlmActionType type, KnowledgeBaseRunAction action, String message, String key) {
        InstrumentDossier dossier = dossierRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier not found"));
        ObjectNode p = payload(type, trigger, List.of(dossier.getIsin()), actor, message); p.put("dossierId", id);
        return start(dossier.getIsin(), action, p, key);
    }
    private void dispatch(Long id) {
        if (id != null && submitted.add(id)) executor.submit(() -> execute(id));
    }
    private void execute(Long id) {
        try {
            KnowledgeBaseRun pending = runService.findById(id).orElse(null);
            if (pending == null || !pending.getStatus().isActive()) return;
            ObjectNode input = objectPayload(pending);
            Optional<KnowledgeBaseRun> claimed = runService.claimRun(id, LEASE, LocalDateTime.now());
            if (claimed.isEmpty()) return;
            KnowledgeBaseRun run = claimed.get(); ObjectNode p = objectPayload(run);
            String invalid = invalidInput(run, p);
            if (invalid != null) { runService.completeAction(run, KnowledgeBaseRunStatus.FAILED, encode(p, output("Action failed", null)), invalid, LocalDateTime.now()); return; }
            if (run.getCancelRequestedAt() != null) { runService.markCanceled(run, LocalDateTime.now()); return; }
            run = runService.incrementAttempt(run);
            if (checkpointCanceled(run)) return;
            if (runService.heartbeat(run, LEASE, LocalDateTime.now()).isEmpty()) return;
            if (runService.updateCurrentStep(run, "DISPATCHING", LocalDateTime.now()).isEmpty()) return;
            if (run.getParentRun() == null && typeOf(run) == KnowledgeBaseLlmActionType.RESEARCH && !p.path("legacyWebsearch").asBoolean()) { dispatchBulkChildren(run); reconcileBulkParent(run); return; }
            KnowledgeBaseRun leasedRun = run;
            AtomicBoolean ownershipLost = new AtomicBoolean(false);
            ScheduledFuture<?> heartbeat = leaseHeartbeats.scheduleAtFixedRate(
                    () -> { if (runService.heartbeat(leasedRun, LEASE, LocalDateTime.now()).isEmpty()) ownershipLost.set(true); }, 1, 1, TimeUnit.MINUTES);
            try { executeDomain(leasedRun, p, ownershipLost); } finally { heartbeat.cancel(false); }
        } finally { submitted.remove(id); }
    }
    private void executeDomain(KnowledgeBaseRun run, ObjectNode p, AtomicBoolean ownershipLost) {
        try {
            if (checkpoint(run, ownershipLost)) return;
            if (typeOf(run) == KnowledgeBaseLlmActionType.RESEARCH && p.path("legacyWebsearch").asBoolean()) {
                executeLegacyResearchWorkflow(run, p, ownershipLost);
                return;
            }
            if (runService.updateCurrentStep(run, "PROVIDER_OR_DOMAIN_CALL", LocalDateTime.now()).isEmpty()) return;
            String actor = text(p, "actor"); JsonNode result;
            switch (typeOf(run)) {
                case EXTRACTION -> result = mapper.valueToTree(knowledgeBaseService.runExtraction(p.path("dossierId").asLong(), run));
                case REFRESH -> result = run.getIsin() == null
                        ? mapper.valueToTree(refreshService.refreshBatch(mapper.treeToValue(p.path("request"), KnowledgeBaseRefreshBatchRequestDto.class), actor, Set.of()))
                        : mapper.valueToTree(refreshService.refreshSingle(run.getIsin(), p.path("autoApprove").asBoolean(), p.path("force").asBoolean(), actor, Set.of()));
                case MISSING_METRICS -> result = mapper.valueToTree(knowledgeBaseService.completeMissingMetrics(p.path("dossierId").asLong(), actor, run));
                case ALTERNATIVES -> result = mapper.valueToTree(maintenanceService.findAlternatives(run.getIsin(), p.path("autoApprove").asBoolean(), actor, Set.of()));
                case RESEARCH -> result = mapper.valueToTree(maintenanceService.bulkResearch(isins(p), p.path("autoApprove").asBoolean(), p.path("applyOverrides").asBoolean(), actor));
                default -> throw new IllegalStateException("Unsupported durable action");
            }
            if (ownershipLost.get() || runService.heartbeat(run, LEASE, LocalDateTime.now()).isEmpty()) return;
            if (checkpoint(run, ownershipLost)) return;
            if (runService.updateCurrentStep(run, "PERSISTING_RESULT", LocalDateTime.now()).isEmpty()) return;
            ObjectNode output = output("Completed", result);
            if (checkpoint(run, ownershipLost)) return;
            runService.completeAction(run, KnowledgeBaseRunStatus.COMPLETED, encode(p, output), null, LocalDateTime.now());
            if (run.getParentRun() != null) reconcileBulkParent(run.getParentRun());
        } catch (Exception ex) {
            if (checkpoint(run, ownershipLost)) return;
            if (typeOf(run) == KnowledgeBaseLlmActionType.RESEARCH && p.path("legacyWebsearch").asBoolean()
                    && String.valueOf(ex.getMessage()).contains("result name mismatch")) {
                review(run, p, "IDENTITY_RESOLUTION", "IDENTITY_NAME_MISMATCH",
                        "The discovered name does not match the resolved ISIN identity", mapper.createObjectNode());
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            FailureClassification failure = classify(ex);
            if (failure.retryable() && run.getAttempts() <= maxRetries()) {
                runService.markWaitingRetry(run, nextRetryAt(now, failure), failure.code(), "Action retry scheduled", failure.requestId(), now);
            } else {
                String code = failure.retryable() ? "RETRY_EXHAUSTED" : failure.code();
                runService.completeAction(run, KnowledgeBaseRunStatus.FAILED, encode(p, output("Action failed", null)), "Action failed", code, failure.requestId(), now);
            }
            if (run.getParentRun() != null) reconcileBulkParent(run.getParentRun());
        }
    }

    /**
     * A deliberately finite research workflow, not an autonomous agent. Every transition is
     * persisted in kb_runs and composition is blocked unless source verification produced facts.
     */
    private void executeLegacyResearchWorkflow(KnowledgeBaseRun run, ObjectNode input, AtomicBoolean ownershipLost) {
        String isin = run.getIsin();
        if (!progress(run, input, "IDENTITY_RESOLUTION", mapper.createObjectNode().put("isin", isin))) return;
        if (checkpoint(run, ownershipLost)) return;

        if (!progress(run, input, "SOURCE_DISCOVERY", mapper.createObjectNode().put("query", safe(isin + " official factsheet")))) return;
        InstrumentDossierWebsearchResponseDto discovered = knowledgeBaseService.createDossierDraftViaWebsearch(isin);
        if (ownershipLost.get() || checkpoint(run, ownershipLost)) return;

        String displayName = safe(discovered.displayName());
        if (displayName == null || displayName.isBlank()) {
            review(run, input, "IDENTITY_RESOLUTION", "IDENTITY_NAME_MISMATCH", "The ISIN could not be matched to a named instrument", mapper.createObjectNode());
            return;
        }
        if (!progress(run, input, "SOURCE_VERIFICATION", mapper.createObjectNode().put("candidateName", displayName))) return;
        KnowledgeBaseQualityGateService.SourceVerificationResult verification = knowledgeBaseService.verifyWebsearchSources(discovered.citations());
        if (!verification.passed()) {
            ObjectNode details = mapper.createObjectNode(); details.set("reasons", mapper.valueToTree(verification.reasons()));
            review(run, input, "SOURCE_VERIFICATION", "INSUFFICIENT_EVIDENCE", "Verified source evidence is insufficient", details);
            return;
        }

        ArrayNode facts = mapper.createArrayNode();
        if (!progress(run, input, "FACT_EXTRACTION", factsProgress(discovered, verification, facts))) return;
        if (facts.isEmpty()) {
            review(run, input, "FACT_EXTRACTION", "INSUFFICIENT_EVIDENCE", "No verified facts could be extracted", mapper.createObjectNode());
            return;
        }
        if (!progress(run, input, "DOSSIER_COMPOSITION", mapper.createObjectNode().put("verifiedFactCount", facts.size()))) return;
        // The only composable content is the fact whose evidence is a verified source. Citations
        // are rebuilt from the source-policy output, never copied from discovery unfiltered.
        InstrumentDossierWebsearchResponseDto composed = new InstrumentDossierWebsearchResponseDto(
                facts.get(0).path("value").asText(), displayName, toToolsJson(verifiedCitations(verification)), discovered.model());
        ObjectNode result = mapper.valueToTree(composed);
        ObjectNode details = mapper.createObjectNode(); details.set("facts", facts); details.put("verifiedFactCount", facts.size());
        runService.completeAction(run, KnowledgeBaseRunStatus.COMPLETED,
                encodeProgress(input, "DOSSIER_COMPOSITION", details, output("Completed", result)), null, LocalDateTime.now());
    }

    private boolean progress(KnowledgeBaseRun run, ObjectNode input, String step, JsonNode details) {
        return runService.updateProgress(run, step, encodeProgress(input, step, details, null), LocalDateTime.now()).isPresent();
    }

    private void review(KnowledgeBaseRun run, ObjectNode input, String step, String code, String message, ObjectNode details) {
        details.put("reviewRequired", true); details.put("errorCode", code);
        runService.completeAction(run, KnowledgeBaseRunStatus.REVIEW_REQUIRED,
                encodeProgress(input, step, details, output(message, null)), message, code, LocalDateTime.now());
    }

    private ObjectNode factsProgress(InstrumentDossierWebsearchResponseDto draft,
            KnowledgeBaseQualityGateService.SourceVerificationResult verification, ArrayNode facts) {
        // Keep an intentionally small, auditable fact set. Values and evidence are bounded/redacted.
        for (KnowledgeBaseQualityGateService.VerifiedSource source : verification.sources().stream().limit(MAX_FACTS).toList()) {
            ObjectNode fact = mapper.createObjectNode();
            fact.put("field", "dossier_content");
            fact.put("value", KnowledgeBaseSourceUrlPolicy.bound(KnowledgeBaseSourceUrlPolicy.redactSensitiveText(draft.contentMd()), MAX_FACT_VALUE));
            fact.put("source", source.url());
            fact.put("evidence", KnowledgeBaseSourceUrlPolicy.bound(KnowledgeBaseSourceUrlPolicy.redactSensitiveText(source.title()), 512));
            facts.add(fact);
            break; // one composed content fact; additional sources remain independently recorded below.
        }
        ObjectNode progress = mapper.createObjectNode(); progress.set("facts", facts); progress.set("verifiedSources", verifiedCitations(verification));
        return progress;
    }

    private ArrayNode verifiedCitations(KnowledgeBaseQualityGateService.SourceVerificationResult verification) {
        ArrayNode citations = mapper.createArrayNode();
        for (KnowledgeBaseQualityGateService.VerifiedSource source : verification.sources().stream().limit(MAX_FACTS).toList()) {
            ObjectNode citation = mapper.createObjectNode(); citation.put("url", source.url());
            if (source.publisher() != null) citation.put("publisher", KnowledgeBaseSourceUrlPolicy.bound(source.publisher(), 512));
            if (source.title() != null) citation.put("title", KnowledgeBaseSourceUrlPolicy.bound(source.title(), 512));
            citations.add(citation);
        }
        return citations;
    }

    private tools.jackson.databind.JsonNode toToolsJson(JsonNode value) {
        try { return TOOLS_JSON.readTree(mapper.writeValueAsString(value)); }
        catch (Exception ex) { throw new IllegalStateException("Unable to compose verified citations", ex); }
    }
    private void dispatchBulkChildren(KnowledgeBaseRun parent) { runService.childrenOf(parent.getRunId()).forEach(child -> dispatch(child.getRunId())); }
    private void reconcileBulkParent(KnowledgeBaseRun parent) {
        List<KnowledgeBaseRun> children = runService.childrenOf(parent.getRunId());
        if (children.isEmpty() || children.stream().anyMatch(c -> !c.getStatus().isTerminal())) return;
        KnowledgeBaseRun fresh = runService.findById(parent.getRunId()).orElse(parent);
        if (!fresh.getStatus().isActive() || checkpointCanceled(fresh)) return;
        KnowledgeBaseRunStatus finalStatus = children.stream().anyMatch(c -> c.getStatus() == KnowledgeBaseRunStatus.FAILED) ? KnowledgeBaseRunStatus.FAILED : KnowledgeBaseRunStatus.COMPLETED;
        runService.completeAction(fresh, finalStatus, encode(objectPayload(fresh), output(finalStatus == KnowledgeBaseRunStatus.COMPLETED ? "Completed" : "One or more bulk items failed", null)), finalStatus == KnowledgeBaseRunStatus.FAILED ? "One or more bulk items failed" : null, finalStatus == KnowledgeBaseRunStatus.FAILED ? "BULK_CHILD_FAILED" : null, null, LocalDateTime.now());
    }
    private boolean checkpointCanceled(KnowledgeBaseRun run) {
        KnowledgeBaseRun current = runService.findById(run.getRunId()).orElse(null);
        if (current != null && current.getCancelRequestedAt() != null) { runService.markCanceled(run, LocalDateTime.now()); return true; }
        return false;
    }
    /** A lost lease is terminal for this worker: no later provider/domain call or result write is allowed. */
    private boolean checkpoint(KnowledgeBaseRun run, AtomicBoolean ownershipLost) {
        return ownershipLost.get() || checkpointCanceled(run);
    }
    private KnowledgeBaseRun requireRun(String id) {
        try { return runService.findById(Long.valueOf(id)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LLM action not found")); }
        catch (NumberFormatException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LLM action not found"); }
    }
    private KnowledgeBaseLlmActionDto toDto(KnowledgeBaseRun run, boolean details) {
        ObjectNode p = objectPayload(run); JsonNode result = outputPayload(run).path("result"); List<String> isins = isins(p);
        List<KnowledgeBaseRun> children = run.getParentRun() == null && typeOf(run) == KnowledgeBaseLlmActionType.RESEARCH ? runService.childrenOf(run.getRunId()) : List.of();
        KnowledgeBaseBulkResearchResponseDto bulkResearchResult = details ? convert(result, KnowledgeBaseBulkResearchResponseDto.class) : null;
        if (details && bulkResearchResult == null) bulkResearchResult = hydrateBulkResearchResult(run, p);
        KnowledgeBaseAlternativesResponseDto alternativesResult = details ? convert(result, KnowledgeBaseAlternativesResponseDto.class) : null;
        if (details && alternativesResult == null) alternativesResult = hydrateAlternativesResult(run, p);
        KnowledgeBaseRefreshBatchResponseDto refreshBatchResult = details && run.getIsin() == null ? convert(result, KnowledgeBaseRefreshBatchResponseDto.class) : null;
        if (details && refreshBatchResult == null && run.getIsin() == null) refreshBatchResult = hydrateRefreshBatchResult(run, p);
        return new KnowledgeBaseLlmActionDto(String.valueOf(run.getRunId()), typeOf(run), statusOf(run), triggerOf(p), isins,
                run.getCreatedAt(), run.getUpdatedAt(), text(outputPayload(run), "message"), knowledgeBaseService.resolveManualApprovals(isins),
                bulkResearchResult,
                alternativesResult,
                refreshBatchResult,
                details && run.getIsin() != null ? convert(result, KnowledgeBaseRefreshItemDto.class) : null,
                details ? convert(result, InstrumentDossierExtractionResponseDto.class) : null,
                details ? convert(result, KnowledgeBaseMissingMetricsResponseDto.class) : null,
                run.getCurrentStep(), run.getAttempts(), run.getNextRetryAt(), run.getErrorCode(), safeReference(run),
                children.isEmpty() ? null : children.size(), children.isEmpty() ? null : (int) children.stream().filter(c -> c.getStatus() == KnowledgeBaseRunStatus.COMPLETED).count(),
                children.isEmpty() ? null : (int) children.stream().filter(c -> c.getStatus() == KnowledgeBaseRunStatus.FAILED).count(),
                children.isEmpty() ? null : (int) children.stream().filter(c -> c.getStatus() == KnowledgeBaseRunStatus.CANCELED).count());
    }
    private <T> T convert(JsonNode n, Class<T> type) { try { return n == null || n.isMissingNode() ? null : mapper.treeToValue(n, type); } catch (Exception ignored) { return null; } }
    private KnowledgeBaseBulkResearchResponseDto hydrateBulkResearchResult(KnowledgeBaseRun run, ObjectNode input) {
        List<String> bulkIsins = isins(input);
        if (bulkIsins.isEmpty()) return null;
        List<KnowledgeBaseBulkResearchItemDto> items = new ArrayList<>();
        int succeeded = 0, skipped = 0, failed = 0;
        for (String isin : bulkIsins) {
            KnowledgeBaseBulkResearchItemDto item = hydrateResearchItem(isin, run);
            items.add(item);
            switch (item.status()) {
                case SUCCEEDED -> succeeded++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }
        return new KnowledgeBaseBulkResearchResponseDto(bulkIsins.size(), succeeded, skipped, failed, items);
    }
    private KnowledgeBaseAlternativesResponseDto hydrateAlternativesResult(KnowledgeBaseRun run, ObjectNode input) {
        String baseIsin = run.getIsin() != null ? run.getIsin() : (isins(input).isEmpty() ? null : isins(input).get(0));
        if (baseIsin == null) return null;
        List<KnowledgeBaseAlternativeItemDto> alternatives = new ArrayList<>();
        List<KnowledgeBaseAlternative> stored = alternativeRepository.findByBaseIsinOrderByCreatedAtDesc(baseIsin);
        if (stored.isEmpty()) {
            stored = alternativeRepository.findAll().stream()
                    .filter(a -> a.getBaseIsin() != null && a.getBaseIsin().equalsIgnoreCase(baseIsin))
                    .sorted(Comparator.comparing(KnowledgeBaseAlternative::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                    .toList();
        }
        if (!stored.isEmpty()) {
            for (KnowledgeBaseAlternative alternative : stored) {
                alternatives.add(hydrateAlternativeItem(alternative));
            }
        } else {
            LocalDateTime since = run.getCreatedAt();
            runService.findActionRuns().stream()
                    .filter(r -> r.getIsin() != null && !r.getIsin().equals(baseIsin))
                    .filter(r -> since == null || (r.getStartedAt() != null && !r.getStartedAt().isBefore(since)))
                    .map(KnowledgeBaseRun::getIsin)
                    .distinct()
                    .map(isin -> hydrateAlternativeItem(isin, baseIsin))
                    .forEach(alternatives::add);
        }
        return new KnowledgeBaseAlternativesResponseDto(baseIsin, alternatives);
    }
    private KnowledgeBaseRefreshBatchResponseDto hydrateRefreshBatchResult(KnowledgeBaseRun run, ObjectNode input) {
        KnowledgeBaseRefreshBatchRequestDto request;
        try { request = mapper.treeToValue(input.path("request"), KnowledgeBaseRefreshBatchRequestDto.class); }
        catch (Exception ex) { return null; }
        List<String> candidates = request == null || request.scope() == null ? List.of() : normalizeIsins(request.scope().isins(), false);
        if (candidates.isEmpty()) return null;
        if (request.limit() != null && request.limit() > 0 && candidates.size() > request.limit()) {
            candidates = candidates.subList(0, request.limit());
        }
        boolean dryRun = request.dryRun() != null && request.dryRun();
        List<KnowledgeBaseRefreshItemDto> items = new ArrayList<>();
        int processed = 0, succeeded = 0, skipped = 0, failed = 0;
        for (String isin : candidates) {
            KnowledgeBaseRefreshItemDto item = dryRun ? new KnowledgeBaseRefreshItemDto(isin, KnowledgeBaseBulkResearchItemStatus.SKIPPED, null, null, "dry_run", null) : hydrateRefreshItem(isin, run);
            items.add(item);
            processed++;
            switch (item.status()) {
                case SUCCEEDED -> succeeded++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }
        return new KnowledgeBaseRefreshBatchResponseDto(candidates.size(), processed, succeeded, skipped, failed, dryRun, items);
    }
    private KnowledgeBaseBulkResearchItemDto hydrateResearchItem(String isin, KnowledgeBaseRun run) {
        KnowledgeBaseRun latest = runService.findLatest(isin, KnowledgeBaseRunAction.BULK_CREATE).orElse(null);
        KnowledgeBaseBulkResearchItemStatus status = mapBulkResearchStatus(latest == null ? null : latest.getStatus());
        InstrumentDossier dossier = dossierRepository.findFirstByIsinOrderByVersionDesc(isin).orElse(null);
        Long dossierId = dossier == null ? null : dossier.getDossierId();
        InstrumentDossierExtraction extraction = dossierId == null ? null : extractionRepository.findByDossierIdOrderByCreatedAtDesc(dossierId).stream().findFirst().orElse(null);
        Long extractionId = extraction == null ? null : extraction.getExtractionId();
        String error = latest == null ? null : latest.getError();
        KnowledgeBaseManualApprovalDto manualApproval = dossier == null && extraction == null
                ? knowledgeBaseService.resolveManualApprovalForIsin(isin)
                : knowledgeBaseService.resolveManualApproval(dossier == null ? null : dossier.getStatus(), extraction == null ? null : extraction.getStatus());
        if (status == null) status = dossierId != null && extractionId != null ? KnowledgeBaseBulkResearchItemStatus.SUCCEEDED : KnowledgeBaseBulkResearchItemStatus.FAILED;
        return new KnowledgeBaseBulkResearchItemDto(isin, status, dossierId, extractionId, error, manualApproval);
    }
    private KnowledgeBaseRefreshItemDto hydrateRefreshItem(String isin, KnowledgeBaseRun run) {
        KnowledgeBaseRun latest = runService.findLatest(isin, KnowledgeBaseRunAction.REFRESH).orElse(null);
        KnowledgeBaseBulkResearchItemStatus status = mapBulkResearchStatus(latest == null ? null : latest.getStatus());
        InstrumentDossier dossier = dossierRepository.findFirstByIsinOrderByVersionDesc(isin).orElse(null);
        Long dossierId = dossier == null ? null : dossier.getDossierId();
        InstrumentDossierExtraction extraction = dossierId == null ? null : extractionRepository.findByDossierIdOrderByCreatedAtDesc(dossierId).stream().findFirst().orElse(null);
        Long extractionId = extraction == null ? null : extraction.getExtractionId();
        String error = latest == null ? null : latest.getError();
        KnowledgeBaseManualApprovalDto manualApproval = dossier == null && extraction == null
                ? knowledgeBaseService.resolveManualApprovalForIsin(isin)
                : knowledgeBaseService.resolveManualApproval(dossier == null ? null : dossier.getStatus(), extraction == null ? null : extraction.getStatus());
        if (status == null) status = dossierId != null && extractionId != null ? KnowledgeBaseBulkResearchItemStatus.SUCCEEDED : KnowledgeBaseBulkResearchItemStatus.FAILED;
        return new KnowledgeBaseRefreshItemDto(isin, status, dossierId, extractionId, error, manualApproval);
    }
    private KnowledgeBaseAlternativeItemDto hydrateAlternativeItem(KnowledgeBaseAlternative alternative) {
        return hydrateAlternativeItem(alternative.getAltIsin(), alternative.getRationale(), alternative.getSourcesJson(), alternative.getStatus());
    }
    private KnowledgeBaseAlternativeItemDto hydrateAlternativeItem(String isin, String rationale, tools.jackson.databind.JsonNode citations, KnowledgeBaseAlternativeStatus status) {
        InstrumentDossier dossier = dossierRepository.findFirstByIsinOrderByVersionDesc(isin).orElse(null);
        Long dossierId = dossier == null ? null : dossier.getDossierId();
        InstrumentDossierExtraction extraction = dossierId == null ? null : extractionRepository.findByDossierIdOrderByCreatedAtDesc(dossierId).stream().findFirst().orElse(null);
        Long extractionId = extraction == null ? null : extraction.getExtractionId();
        KnowledgeBaseManualApprovalDto manualApproval = dossier == null && extraction == null
                ? knowledgeBaseService.resolveManualApprovalForIsin(isin)
                : knowledgeBaseService.resolveManualApproval(dossier == null ? null : dossier.getStatus(), extraction == null ? null : extraction.getStatus());
        KnowledgeBaseAlternativeStatus resolvedStatus = status == null ? (dossierId != null && extractionId != null ? KnowledgeBaseAlternativeStatus.GENERATED : KnowledgeBaseAlternativeStatus.PROPOSED) : status;
        return new KnowledgeBaseAlternativeItemDto(isin, rationale, citations, resolvedStatus, dossierId, extractionId, null, manualApproval);
    }
    private KnowledgeBaseAlternativeItemDto hydrateAlternativeItem(String isin, String baseIsin) {
        InstrumentDossier dossier = dossierRepository.findFirstByIsinOrderByVersionDesc(isin).orElse(null);
        Long dossierId = dossier == null ? null : dossier.getDossierId();
        InstrumentDossierExtraction extraction = dossierId == null ? null : extractionRepository.findByDossierIdOrderByCreatedAtDesc(dossierId).stream().findFirst().orElse(null);
        Long extractionId = extraction == null ? null : extraction.getExtractionId();
        KnowledgeBaseManualApprovalDto manualApproval = dossier == null && extraction == null
                ? knowledgeBaseService.resolveManualApprovalForIsin(isin)
                : knowledgeBaseService.resolveManualApproval(dossier == null ? null : dossier.getStatus(), extraction == null ? null : extraction.getStatus());
        KnowledgeBaseAlternativeStatus status = dossierId != null && extractionId != null ? KnowledgeBaseAlternativeStatus.GENERATED : KnowledgeBaseAlternativeStatus.PROPOSED;
        return new KnowledgeBaseAlternativeItemDto(isin, null, null, status, dossierId, extractionId, null, manualApproval);
    }
    private KnowledgeBaseBulkResearchItemStatus mapBulkResearchStatus(KnowledgeBaseRunStatus status) {
        if (status == null) return null;
        return switch (status) {
            case QUEUED, RUNNING, IN_PROGRESS, WAITING_RETRY -> null;
            case COMPLETED, SUCCEEDED -> KnowledgeBaseBulkResearchItemStatus.SUCCEEDED;
            case SKIPPED -> KnowledgeBaseBulkResearchItemStatus.SKIPPED;
            case CANCELED, FAILED, FAILED_TIMEOUT, REVIEW_REQUIRED -> KnowledgeBaseBulkResearchItemStatus.FAILED;
        };
    }
    private KnowledgeBaseLlmActionType typeOf(KnowledgeBaseRun r) { return switch (r.getAction()) { case BULK_CREATE -> KnowledgeBaseLlmActionType.RESEARCH; case EXTRACT -> KnowledgeBaseLlmActionType.EXTRACTION; case MISSING_DATA -> KnowledgeBaseLlmActionType.MISSING_METRICS; case ALTERNATIVES -> KnowledgeBaseLlmActionType.ALTERNATIVES; default -> KnowledgeBaseLlmActionType.REFRESH; }; }
    private KnowledgeBaseLlmActionStatus statusOf(KnowledgeBaseRun r) { return switch (r.getStatus()) { case QUEUED -> KnowledgeBaseLlmActionStatus.QUEUED; case RUNNING, IN_PROGRESS -> KnowledgeBaseLlmActionStatus.RUNNING; case WAITING_RETRY -> KnowledgeBaseLlmActionStatus.WAITING_RETRY; case REVIEW_REQUIRED -> KnowledgeBaseLlmActionStatus.REVIEW_REQUIRED; case COMPLETED, SUCCEEDED, SKIPPED -> KnowledgeBaseLlmActionStatus.COMPLETED; case CANCELED -> KnowledgeBaseLlmActionStatus.CANCELED; case FAILED, FAILED_TIMEOUT -> KnowledgeBaseLlmActionStatus.FAILED; }; }
    private ObjectNode payload(KnowledgeBaseLlmActionType type, KnowledgeBaseLlmActionTrigger trigger, List<String> isins, String actor, String message) { ObjectNode p = mapper.createObjectNode(); p.put("type", type.name()); p.put("trigger", (trigger == null ? KnowledgeBaseLlmActionTrigger.USER : trigger).name()); p.set("isins", mapper.valueToTree(isins)); p.put("actor", safe(actor)); p.put("message", safe(message)); return p; }
    /** Reads v2 input or a pre-envelope payload created during the rolling deployment. */
    private ObjectNode objectPayload(KnowledgeBaseRun r) { try { JsonNode n = mapper.readTree(r.getActionPayload()); if (n instanceof ObjectNode o) return o.path("input") instanceof ObjectNode input ? input.deepCopy() : o.deepCopy(); return mapper.createObjectNode(); } catch (Exception ignored) { return mapper.createObjectNode(); } }
    private ObjectNode outputPayload(KnowledgeBaseRun r) { try { JsonNode n = mapper.readTree(r.getActionPayload()); return n instanceof ObjectNode o && o.path("output") instanceof ObjectNode output ? output : mapper.createObjectNode(); } catch (Exception ignored) { return mapper.createObjectNode(); } }
    private ObjectNode output(String message, JsonNode result) { ObjectNode output = mapper.createObjectNode(); output.put("message", safe(message)); if (result != null) output.set("result", result); return output; }
	private String encode(ObjectNode input, ObjectNode output) {
		try {
			ObjectNode envelope = mapper.createObjectNode();
			envelope.set("input", input.deepCopy());
			if (output != null) {
				envelope.set("output", summarizeOutput(output));
			}
			return mapper.writeValueAsString(envelope);
		} catch (Exception e) {
			throw new IllegalStateException("Unable to persist durable action input", e);
		}
	}
	private String encodeProgress(ObjectNode input, String step, JsonNode details, ObjectNode output) {
		try {
			ObjectNode envelope = mapper.createObjectNode();
			envelope.set("input", input.deepCopy());
			ObjectNode progress = mapper.createObjectNode();
			progress.put("step", safe(step));
			if (details != null) progress.set("details", details);
			envelope.set("progress", progress);
			if (output != null) envelope.set("output", summarizeOutput(output));
			String encoded = mapper.writeValueAsString(envelope);
			if (encoded.length() <= MAX_PAYLOAD) return encoded;
			ObjectNode bounded = mapper.createObjectNode();
			bounded.set("input", input.deepCopy());
			bounded.set("progress", mapper.createObjectNode().put("step", safe(step)).put("truncated", true));
			return mapper.writeValueAsString(bounded);
		} catch (Exception e) {
			throw new IllegalStateException("Unable to persist workflow progress", e);
		}
	}
	private ObjectNode summarizeOutput(ObjectNode output) {
		ObjectNode summary = mapper.createObjectNode();
		String message = text(output, "message");
		if (message != null) summary.put("message", safe(message));
		String errorCode = text(output, "errorCode");
		if (errorCode != null) summary.put("errorCode", safe(errorCode));
		JsonNode result = output.get("result");
		if (result != null && !result.isNull() && !result.isMissingNode()) {
			summary.put("resultPresent", true);
			summary.put("resultType", result.isObject() ? "object" : result.getNodeType().name().toLowerCase(Locale.ROOT));
			if (result.isObject()) {
				summary.put("resultFieldCount", result.size());
			} else if (result.isArray()) {
				summary.put("resultItemCount", result.size());
			}
			// Legacy websearch GET returns this DTO. Retain only its bounded, policy-clean result.
			if (result.isObject() && result.has("contentMd") && result.has("displayName")) {
				ObjectNode safeResult = mapper.createObjectNode();
				safeResult.put("contentMd", KnowledgeBaseSourceUrlPolicy.bound(
						KnowledgeBaseSourceUrlPolicy.redactSensitiveText(result.path("contentMd").asText()), MAX_FACT_VALUE));
				safeResult.put("displayName", safe(result.path("displayName").asText()));
				safeResult.put("model", safe(result.path("model").asText()));
				JsonNode citations = result.path("citations");
				if (citations.isArray()) safeResult.set("citations", citations.deepCopy());
				summary.set("result", safeResult);
			} else if (isSafeHydratableResult(result)) {
				summary.set("result", result.deepCopy());
			}
		}
		return summary;
	}
	private boolean isSafeHydratableResult(JsonNode result) {
		return result != null && result.isObject() && (
				(result.has("total") && result.has("succeeded") && result.has("skipped") && result.has("failed") && result.has("items"))
				|| (result.has("baseIsin") && result.has("alternatives"))
				|| (result.has("totalCandidates") && result.has("processed") && result.has("dryRun") && result.has("items"))
				|| (result.has("isin") && result.has("status") && result.has("dossierId") && result.has("extractionId"))
				|| (result.has("extractionId") && result.has("dossierId") && result.has("status") && result.has("evidenceGate"))
		);
	}
    private KnowledgeBaseLlmActionTrigger triggerOf(ObjectNode p) { try { return KnowledgeBaseLlmActionTrigger.valueOf(text(p, "trigger")); } catch (Exception e) { return KnowledgeBaseLlmActionTrigger.USER; } }
    private List<String> isins(ObjectNode p) { List<String> values = new ArrayList<>(); p.path("isins").forEach(n -> values.add(n.asText())); return List.copyOf(values); }
    private String text(ObjectNode p, String field) { return p.path(field).isMissingNode() ? null : p.path(field).asText(null); }
    private String invalidInput(KnowledgeBaseRun run, ObjectNode p) {
        if (text(p, "type") == null || text(p, "trigger") == null || text(p, "actor") == null) return "Missing durable action input";
        if (!typeOf(run).name().equals(text(p, "type"))) return "Durable action type does not match input";
        return switch (typeOf(run)) {
            case RESEARCH, ALTERNATIVES -> isins(p).isEmpty() ? "Missing ISIN input" : null;
            case REFRESH -> run.getIsin() == null && p.path("request").isMissingNode() ? "Missing refresh request input" : null;
            case EXTRACTION, MISSING_METRICS -> p.path("dossierId").canConvertToLong() && p.path("dossierId").asLong() > 0 ? null : "Missing dossier input";
        };
    }
    private String idempotencyKey(KnowledgeBaseRunAction action, ObjectNode input) { try { MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] hash = digest.digest((text(input, "actor") + "|" + action.name() + "|" + mapper.writeValueAsString(input)).getBytes(StandardCharsets.UTF_8)); return "intent:" + java.util.HexFormat.of().formatHex(hash); } catch (Exception e) { throw new IllegalStateException("Unable to derive action idempotency key", e); } }
    private String scopedCallerKey(String actor, String key) {
        if (key == null || key.isBlank()) return null;
        String normalizedActor = actor == null || actor.isBlank() ? "anonymous" : actor.trim();
        try { return "client:" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((normalizedActor + "\u0000" + key.trim()).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Unable to scope idempotency key", e); }
    }
	FailureClassification classify(Exception exception) {
		LlmRequestException llm = findCause(exception, LlmRequestException.class);
		if (llm != null) {
			return new FailureClassification(llm.isRetryable(), failureCode(llm), llm.getRequestId(), llm.getRetryAfter());
		}
		if (isTimeoutLike(exception)) {
			return new FailureClassification(true, "TIMEOUT", null, null);
		}
		if (findCause(exception, org.springframework.web.client.ResourceAccessException.class) != null) {
			return new FailureClassification(true, "PROVIDER_RETRYABLE", null, null);
		}
		return new FailureClassification(false, "ACTION_FAILED", null, null);
	}

	Duration retryDelay(FailureClassification failure) {
		int maxBackoffSeconds = Math.max(1, configService.getSnapshot().maxBackoffSeconds());
		Duration cap = Duration.ofSeconds(maxBackoffSeconds);
		Duration fallback = Duration.ofSeconds(Math.min(60, maxBackoffSeconds));
		Duration retryAfter = failure.retryAfter();
		if (retryAfter == null) {
			return fallback;
		}
		if (retryAfter.isNegative()) {
			return Duration.ZERO;
		}
		return retryAfter.compareTo(cap) > 0 ? cap : retryAfter;
	}

	LocalDateTime nextRetryAt(LocalDateTime now, FailureClassification failure) {
		return now.plus(retryDelay(failure));
	}

	private String failureCode(LlmRequestException exception) {
		Integer statusCode = exception.getStatusCode();
		if (statusCode == null) {
			return isTimeoutLike(exception) ? "TIMEOUT" : "PROVIDER_RETRYABLE";
		}
		if (statusCode == 429) return "RATE_LIMIT";
		if (statusCode == 408) return "TIMEOUT";
		if (statusCode >= 500) return "PROVIDER_RETRYABLE";
		return "PROVIDER_FAILED";
	}

	private boolean isTimeoutLike(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof java.net.SocketTimeoutException || current instanceof java.io.InterruptedIOException) {
				return true;
			}
		}
		return false;
	}

	private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (type.isInstance(current)) {
				return type.cast(current);
			}
		}
		return null;
	}
	/** max_retries_per_instrument is the configured number of retries after the initial attempt. */
	private int maxRetries() { return configService.getSnapshot().maxRetriesPerInstrument(); }
	record FailureClassification(boolean retryable, String code, String requestId, Duration retryAfter) { }
    private String safe(String value) { if (value == null) return null; String s = value.replaceAll("[\\r\\n]+", " ").trim(); return s.length() > 512 ? s.substring(0, 512) : s; }
    private List<String> normalizeIsins(List<String> values, boolean required) { if (values == null || values.isEmpty()) { if (required) throw new IllegalArgumentException("At least one ISIN is required"); return List.of(); } return values.stream().map(this::normalizeIsin).distinct().toList(); }
    private String normalizeIsin(String value) { String isin = value == null ? "" : value.trim().toUpperCase(Locale.ROOT); if (!ISIN_RE.matcher(isin).matches()) throw new IllegalArgumentException("Invalid ISIN: " + isin); return isin; }
    private String oneIsin(KnowledgeBaseLlmActionCreateRequestDto request) { List<String> values = normalizeIsins(request.isins(), true); if (values.size() != 1) throw new IllegalArgumentException("Exactly one ISIN is required"); return values.get(0); }
    private Long requiredDossierId(KnowledgeBaseLlmActionCreateRequestDto request) { if (request.dossierId() == null || request.dossierId() <= 0) throw new IllegalArgumentException("A dossierId is required"); return request.dossierId(); }
    private String safeReference(KnowledgeBaseRun run) { return run.getErrorCode() == null ? null : "run:" + run.getRunId(); }
    @PreDestroy public void shutdown() { executor.shutdownNow(); leaseHeartbeats.shutdownNow(); retryDispatcher.shutdownNow(); }
}
