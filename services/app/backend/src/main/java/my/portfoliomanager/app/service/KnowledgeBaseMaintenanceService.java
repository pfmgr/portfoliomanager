package my.portfoliomanager.app.service;

import tools.jackson.databind.JsonNode;
import my.portfoliomanager.app.domain.DossierExtractionStatus;
import my.portfoliomanager.app.domain.DossierOrigin;
import my.portfoliomanager.app.domain.DossierStatus;
import my.portfoliomanager.app.domain.KnowledgeBaseAlternative;
import my.portfoliomanager.app.domain.KnowledgeBaseAlternativeStatus;
import my.portfoliomanager.app.domain.KnowledgeBaseRun;
import my.portfoliomanager.app.domain.KnowledgeBaseRunAction;
import my.portfoliomanager.app.dto.*;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmAlternativeItem;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmAlternativesDraft;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmClient;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmDossierDraft;
import my.portfoliomanager.app.repository.InstrumentDossierRepository;
import my.portfoliomanager.app.repository.KnowledgeBaseAlternativeRepository;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

@Service
public class KnowledgeBaseMaintenanceService {
    private static final Pattern ISIN_RE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseMaintenanceService.class);
    private final KnowledgeBaseConfigService configService;
    private final KnowledgeBaseLlmClient llmClient;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseRunService runService;
    private final InstrumentDossierRepository dossierRepository;
    private final KnowledgeBaseAlternativeRepository alternativeRepository;
    private final KnowledgeBaseExtractionService knowledgeBaseExtractionService;
    private final KnowledgeBaseQualityGateService qualityGateService;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseMaintenanceService(KnowledgeBaseConfigService configService,
                                           KnowledgeBaseLlmClient llmClient,
                                           KnowledgeBaseService knowledgeBaseService,
                                           KnowledgeBaseRunService runService,
                                           InstrumentDossierRepository dossierRepository,
                                           KnowledgeBaseAlternativeRepository alternativeRepository,
                                           KnowledgeBaseExtractionService knowledgeBaseExtractionService,
                                           KnowledgeBaseQualityGateService qualityGateService,
                                           ObjectMapper objectMapper) {
        this.configService = configService;
        this.llmClient = llmClient;
        this.knowledgeBaseService = knowledgeBaseService;
        this.runService = runService;
        this.dossierRepository = dossierRepository;
        this.alternativeRepository = alternativeRepository;
        this.knowledgeBaseExtractionService = knowledgeBaseExtractionService;
        this.qualityGateService = qualityGateService;
        this.objectMapper = objectMapper;
    }

    public KnowledgeBaseBulkResearchResponseDto bulkResearch(List<String> isins,
                                                             Boolean autoApprove,
                                                             Boolean applyToOverrides,
                                                             String actor) {
        List<String> normalized = normalizeIsins(isins);
        KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
        if (normalized.size() > config.maxInstrumentsPerRun()) {
            throw new IllegalArgumentException("Too many ISINs (max " + config.maxInstrumentsPerRun() + ")");
        }
        boolean autoApproveFlag = autoApprove != null ? autoApprove : config.autoApprove();
        boolean applyOverrides = applyToOverrides != null && applyToOverrides && config.applyExtractionsToOverrides();
        String batchId = UUID.randomUUID().toString();

        int batchSize = Math.max(1, config.batchSizeInstruments());
        int maxParallelBatches = Math.max(1, config.maxParallelBulkBatches());
        List<List<String>> batches = partition(normalized, batchSize);
        if (batches.size() <= 1 || maxParallelBatches <= 1) {
            BatchResult result = processBatch(normalized, autoApproveFlag, applyOverrides, actor, batchId, config);
            return toBulkResponse(normalized.size(), List.of(result));
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(maxParallelBatches, batches.size()));
        List<Future<BatchResult>> futures = new ArrayList<>();
        try {
            for (List<String> batch : batches) {
                futures.add(executor.submit(() -> processBatch(batch, autoApproveFlag, applyOverrides, actor, batchId, config)));
            }
            List<BatchResult> results = new ArrayList<>();
            for (Future<BatchResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof CancellationException cancel) {
                        throw cancel;
                    }
                    if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new RuntimeException(cause);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Canceled");
                }
            }
            return toBulkResponse(normalized.size(), results);
        } finally {
            executor.shutdownNow();
        }
    }

    @Transactional
    public KnowledgeBaseAlternativesResponseDto findAlternatives(String baseIsin,
                                                                 Boolean autoApprove,
                                                                 String actor) {
        return findAlternatives(baseIsin, autoApprove, actor, Set.of());
    }

    @Transactional
    public KnowledgeBaseAlternativesResponseDto findAlternatives(String baseIsin,
                                                                 Boolean autoApprove,
                                                                 String actor,
                                                                 Set<String> blockedIsins) {
        String normalizedBase = normalizeIsin(baseIsin);
        KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
        boolean autoApproveFlag = autoApprove != null ? autoApprove : config.autoApprove();
        boolean applyOverrides = config.applyExtractionsToOverrides();

        KnowledgeBaseRun baseRun = runService.startRun(normalizedBase, KnowledgeBaseRunAction.ALTERNATIVES, null, null);
        runService.incrementAttempt(baseRun);
        List<KnowledgeBaseAlternativeItemDto> responseItems = new ArrayList<>();
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Canceled");
            }
            KnowledgeBaseLlmAlternativesDraft draft = llmClient.findAlternatives(normalizedBase, config.websearchAllowedDomains());
            for (KnowledgeBaseLlmAlternativeItem item : draft.items()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Canceled");
                }
                KnowledgeBaseAlternative alternative = upsertAlternative(normalizedBase, item);
                KnowledgeBaseAlternativeStatus status = alternative.getStatus();
                Long dossierId = null;
                Long extractionId = null;
                String error = null;

                KnowledgeBaseRun dossierRun = runService.startRun(item.isin(), KnowledgeBaseRunAction.BULK_CREATE, null, null);
                runService.incrementAttempt(dossierRun);
                if (blockedIsins != null && blockedIsins.contains(item.isin())) {
                    status = KnowledgeBaseAlternativeStatus.FAILED;
                    error = "already_running";
                    alternative.setStatus(status);
                    alternativeRepository.save(alternative);
                    runService.markFailed(dossierRun, "already_running");
                    responseItems.add(new KnowledgeBaseAlternativeItemDto(
                            item.isin(),
                            item.rationale(),
                            item.citations(),
                            status,
                            dossierId,
                            extractionId,
                            error
                    ));
                    continue;
                }
                boolean hasDossier = dossierRepository.findFirstByIsinOrderByVersionDesc(item.isin()).isPresent();
                if (hasDossier) {
                    status = KnowledgeBaseAlternativeStatus.EXISTS;
                    alternative.setStatus(status);
                    alternativeRepository.save(alternative);
                    runService.markSkipped(dossierRun, "Dossier exists");
                } else {
                    try {
                        KnowledgeBaseLlmDossierDraft dossierDraft = llmClient.generateDossier(
                                item.isin(),
                                null,
                                config.websearchAllowedDomains(),
                                config.dossierMaxChars()
                        );
                        InstrumentDossierResponseDto dossier = createDossierFromDraft(item.isin(), dossierDraft, actor, DossierStatus.PENDING_REVIEW);
                        dossierId = dossier.dossierId();
                        boolean requestedAutoApprove = autoApproveFlag;
                        ExtractionFlowResult extractionResult = runExtractionFlow(
                                item.isin(), dossierId, actor, false, applyOverrides
                        );
                        KnowledgeBaseBulkResearchItemDto extractionItem = extractionResult.item();
                        extractionId = extractionItem.extractionId();
                        if (extractionItem.status() == KnowledgeBaseBulkResearchItemStatus.FAILED) {
                            status = KnowledgeBaseAlternativeStatus.FAILED;
                            error = extractionItem.error();
                        } else {
                            if (requestedAutoApprove) {
                                KnowledgeBaseQualityGateService.SimilarityResult similarity =
                                        evaluateAlternativeSimilarity(normalizedBase, extractionResult.payload(), config);
                                if (!similarity.passed()) {
                                    error = "similarity_gate_failed";
                                } else {
                                    InstrumentDossierResponseDto approved = knowledgeBaseService.approveDossier(dossierId, actor, true);
                                    if (approved.status() != DossierStatus.APPROVED) {
                                        error = "dossier_quality_gate_failed";
                                    } else {
                                        knowledgeBaseService.approveExtraction(extractionId, actor, true, applyOverrides);
                                    }
                                }
                            }
                            status = KnowledgeBaseAlternativeStatus.GENERATED;
                        }
                        alternative.setStatus(status);
                        alternativeRepository.save(alternative);
                        runService.markSucceeded(dossierRun);
                    } catch (CancellationException ex) {
                        runService.markFailed(dossierRun, "Canceled");
                        throw ex;
                    } catch (Exception ex) {
                        status = KnowledgeBaseAlternativeStatus.FAILED;
                        error = messageOrFallback(ex);
                        alternative.setStatus(status);
                        alternativeRepository.save(alternative);
                        runService.markFailed(dossierRun, ex.getMessage());
                    }
                }

                responseItems.add(new KnowledgeBaseAlternativeItemDto(
                        item.isin(),
                        item.rationale(),
                        item.citations(),
                        status,
                        dossierId,
                        extractionId,
                        error
                ));
            }
            runService.markSucceeded(baseRun);
        } catch (CancellationException ex) {
            runService.markFailed(baseRun, "Canceled");
            throw ex;
        } catch (Exception ex) {
            runService.markFailed(baseRun, ex.getMessage());
            throw ex;
        }
        return new KnowledgeBaseAlternativesResponseDto(normalizedBase, responseItems);
    }

    private ExtractionFlowResult runExtractionFlow(String isin,
                                                   Long dossierId,
                                                   String actor,
                                                   boolean autoApprove,
                                                   boolean applyOverrides) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Canceled");
        }
        KnowledgeBaseRun extractRun = runService.startRun(isin, KnowledgeBaseRunAction.EXTRACT, null, null);
        runService.incrementAttempt(extractRun);
        try {
            InstrumentDossierExtractionResponseDto extraction = knowledgeBaseService.runExtraction(dossierId);
            if (extraction.status() == DossierExtractionStatus.FAILED) {
                runService.markFailed(extractRun, extraction.error());
                return new ExtractionFlowResult(
                        new KnowledgeBaseBulkResearchItemDto(isin, KnowledgeBaseBulkResearchItemStatus.FAILED, dossierId,
                                extraction.extractionId(), extraction.error()),
                        null
                );
            }
            Long extractionId = extraction.extractionId();
            InstrumentDossierExtractionPayload payload = parseExtractionPayload(extraction.extractedJson());
            if (autoApprove) {
                extraction = knowledgeBaseService.approveExtraction(extractionId, actor, true, applyOverrides);
                extractionId = extraction.extractionId();
            }
            runService.markSucceeded(extractRun);
            return new ExtractionFlowResult(
                    new KnowledgeBaseBulkResearchItemDto(isin, KnowledgeBaseBulkResearchItemStatus.SUCCEEDED, dossierId,
                            extractionId, null),
                    payload
            );
        } catch (CancellationException ex) {
            runService.markFailed(extractRun, "Canceled");
            throw ex;
        } catch (Exception ex) {
            runService.markFailed(extractRun, ex.getMessage());
            return new ExtractionFlowResult(
                    new KnowledgeBaseBulkResearchItemDto(isin, KnowledgeBaseBulkResearchItemStatus.FAILED, dossierId, null,
                            messageOrFallback(ex)),
                    null
            );
        }
    }

    private BatchResult processBatch(List<String> isins,
                                     boolean autoApproveFlag,
                                     boolean applyOverrides,
                                     String actor,
                                     String batchId,
                                     KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config) {
        List<KnowledgeBaseBulkResearchItemDto> items = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        logger.info("Processing batch for {} ISINS", isins.size());
        for (String isin : isins) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Canceled");
            }
            logger.info("Start KnowledgeBaseRun for ISIN {}", isin);
            KnowledgeBaseRun dossierRun = runService.startRun(isin, KnowledgeBaseRunAction.BULK_CREATE, batchId, null);
            runService.incrementAttempt(dossierRun);
            try {
                KnowledgeBaseLlmDossierDraft draft = llmClient.generateDossier(
                        isin,
                        null,
                        config.websearchAllowedDomains(),
                        config.dossierMaxChars()
                );
                logger.info("Generated draft for ISIN {}",isin);
                InstrumentDossierResponseDto dossier = createDossierFromDraft(isin, draft, actor, DossierStatus.PENDING_REVIEW);
                logger.info("Created dossier from draft for ISIN {}",isin);
                boolean localAutoApprove = autoApproveFlag;
                if (localAutoApprove) {
                    dossier = knowledgeBaseService.approveDossier(dossier.dossierId(), actor, true);
                    if (dossier.status() == DossierStatus.APPROVED) {
                        logger.info("Auto-Approved dossier for ISIN {}",isin);
                    } else {
                        logger.info("Auto-approve dossier gate blocked for ISIN {}", isin);
                        localAutoApprove = false;
                    }
                }
                runService.markSucceeded(dossierRun);

                ExtractionFlowResult extractionResult = runExtractionFlow(isin, dossier.dossierId(), actor, localAutoApprove, applyOverrides);
                KnowledgeBaseBulkResearchItemDto extractionItem = extractionResult.item();
                if (extractionItem.status() == KnowledgeBaseBulkResearchItemStatus.FAILED) {
                    logger.info("Dossier extraction for ISIN {} failed",isin);
                    failed++;
                } else {
                    logger.info("Dossier extraction for ISIN {} succeeded",isin);
                    succeeded++;
                }
                items.add(extractionItem);
            } catch (CancellationException ex) {
                runService.markFailed(dossierRun, "Canceled");
                throw ex;
            } catch (Exception ex) {
                runService.markFailed(dossierRun, ex.getMessage());
                items.add(new KnowledgeBaseBulkResearchItemDto(isin, KnowledgeBaseBulkResearchItemStatus.FAILED, null, null,
                        messageOrFallback(ex)));
                failed++;
            }
        }
        return new BatchResult(items, succeeded, skipped, failed);
    }

    private KnowledgeBaseBulkResearchResponseDto toBulkResponse(int total, List<BatchResult> results) {
        List<KnowledgeBaseBulkResearchItemDto> items = new ArrayList<>();
        int succeeded = 0;
        int skipped = 0;
        int failed = 0;
        for (BatchResult result : results) {
            items.addAll(result.items());
            succeeded += result.succeeded();
            skipped += result.skipped();
            failed += result.failed();
        }
        return new KnowledgeBaseBulkResearchResponseDto(total, succeeded, skipped, failed, items);
    }

    private KnowledgeBaseQualityGateService.SimilarityResult evaluateAlternativeSimilarity(
            String baseIsin,
            InstrumentDossierExtractionPayload alternativePayload,
            KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config) {
        InstrumentDossierExtractionPayload basePayload = knowledgeBaseExtractionService.findPayload(baseIsin);
        if (basePayload == null || alternativePayload == null) {
            return new KnowledgeBaseQualityGateService.SimilarityResult(false, 0.0, List.of("missing_similarity_payload"));
        }
        double threshold = config == null ? 0.6 : config.alternativesMinSimilarityScore();
        return qualityGateService.evaluateSimilarity(basePayload, alternativePayload, threshold);
    }

    private InstrumentDossierExtractionPayload parseExtractionPayload(JsonNode extractedJson) {
        if (extractedJson == null || extractedJson.isNull()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(extractedJson, InstrumentDossierExtractionPayload.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<List<String>> partition(List<String> values, int batchSize) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        int size = Math.max(1, batchSize);
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < values.size(); i += size) {
            batches.add(List.copyOf(values.subList(i, Math.min(values.size(), i + size))));
        }
        return batches;
    }

    private record BatchResult(
            List<KnowledgeBaseBulkResearchItemDto> items,
            int succeeded,
            int skipped,
            int failed
    ) {
    }

    private record ExtractionFlowResult(
            KnowledgeBaseBulkResearchItemDto item,
            InstrumentDossierExtractionPayload payload
    ) {
    }

    private InstrumentDossierResponseDto createDossierFromDraft(String isin,
                                                                KnowledgeBaseLlmDossierDraft draft,
                                                                String actor,
                                                                DossierStatus status) {
        InstrumentDossierCreateRequest request = new InstrumentDossierCreateRequest(
                isin,
                draft.displayName(),
                draft.contentMd(),
                DossierOrigin.LLM_WEBSEARCH,
                status,
                draft.citations()
        );
        return knowledgeBaseService.createDossier(request, actor);
    }

    private KnowledgeBaseAlternative upsertAlternative(String baseIsin, KnowledgeBaseLlmAlternativeItem item) {
        KnowledgeBaseAlternative alternative = alternativeRepository.findByBaseIsinAndAltIsin(baseIsin, item.isin())
                .orElseGet(KnowledgeBaseAlternative::new);
        alternative.setBaseIsin(baseIsin);
        alternative.setAltIsin(item.isin());
        alternative.setRationale(item.rationale());
        alternative.setSourcesJson(item.citations());
        alternative.setStatus(KnowledgeBaseAlternativeStatus.PROPOSED);
        if (alternative.getCreatedAt() == null) {
            alternative.setCreatedAt(LocalDateTime.now());
        }
        return alternativeRepository.save(alternative);
    }

    private List<String> normalizeIsins(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("At least one ISIN is required");
        }
        Set<String> seen = new HashSet<>();
        List<String> normalized = new ArrayList<>();
        for (String raw : values) {
            String isin = normalizeIsin(raw);
            if (seen.add(isin)) {
                normalized.add(isin);
            }
        }
        return normalized;
    }

    private String normalizeIsin(String isin) {
        String trimmed = isin == null ? "" : isin.trim().toUpperCase(Locale.ROOT);
        if (!ISIN_RE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid ISIN: " + trimmed);
        }
        return trimmed;
    }

    private String messageOrFallback(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }
}
