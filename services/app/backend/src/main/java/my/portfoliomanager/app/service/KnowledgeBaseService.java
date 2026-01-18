package my.portfoliomanager.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.domain.*;
import my.portfoliomanager.app.dto.*;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmClient;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmDossierDraft;
import my.portfoliomanager.app.llm.LlmClient;
import my.portfoliomanager.app.llm.LlmSuggestion;
import my.portfoliomanager.app.repository.*;
import my.portfoliomanager.app.repository.projection.InstrumentDossierSearchProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

import static my.portfoliomanager.app.service.util.ISINUtil.normalizeIsin;
import static my.portfoliomanager.app.service.util.ISINUtil.normalizeIsins;

@Service
public class KnowledgeBaseService {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final Pattern ISIN_RE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");
    private static final int MAX_LIMIT = 1000;
    private static final String EDIT_SOURCE = "KB_EXTRACTION";
    private final InstrumentRepository instrumentRepository;
    private final InstrumentDossierRepository dossierRepository;
    private final InstrumentDossierExtractionRepository extractionRepository;
    private final InstrumentOverrideRepository overrideRepository;
    private final InstrumentFactRepository factRepository;
    private final AuditService auditService;
    private final ExtractorService extractorService;
    private final KnowledgeBaseExtractionService knowledgeBaseExtractionService;
    private final KnowledgeBaseConfigService configService;
    private final KnowledgeBaseLlmClient knowledgeBaseLlmClient;
    private final KnowledgeBaseRunService runService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final Map<String, Object> bulkWebsearchSchema;

    public KnowledgeBaseService(InstrumentRepository instrumentRepository,
                                InstrumentDossierRepository dossierRepository,
                                InstrumentDossierExtractionRepository extractionRepository,
                                InstrumentOverrideRepository overrideRepository,
                                InstrumentFactRepository factRepository,
                                AuditService auditService,
                                ExtractorService extractorService,
                                KnowledgeBaseExtractionService knowledgeBaseExtractionService,
                                KnowledgeBaseConfigService configService,
                                KnowledgeBaseLlmClient knowledgeBaseLlmClient,
                                KnowledgeBaseRunService runService,
                                LlmClient llmClient,
                                ObjectMapper objectMapper) {
        this.instrumentRepository = instrumentRepository;
        this.dossierRepository = dossierRepository;
        this.extractionRepository = extractionRepository;
        this.overrideRepository = overrideRepository;
        this.factRepository = factRepository;
        this.auditService = auditService;
        this.extractorService = extractorService;
        this.knowledgeBaseExtractionService = knowledgeBaseExtractionService;
        this.configService = configService;
        this.knowledgeBaseLlmClient = knowledgeBaseLlmClient;
        this.runService = runService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.bulkWebsearchSchema = buildBulkWebsearchSchema(objectMapper);
    }

    public InstrumentDossierSearchPageDto searchDossiers(String query,
                                                         DossierStatus status,
                                                         Boolean stale,
                                                         int limit,
                                                         int offset) {
        int finalLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int finalOffset = Math.max(offset, 0);
        String normalizedQuery = normalizeQuery(query);
        KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
        LocalDateTime staleBefore = LocalDateTime.now().minusDays(config.refreshIntervalDays());
        String statusFilter = status == null ? null : status.name();
        long total = dossierRepository.countSearch(normalizedQuery, statusFilter, stale, staleBefore);
        List<InstrumentDossierSearchProjection> rows = dossierRepository.searchDossiers(
                normalizedQuery,
                statusFilter,
                stale,
                staleBefore,
                finalLimit,
                finalOffset
        );
        List<InstrumentDossierSearchItemDto> items = rows.stream().map(this::toSearchItem).toList();
        return new InstrumentDossierSearchPageDto(items, Math.toIntExact(total), finalLimit, finalOffset);
    }

    @Transactional
    public KnowledgeBaseDossierDeleteResultDto deleteDossiers(List<String> isins) {
        List<String> normalized = normalizeIsins(isins);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one ISIN is required");
        }
        dossierRepository.clearSupersedesByIsinIn(normalized);
        List<Long> dossierIds = dossierRepository.findIdsByIsinIn(normalized);
        int deletedExtractions = dossierIds.isEmpty() ? 0 : extractionRepository.deleteByDossierIdIn(dossierIds);
        int deletedDossiers = dossierRepository.deleteByIsinIn(normalized);
        int deletedKbExtractions = knowledgeBaseExtractionService.deleteByIsins(normalized);
        return new KnowledgeBaseDossierDeleteResultDto(
                normalized.size(),
                deletedDossiers,
                deletedExtractions,
                deletedKbExtractions
        );
    }

    public InstrumentDossierWebsearchResponseDto createDossierDraftViaWebsearch(String isin) {
        logger.info("createDossierDraftViaWebsearch for ISIN {}", isin);
        String normalizedIsin = normalizeIsin(isin);
        KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
        KnowledgeBaseLlmDossierDraft parsed;
        try {
            parsed = knowledgeBaseLlmClient.generateDossier(
                    normalizedIsin,
                    null,
                    config.websearchAllowedDomains(),
                    config.dossierMaxChars()
            );
        } catch (Exception ex) {
            logger.error("Received error from LLM", ex);
            String message = ex.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("web_search") || lower.contains("web search") || lower.contains("tools")) {
                    message = "LLM model/provider does not support web_search tools: " + message;
                }
            }
            throw new IllegalStateException("LLM websearch request failed: " + (message == null ? "Unknown error" : message));
        }
        return new InstrumentDossierWebsearchResponseDto(
                parsed.contentMd(),
                parsed.displayName(),
                parsed.citations(),
                parsed.model()
        );
    }

    @Transactional
    public DossierUpsertResult upsertDossierFromWebsearchDraft(String isin,
                                                               String contentMd,
                                                               String displayName,
                                                               JsonNode citations,
                                                               String createdBy) {
        String normalizedIsin = normalizeIsin(isin);
        String content = normalizeContent(contentMd);
        JsonNode normalizedCitations = normalizeCitationsAllowEmpty(citations);
        LocalDateTime now = LocalDateTime.now();
        InstrumentDossier previous = dossierRepository.findFirstByIsinOrderByVersionDesc(normalizedIsin).orElse(null);
        if (previous != null && previous.getStatus() != DossierStatus.SUPERSEDED) {
            previous.setStatus(DossierStatus.SUPERSEDED);
            previous.setUpdatedAt(now);
            dossierRepository.save(previous);
        }
        InstrumentDossier dossier = new InstrumentDossier();
        dossier.setIsin(normalizedIsin);
        dossier.setDisplayName(trimToNull(displayName));
        dossier.setCreatedBy(createdBy == null || createdBy.isBlank() ? "system" : createdBy);
        dossier.setOrigin(DossierOrigin.LLM_WEBSEARCH);
        dossier.setAuthoredBy(DossierAuthoredBy.LLM);
        dossier.setStatus(DossierStatus.DRAFT);
        dossier.setVersion(previous == null || previous.getVersion() == null ? 1 : previous.getVersion() + 1);
        dossier.setSupersedesId(previous == null ? null : previous.getDossierId());
        dossier.setContentMd(content);
        dossier.setCitationsJson(normalizedCitations);
        dossier.setContentHash(hashContent(content));
        dossier.setCreatedAt(now);
        dossier.setUpdatedAt(now);
        dossier.setAutoApproved(false);
        return new DossierUpsertResult(toResponse(dossierRepository.save(dossier)), previous == null);
    }

    public BulkWebsearchDraftResult createDossierDraftsViaWebsearchBulk(List<String> isins) {
        List<String> normalized = normalizeIsins(isins);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one ISIN is required");
        }
        logger.info("Performing Buld Draft Creating for {} isins", normalized.size());
        KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
        String prompt = buildBulkWebsearchPrompt(normalized, config.dossierMaxChars());
        logger.debug("Sending Prompt {}", prompt);
        LlmSuggestion suggestion;
        try {
            logger.info("Sending bulk research to LLM");
            suggestion = llmClient.createInstrumentDossierViaWebSearch(
                    prompt,
                    "kb_bulk_dossier_websearch",
                    bulkWebsearchSchema
            );
            logger.info("LLM responded");
        } catch (Exception ex) {
            logger.error("Received error from LLM", ex);
            String message = ex.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("web_search") || lower.contains("web search") || lower.contains("tools")) {
                    message = "LLM model/provider does not support web_search tools: " + message;
                }
            }

            throw new IllegalStateException("LLM websearch request failed: " + (message == null ? "Unknown error" : message));
        }
        if (suggestion == null) {
            throw new IllegalStateException("LLM returned no response");
        }
        if (suggestion.suggestion() == null || suggestion.suggestion().isBlank()) {
            if (suggestion.rationale() != null && !suggestion.rationale().isBlank()) {
                throw new IllegalStateException(suggestion.rationale());
            }
            throw new IllegalStateException("LLM returned empty dossier drafts");
        }
        logger.info("LLM returned bulk result");
        List<BulkWebsearchDraftItem> items = parseBulkWebsearchDrafts(suggestion.suggestion(), normalized);
        return new BulkWebsearchDraftResult(items, suggestion.rationale());
    }

    @Transactional
    public InstrumentDossierResponseDto createDossier(InstrumentDossierCreateRequest request, String createdBy) {
        String normalizedIsin = normalizeIsin(request.isin());
        JsonNode citations = ensureCitations(request.citations());
        String content = normalizeContent(request.contentMd());
        String displayName = trimToNull(request.displayName());
        LocalDateTime now = LocalDateTime.now();
        InstrumentDossier previous = dossierRepository.findFirstByIsinOrderByVersionDesc(normalizedIsin).orElse(null);
        if (previous != null && previous.getStatus() != DossierStatus.SUPERSEDED) {
            previous.setStatus(DossierStatus.SUPERSEDED);
            previous.setUpdatedAt(now);
            dossierRepository.save(previous);
        }
        InstrumentDossier dossier = new InstrumentDossier();
        dossier.setIsin(normalizedIsin);
        dossier.setDisplayName(displayName);
        dossier.setCreatedBy(createdBy);
        dossier.setOrigin(request.origin());
        dossier.setAuthoredBy(request.origin() == DossierOrigin.USER ? DossierAuthoredBy.USER : DossierAuthoredBy.LLM);
        dossier.setVersion(previous == null || previous.getVersion() == null ? 1 : previous.getVersion() + 1);
        dossier.setSupersedesId(previous == null ? null : previous.getDossierId());
        dossier.setContentMd(content);
        dossier.setCitationsJson(citations);
        dossier.setContentHash(hashContent(content));
        dossier.setCreatedAt(now);
        dossier.setUpdatedAt(now);
        applyDossierStatus(dossier, request.status(), createdBy, false);
        return toResponse(dossierRepository.save(dossier));
    }

    public InstrumentDossierResponseDto getDossier(Long dossierId) {
        InstrumentDossier dossier = dossierRepository.findById(dossierId)
                .orElseThrow(() -> new IllegalArgumentException("Dossier not found"));
        return toResponse(dossier);
    }

    public KnowledgeBaseDossierDetailDto getDossierDetail(String isin) {
        String normalizedIsin = normalizeIsin(isin);
        List<InstrumentDossier> versions = dossierRepository.findByIsinOrderByVersionDesc(normalizedIsin);
        if (versions == null || versions.isEmpty()) {
            throw new IllegalArgumentException("Dossier not found");
        }
        InstrumentDossier latest = versions.get(0);
        List<KnowledgeBaseDossierVersionDto> history = versions.stream()
                .map(this::toVersionDto)
                .toList();
        List<InstrumentDossierExtractionResponseDto> extractions = extractionRepository
                .findByDossierIdOrderByCreatedAtDesc(latest.getDossierId())
                .stream()
                .map(this::toResponse)
                .toList();
        KnowledgeBaseRunItemDto lastRun = runService.findLatest(normalizedIsin, KnowledgeBaseRunAction.REFRESH)
                .map(this::toRunDto)
                .orElse(null);
        String displayName = latest.getDisplayName();
        return new KnowledgeBaseDossierDetailDto(
                normalizedIsin,
                displayName,
                toResponse(latest),
                history,
                extractions,
                lastRun
        );
    }

    @Transactional
    public InstrumentDossierResponseDto updateDossier(Long dossierId, InstrumentDossierUpdateRequest request, String updatedBy) {
        InstrumentDossier dossier = dossierRepository.findById(dossierId)
                .orElseThrow(() -> new IllegalArgumentException("Dossier not found"));
        JsonNode citations = ensureCitations(request.citations());
        String content = normalizeContent(request.contentMd());
        if (request.displayName() != null) {
            dossier.setDisplayName(trimToNull(request.displayName()));
        }
        dossier.setContentMd(content);
        dossier.setCitationsJson(citations);
        dossier.setContentHash(hashContent(content));
        dossier.setUpdatedAt(LocalDateTime.now());
        applyDossierStatus(dossier, request.status(), updatedBy, false);
        return toResponse(dossierRepository.save(dossier));
    }

    public List<InstrumentDossierExtractionResponseDto> listExtractions(Long dossierId) {
        List<InstrumentDossierExtraction> extractions = extractionRepository.findByDossierIdOrderByCreatedAtDesc(dossierId);
        return extractions.stream().map(this::toResponse).toList();
    }

    @Transactional
    public InstrumentDossierExtractionResponseDto runExtraction(Long dossierId) {
        InstrumentDossier dossier = dossierRepository.findById(dossierId)
                .orElseThrow(() -> new IllegalArgumentException("Dossier not found"));
        LocalDateTime now = LocalDateTime.now();
        InstrumentDossierExtraction extraction = new InstrumentDossierExtraction();
        extraction.setDossierId(dossierId);
        extraction.setCreatedAt(now);
        extraction.setAutoApproved(false);
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Canceled");
            }
            ExtractionResult result = extractorService.extract(dossier);
            InstrumentDossierExtractionPayload payload = result.payload();
            JsonNode extractedJson = objectMapper.valueToTree(payload);
            List<InstrumentDossierExtractionPayload.MissingFieldPayload> missingFields =
                    payload.missingFields() == null ? List.of() : payload.missingFields();
            List<InstrumentDossierExtractionPayload.WarningPayload> warningFields =
                    payload.warnings() == null ? List.of() : payload.warnings();
            JsonNode missing = objectMapper.valueToTree(missingFields);
            JsonNode warnings = objectMapper.valueToTree(warningFields);
            extraction.setModel(result.model());
            extraction.setExtractedJson(extractedJson);
            extraction.setMissingFieldsJson(missing);
            extraction.setWarningsJson(warnings);
            extraction.setStatus(DossierExtractionStatus.PENDING_REVIEW);
        } catch (java.util.concurrent.CancellationException ex) {
            throw ex;
        } catch (my.portfoliomanager.app.llm.KnowledgeBaseLlmOutputException ex) {
            extraction.setModel("failed");
            extraction.setExtractedJson(objectMapper.createObjectNode());
            extraction.setMissingFieldsJson(objectMapper.createArrayNode());
            extraction.setWarningsJson(objectMapper.createArrayNode());
            extraction.setStatus(DossierExtractionStatus.FAILED);
            extraction.setError(ex.getErrorCode());
        } catch (Exception ex) {
            extraction.setModel("failed");
            extraction.setExtractedJson(objectMapper.createObjectNode());
            extraction.setMissingFieldsJson(objectMapper.createArrayNode());
            extraction.setWarningsJson(objectMapper.createArrayNode());
            extraction.setStatus(DossierExtractionStatus.FAILED);
            extraction.setError(ex.getMessage());
        }
        InstrumentDossierExtraction saved = extractionRepository.save(extraction);
        syncKnowledgeBaseExtraction(dossier.getIsin(), saved);
        return toResponse(saved);
    }

    @Transactional
    public InstrumentDossierExtractionResponseDto approveExtraction(Long extractionId, String approvedBy) {
        InstrumentDossierExtraction extraction = extractionRepository.findById(extractionId)
                .orElseThrow(() -> new IllegalArgumentException("Extraction not found"));
        boolean applyOverrides = configService.getSnapshot().applyExtractionsToOverrides();
        InstrumentDossierExtraction saved = approveExtractionInternal(extraction, approvedBy, false, applyOverrides);
        return toResponse(saved);
    }

    @Transactional
    public InstrumentDossierExtractionResponseDto approveExtraction(Long extractionId, String approvedBy, boolean autoApproved) {
        InstrumentDossierExtraction extraction = extractionRepository.findById(extractionId)
                .orElseThrow(() -> new IllegalArgumentException("Extraction not found"));
        boolean applyOverrides = configService.getSnapshot().applyExtractionsToOverrides();
        InstrumentDossierExtraction saved = approveExtractionInternal(extraction, approvedBy, autoApproved, applyOverrides);
        return toResponse(saved);
    }

    @Transactional
    public InstrumentDossierExtractionResponseDto approveExtraction(Long extractionId,
                                                                    String approvedBy,
                                                                    boolean autoApproved,
                                                                    boolean applyOverrides) {
        InstrumentDossierExtraction extraction = extractionRepository.findById(extractionId)
                .orElseThrow(() -> new IllegalArgumentException("Extraction not found"));
        InstrumentDossierExtraction saved = approveExtractionInternal(extraction, approvedBy, autoApproved, applyOverrides);
        return toResponse(saved);
    }

    @Transactional
    public InstrumentDossierResponseDto approveDossier(Long dossierId, String approvedBy) {
        InstrumentDossier dossier = dossierRepository.findById(dossierId)
                .orElseThrow(() -> new IllegalArgumentException("Dossier not found"));
        InstrumentDossier saved = approveDossierInternal(dossier, approvedBy, false);
        return toResponse(saved);
    }

    @Transactional
    public InstrumentDossierResponseDto approveDossier(Long dossierId, String approvedBy, boolean autoApproved) {
        InstrumentDossier dossier = dossierRepository.findById(dossierId)
                .orElseThrow(() -> new IllegalArgumentException("Dossier not found"));
        InstrumentDossier saved = approveDossierInternal(dossier, approvedBy, autoApproved);
        return toResponse(saved);
    }

    @Transactional
    public InstrumentDossierResponseDto rejectDossier(Long dossierId, String rejectedBy) {
        InstrumentDossier dossier = dossierRepository.findById(dossierId)
                .orElseThrow(() -> new IllegalArgumentException("Dossier not found"));
        if (dossier.getStatus() == DossierStatus.APPROVED) {
            throw new IllegalArgumentException("Approved dossiers must be superseded instead of rejected");
        }
        dossier.setStatus(DossierStatus.REJECTED);
        dossier.setUpdatedAt(LocalDateTime.now());
        dossier.setAutoApproved(false);
        return toResponse(dossierRepository.save(dossier));
    }

    @Transactional
    public InstrumentDossierExtractionResponseDto rejectExtraction(Long extractionId, String rejectedBy) {
        InstrumentDossierExtraction extraction = extractionRepository.findById(extractionId)
                .orElseThrow(() -> new IllegalArgumentException("Extraction not found"));
        if (extraction.getStatus() != DossierExtractionStatus.CREATED
                && extraction.getStatus() != DossierExtractionStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Only pending extractions can be rejected");
        }
        extraction.setStatus(DossierExtractionStatus.REJECTED);
        extraction.setError("rejected");
        extraction.setApprovedBy(rejectedBy);
        extraction.setApprovedAt(LocalDateTime.now());
        extraction.setAutoApproved(false);
        InstrumentDossierExtraction saved = extractionRepository.save(extraction);
        return toResponse(saved);
    }

    @Transactional
    public InstrumentDossierExtractionResponseDto applyExtraction(Long extractionId, String appliedBy) {
        InstrumentDossierExtraction extraction = extractionRepository.findById(extractionId)
                .orElseThrow(() -> new IllegalArgumentException("Extraction not found"));
        if (extraction.getStatus() != DossierExtractionStatus.APPROVED) {
            throw new IllegalArgumentException("Only APPROVED extractions can be applied");
        }
        InstrumentDossier dossier = dossierRepository.findById(extraction.getDossierId())
                .orElseThrow(() -> new IllegalArgumentException("Dossier not found"));
        String isin = dossier.getIsin();
        requireInstrument(isin);
        InstrumentDossierExtractionPayload payload = parsePayload(extraction.getExtractedJson());
        if (payload.isin() == null || payload.isin().isBlank()) {
            throw new IllegalArgumentException("Extraction ISIN is required");
        }
        String extractedIsin = normalizeIsin(payload.isin());
        if (!isin.equalsIgnoreCase(extractedIsin)) {
            throw new IllegalArgumentException("Extraction ISIN does not match dossier");
        }

        InstrumentOverride override = overrideRepository.findById(isin).orElseGet(() -> {
            InstrumentOverride created = new InstrumentOverride();
            created.setIsin(isin);
            return created;
        });
        boolean overwriteExisting = configService.getSnapshot().overwriteExistingOverrides();
        boolean changed = applyOverrides(override, payload, appliedBy, overwriteExisting);
        if (changed) {
            override.setUpdatedAt(LocalDateTime.now());
            overrideRepository.save(override);
        }
        applyFacts(isin, payload);

        extraction.setStatus(DossierExtractionStatus.APPLIED);
        extraction.setAppliedBy(appliedBy);
        extraction.setAppliedAt(LocalDateTime.now());
        InstrumentDossierExtraction saved = extractionRepository.save(extraction);
        syncKnowledgeBaseExtraction(isin, saved);
        return toResponse(saved);
    }

    private InstrumentDossierExtraction approveExtractionInternal(InstrumentDossierExtraction extraction,
                                                                  String approvedBy,
                                                                  boolean autoApproved,
                                                                  boolean applyOverrides) {
        if (extraction.getStatus() != DossierExtractionStatus.CREATED
                && extraction.getStatus() != DossierExtractionStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Only pending extractions can be approved");
        }
        extraction.setStatus(DossierExtractionStatus.APPROVED);
        extraction.setApprovedBy(approvedBy);
        extraction.setApprovedAt(LocalDateTime.now());
        extraction.setAutoApproved(autoApproved);
        InstrumentDossierExtraction saved = extractionRepository.save(extraction);
        String isin = resolveIsinForExtraction(saved);
        syncKnowledgeBaseExtraction(isin, saved);
        if (applyOverrides && isin != null && instrumentRepository.existsById(isin)) {
            try {
                applyExtraction(saved.getExtractionId(), approvedBy);
                saved = extractionRepository.findById(saved.getExtractionId()).orElse(saved);
            } catch (Exception ex) {
                logger.warn("Failed to apply extraction {}: {}", saved.getExtractionId(), ex.getMessage());
            }
        }
        return saved;
    }

    private InstrumentDossier approveDossierInternal(InstrumentDossier dossier, String approvedBy, boolean autoApproved) {
        if (dossier.getStatus() == DossierStatus.SUPERSEDED) {
            throw new IllegalArgumentException("Superseded dossiers cannot be approved");
        }
        if (dossier.getStatus() == DossierStatus.REJECTED) {
            throw new IllegalArgumentException("Rejected dossiers cannot be approved");
        }
        LocalDateTime now = LocalDateTime.now();
        applyDossierStatus(dossier, DossierStatus.APPROVED, approvedBy, autoApproved);
        dossier.setUpdatedAt(now);
        InstrumentDossier saved = dossierRepository.save(dossier);
        List<InstrumentDossier> others = dossierRepository.findByIsinOrderByVersionDesc(saved.getIsin());
        for (InstrumentDossier other : others) {
            if (other.getDossierId().equals(saved.getDossierId())) {
                continue;
            }
            if (other.getStatus() == DossierStatus.APPROVED) {
                other.setStatus(DossierStatus.SUPERSEDED);
                other.setUpdatedAt(now);
                dossierRepository.save(other);
            }
        }
        return saved;
    }

    private void syncKnowledgeBaseExtraction(String isin, InstrumentDossierExtraction extraction) {
        if (extraction == null) {
            return;
        }
        KnowledgeBaseExtractionStatus status = mapExtractionStatus(extraction.getStatus());
        if (status == null) {
            return;
        }
        LocalDateTime updatedAt = resolveUpdatedAt(extraction);
        knowledgeBaseExtractionService.upsert(isin, status, extraction.getExtractedJson(), updatedAt);
    }

    private KnowledgeBaseExtractionStatus mapExtractionStatus(DossierExtractionStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case APPROVED, APPLIED -> KnowledgeBaseExtractionStatus.COMPLETE;
            default -> null;
        };
    }

    private LocalDateTime resolveUpdatedAt(InstrumentDossierExtraction extraction) {
        if (extraction.getAppliedAt() != null) {
            return extraction.getAppliedAt();
        }
        if (extraction.getApprovedAt() != null) {
            return extraction.getApprovedAt();
        }
        if (extraction.getCreatedAt() != null) {
            return extraction.getCreatedAt();
        }
        return LocalDateTime.now();
    }

    private String resolveIsinForExtraction(InstrumentDossierExtraction extraction) {
        if (extraction == null) {
            return null;
        }
        return dossierRepository.findById(extraction.getDossierId())
                .map(InstrumentDossier::getIsin)
                .orElse(null);
    }

    private InstrumentDossierExtractionPayload parsePayload(JsonNode extractedJson) {
        try {
            return objectMapper.treeToValue(extractedJson, InstrumentDossierExtractionPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Extraction payload could not be parsed");
        }
    }

    private boolean applyOverrides(InstrumentOverride override,
                                   InstrumentDossierExtractionPayload payload,
                                   String editedBy,
                                   boolean overwriteExisting) {
        boolean changed = false;
        changed |= updateField(override.getName(), trimToNull(payload.name()), "name", override::setName, override.getIsin(), editedBy, overwriteExisting);
        changed |= updateField(override.getInstrumentType(), trimToNull(payload.instrumentType()), "instrument_type",
                override::setInstrumentType, override.getIsin(), editedBy, overwriteExisting);
        changed |= updateField(override.getAssetClass(), trimToNull(payload.assetClass()), "asset_class",
                override::setAssetClass, override.getIsin(), editedBy, overwriteExisting);
        changed |= updateField(override.getSubClass(), trimToNull(payload.subClass()), "sub_class",
                override::setSubClass, override.getIsin(), editedBy, overwriteExisting);
        changed |= updateLayer(override, payload.layer(), editedBy, overwriteExisting);
        changed |= updateField(override.getLayerNotes(), trimToNull(payload.layerNotes()), "layer_notes",
                override::setLayerNotes, override.getIsin(), editedBy, overwriteExisting);
        return changed;
    }

    private boolean updateLayer(InstrumentOverride override, Integer layer, String editedBy, boolean overwriteExisting) {
        if (layer == null) {
            return false;
        }
        if (layer < 1 || layer > 5) {
            return false;
        }
        if (!overwriteExisting && override.getLayer() != null) {
            return false;
        }
        String oldValue = override.getLayer() == null ? null : override.getLayer().toString();
        String newValue = layer.toString();
        if (oldValue != null && oldValue.equals(newValue)) {
            return false;
        }
        override.setLayer(layer);
        override.setLayerLastChanged(LocalDate.now());
        auditService.recordEdit(override.getIsin(), "layer", oldValue, newValue, editedBy, EDIT_SOURCE);
        return true;
    }

    private boolean updateField(String oldValue, String newValue, String field,
                                java.util.function.Consumer<String> updater,
                                String isin, String editedBy, boolean overwriteExisting) {
        if (newValue == null) {
            return false;
        }
        if (!overwriteExisting && oldValue != null && !oldValue.isBlank()) {
            return false;
        }
        if (oldValue != null && oldValue.equals(newValue)) {
            return false;
        }
        updater.accept(newValue);
        auditService.recordEdit(isin, field, oldValue, newValue, editedBy, EDIT_SOURCE);
        return true;
    }

    private void applyFacts(String isin, InstrumentDossierExtractionPayload payload) {
        if (payload.etf() != null) {
            upsertFact(isin, "etf.ongoing_charges_pct", null, payload.etf().ongoingChargesPct(), "pct");
            upsertFact(isin, "etf.benchmark_index", payload.etf().benchmarkIndex(), null, null);
        }
        if (payload.risk() != null && payload.risk().summaryRiskIndicator() != null) {
            Integer value = payload.risk().summaryRiskIndicator().value();
            upsertFact(isin, "risk.summary_risk_indicator.value", null, value == null ? null : BigDecimal.valueOf(value), null);
        }
    }

    private void upsertFact(String isin, String key, String textValue, BigDecimal numValue, String unit) {
        if ((textValue == null || textValue.isBlank()) && numValue == null) {
            return;
        }
        LocalDate asOfDate = LocalDate.now();
        InstrumentFactId id = new InstrumentFactId(isin, key, asOfDate);
        InstrumentFact fact = factRepository.findById(id).orElseGet(() -> {
            InstrumentFact created = new InstrumentFact();
            created.setIsin(isin);
            created.setFactKey(key);
            created.setAsOfDate(asOfDate);
            return created;
        });
        if (textValue != null && !textValue.isBlank()) {
            fact.setFactValueText(textValue.trim());
        }
        if (numValue != null) {
            fact.setFactValueNum(numValue);
        }
        fact.setUnit(unit);
        fact.setSourceRef(EDIT_SOURCE);
        fact.setUpdatedAt(LocalDateTime.now());
        factRepository.save(fact);
    }

    private KnowledgeBaseDossierVersionDto toVersionDto(InstrumentDossier dossier) {
        if (dossier == null) {
            return null;
        }
        return new KnowledgeBaseDossierVersionDto(
                dossier.getDossierId(),
                dossier.getVersion(),
                dossier.getStatus(),
                dossier.getDisplayName(),
                dossier.getCreatedBy(),
                dossier.getOrigin(),
                dossier.getCreatedAt(),
                dossier.getUpdatedAt(),
                dossier.getApprovedAt(),
                dossier.isAutoApproved()
        );
    }

    private KnowledgeBaseRunItemDto toRunDto(KnowledgeBaseRun run) {
        if (run == null) {
            return null;
        }
        return new KnowledgeBaseRunItemDto(
                run.getRunId(),
                run.getIsin(),
                run.getAction(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getAttempts(),
                run.getError(),
                run.getBatchId(),
                run.getRequestId()
        );
    }

    private void applyDossierStatus(InstrumentDossier dossier,
                                    DossierStatus status,
                                    String approvedBy,
                                    boolean autoApproved) {
        if (status == null) {
            return;
        }
        dossier.setStatus(status);
        if (status == DossierStatus.APPROVED) {
            if (dossier.getApprovedAt() == null) {
                dossier.setApprovedAt(LocalDateTime.now());
            }
            if (approvedBy != null && !approvedBy.isBlank()) {
                dossier.setApprovedBy(approvedBy);
            }
            dossier.setAutoApproved(autoApproved);
        } else if (status == DossierStatus.REJECTED || status == DossierStatus.FAILED) {
            dossier.setAutoApproved(false);
        }
    }

    private InstrumentDossierResponseDto toResponse(InstrumentDossier dossier) {
        return new InstrumentDossierResponseDto(
                dossier.getDossierId(),
                dossier.getIsin(),
                dossier.getDisplayName(),
                dossier.getCreatedBy(),
                dossier.getOrigin(),
                dossier.getStatus(),
                dossier.getAuthoredBy(),
                dossier.getVersion(),
                dossier.getContentMd(),
                dossier.getCitationsJson(),
                dossier.getContentHash(),
                dossier.getCreatedAt(),
                dossier.getUpdatedAt(),
                dossier.getApprovedBy(),
                dossier.getApprovedAt(),
                dossier.isAutoApproved(),
                dossier.getSupersedesId()
        );
    }

    private InstrumentDossierExtractionResponseDto toResponse(InstrumentDossierExtraction extraction) {
        return new InstrumentDossierExtractionResponseDto(
                extraction.getExtractionId(),
                extraction.getDossierId(),
                extraction.getModel(),
                extraction.getExtractedJson(),
                extraction.getMissingFieldsJson(),
                extraction.getWarningsJson(),
                extraction.getStatus(),
                extraction.getError(),
                extraction.getCreatedAt(),
                extraction.getApprovedBy(),
                extraction.getApprovedAt(),
                extraction.getAppliedBy(),
                extraction.getAppliedAt(),
                extraction.isAutoApproved()
        );
    }


    private void requireInstrument(String isin) {
        Optional<Instrument> instrument = instrumentRepository.findById(isin);
        if (instrument.isEmpty()) {
            throw new IllegalArgumentException("Instrument not found");
        }
    }

    private JsonNode ensureCitations(JsonNode citations) {
        if (citations == null || citations.isNull()) {
            throw new IllegalArgumentException("Citations must be provided");
        }
        if (!citations.isArray()) {
            throw new IllegalArgumentException("Citations must be a JSON array");
        }
        return citations;
    }

    private JsonNode normalizeCitationsAllowEmpty(JsonNode citations) {
        if (citations == null || citations.isNull()) {
            return objectMapper.createArrayNode();
        }
        if (!citations.isArray()) {
            throw new IllegalArgumentException("Citations must be a JSON array");
        }
        return citations;
    }

    private String normalizeContent(String contentMd) {
        if (contentMd == null || contentMd.isBlank()) {
            throw new IllegalArgumentException("Content must be provided");
        }
        return contentMd.trim();
    }


    private String buildBulkWebsearchPrompt(List<String> isins, int maxChars) {
        String today = LocalDate.now().toString();
        StringBuilder isinList = new StringBuilder();
        for (String isin : isins) {
            isinList.append("- ").append(isin).append('\n');
        }
        return """
                You are a research assistant for financial instruments (securities). For each provided ISIN, create a dossier in English.
                
                Requirements:
                - Use web research (web_search) to find reliable primary sources (issuer/provider site, PRIIPs KID/KIID, factsheet, index provider, exchange/regulator pages; optionally justETF or similar as a secondary source).
                - Provide citations: every key claim (e.g., TER/fees, replication method, index tracked, domicile, distribution policy, SRI) must be backed by a source.
                - Do not invent data. If something cannot be verified, write "unknown" and briefly explain why.
                - Include the research date (%s) and, if available, the “data as of” date for key metrics.
                - Only add verified information to the dossiers.
                - No financial advice; informational only.
                
                Output format:
                Return a single JSON object with exactly one entry per requested ISIN:
                {
                  "items": [
                    {
                      "isin": "DE0000000000",
                      "contentMd": "Markdown dossier (string) or null when failed",
                      "displayName": "instrument name (string) or null",
                      "citations": [ { "id": "...", "title": "...", "url": "...", "publisher": "...", "accessed_at": "YYYY-MM-DD" } ],
                      "error": "short error string or null"
                    }
                  ]
                }
                	The Markdown dossier (contentMd) must follow:
                	# <ISIN> — <Name>
                	## Quick profile (table)
                	## Classification (instrument type, asset class, subclass, suggested layer per Core/Satellite)
                	## Risk (SRI and notes)
                	## Costs & structure (TER, replication, domicile, distribution, currency if relevant)
                	## Exposures (regions, sectors, top holdings/top-10, benchmark/index)
                	## Redundancy hints (qualitative; do not claim precise correlations without data)
                	## Sources (numbered list)
                
                	Additional requirements:
                	- Expected Layer definition: 1=Global-Core, 2=Core-Plus, 3=Themes, 4=Single stock.   
                	- When suggesting a layer, justify it using index breadth, concentration, thematic focus, and region/sector tilt.
                	- If possible, include the Synthetic Risk Indicator (SRI) from the PRIIPs KID.
                	- Output JSON only. Do not wrap in Markdown code fences.
                	- Provide exactly one items[] entry for each ISIN, in the same order as given.
                	- If you cannot complete an ISIN, set contentMd=null, displayName=null, citations=[], error="<reason>".
                    - Keep each dossier concise but complete (under %d characters).
                    - Single Stocks should always be classified as layer 4= Single Stock.
                    - To qualify as Layer 1 = Global-Core, an instrument must be an ETF or fund that diversifies across industries and themes worldwide, but not only across individual countries and continents. World wide diversified Core-Umbrella fonds, Core-Multi Asset-ETFs and/or Bond-ETFs are allowed in this layer, too.
                    - ETFs/Fonds focussing on instruments from single continents, countries and/or only one country/continent are NOT allowed for Layer 1!
                    - To qualify as Layer 2 = Core-Plus, an Instrument must be an ETF or fund that diversifies across industries and themes but tilts into specific regions, continents or countries. Umbrella ETFs, Multi Asset-ETFs and/or Bond-ETFs diversified over specific regions/countries/continents are allowed in this layer, too.
                    - If the choice between Layer 1 and 2 is unclear, choose layer 2.
                    - Layer 3 = Themes are ETFs and fonds covering specific themes or industries and/or not matching into layer 1 or 2. Also Multi-Asset ETfs and Umbrella fonds are allowed if they cover only specific themes or industries.
                
                
                ISINs:
                %s
                """.formatted(today, maxChars, isinList.toString().trim());
    }

    private Map<String, Object> buildBulkWebsearchSchema(ObjectMapper mapper) {
        String schema = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["items"],
                  "properties": {
                    "items": {
                      "type": "array",
                      "minItems": 1,
                      "items": {
                        "type": "object",
                        "additionalProperties": false,
                        "required": ["isin","contentMd","displayName","citations","error"],
                        "properties": {
                          "isin": { "type": "string" },
                          "contentMd": { "type": ["string","null"] },
                          "displayName": { "type": ["string","null"] },
                          "citations": {
                            "type": "array",
                            "items": {
                              "type": "object",
                              "additionalProperties": false,
                              "required": ["id","title","url","publisher","accessed_at"],
                              "properties": {
                                "id": { "type": "string" },
                                "title": { "type": "string" },
                                "url": { "type": "string" },
                                "publisher": { "type": "string" },
                                "accessed_at": { "type": "string", "format": "date" }
                              }
                            }
                          },
                          "error": { "type": ["string","null"] }
                        }
                      }
                    }
                  }
                }
                """;
        return readSchema(mapper, schema, "KB bulk websearch response");
    }

    private Map<String, Object> readSchema(ObjectMapper mapper, String schema, String label) {
        try {
            return mapper.readValue(schema, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            logger.error("Failed to load {} JSON schema", label, ex);
            throw new IllegalStateException("Failed to load " + label + " schema");
        }
    }

    private List<BulkWebsearchDraftItem> parseBulkWebsearchDrafts(String raw, List<String> requestedIsins) {
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception ex) {
            logger.error("Error parsing response {}", raw, ex);
            throw new IllegalArgumentException("LLM output is not valid JSON", ex);
        }
        JsonNode items = root.isArray() ? root : root.get("items");
        if (items == null || items.isNull()) {
            items = root.get("dossiers");
        }
        if (items == null || items.isNull() || !items.isArray()) {
            throw new IllegalArgumentException("LLM output must contain an items array");
        }
        Map<String, JsonNode> byIsin = new HashMap<>();
        for (JsonNode item : items) {
            String isin = textOrNull(item, "isin");
            if (isin == null) {
                continue;
            }
            byIsin.putIfAbsent(isin.trim().toUpperCase(Locale.ROOT), item);
        }
        List<BulkWebsearchDraftItem> results = new ArrayList<>();
        for (String requested : requestedIsins) {
            JsonNode item = byIsin.get(requested);
            if (item == null) {
                results.add(new BulkWebsearchDraftItem(requested, null, null, objectMapper.createArrayNode(), "Missing entry in LLM output"));
                continue;
            }
            String error = textOrNull(item, "error");
            if (error == null) {
                error = textOrNull(item, "failure");
            }
            String contentMd = textOrNull(item, "contentMd");
            if (contentMd == null) {
                contentMd = textOrNull(item, "content_md");
            }
            String displayName = textOrNull(item, "displayName");
            if (displayName == null) {
                displayName = textOrNull(item, "display_name");
            }
            if (displayName == null) {
                displayName = textOrNull(item, "name");
            }
            JsonNode citations = item.get("citations");
            if (citations == null || citations.isNull()) {
                citations = objectMapper.createArrayNode();
            }
            if (!citations.isArray()) {
                results.add(new BulkWebsearchDraftItem(requested, null, null, objectMapper.createArrayNode(), "Citations must be a JSON array"));
                continue;
            }
            if (error != null && !error.isBlank()) {
                results.add(new BulkWebsearchDraftItem(requested, null, null, citations, error));
                continue;
            }
            if (contentMd == null || contentMd.isBlank()) {
                results.add(new BulkWebsearchDraftItem(requested, null, null, citations, "LLM returned empty content"));
                continue;
            }
            results.add(new BulkWebsearchDraftItem(requested, contentMd.trim(), trimToNull(displayName), citations, null));
        }
        return results;
    }


    private InstrumentDossierSearchItemDto toSearchItem(InstrumentDossierSearchProjection row) {
        if (row == null) {
            return null;
        }
        boolean hasDossier = row.getDossierId() != null;
        DossierStatus dossierStatus = null;
        if (row.getDossierStatus() != null && !row.getDossierStatus().isBlank()) {
            dossierStatus = DossierStatus.valueOf(row.getDossierStatus());
        }
        DossierExtractionFreshness freshness = parseFreshness(row.getExtractionFreshness());
        return new InstrumentDossierSearchItemDto(
                row.getIsin(),
                row.getName(),
                row.getEffectiveLayer(),
                hasDossier,
                hasDossier ? row.getDossierId() : null,
                dossierStatus,
                row.getDossierUpdatedAt(),
                row.getDossierVersion(),
                row.getDossierApprovedAt(),
                Boolean.TRUE.equals(row.getHasApprovedDossier()),
                Boolean.TRUE.equals(row.getHasApprovedExtraction()),
                Boolean.TRUE.equals(row.getStale()),
                freshness
        );
    }

    private DossierExtractionFreshness parseFreshness(String raw) {
        if (raw == null || raw.isBlank()) {
            return DossierExtractionFreshness.NONE;
        }
        try {
            return DossierExtractionFreshness.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return DossierExtractionFreshness.NONE;
        }
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String hashContent(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Unable to hash dossier content", ex);
        }
    }

    private record DossierDraft(String contentMd, String displayName, JsonNode citations) {
    }

    public record BulkWebsearchDraftItem(String isin, String contentMd, String displayName, JsonNode citations,
                                         String error) {
    }

    public record BulkWebsearchDraftResult(List<BulkWebsearchDraftItem> items, String model) {
    }

    public record DossierUpsertResult(InstrumentDossierResponseDto dossier, boolean created) {
    }
}
