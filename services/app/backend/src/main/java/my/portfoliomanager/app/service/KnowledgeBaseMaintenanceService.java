package my.portfoliomanager.app.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import my.portfoliomanager.app.domain.DossierExtractionStatus;
import my.portfoliomanager.app.domain.DossierOrigin;
import my.portfoliomanager.app.domain.DossierStatus;
import my.portfoliomanager.app.domain.InstrumentDossier;
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
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
	private static final Set<String> SINGLE_STOCK_RETRY_FIELDS = Set.of(
			"valuation.eps_history",
			"eps_history",
			"valuation.price",
			"price",
			"valuation.pe_current",
			"pe_current",
			"valuation.pb_current",
			"pb_current",
			"valuation.market_cap",
			"market_cap",
			"valuation.shares_outstanding",
			"shares_outstanding"
	);
	private static final Set<String> ETF_RETRY_FIELDS = Set.of(
			"etf.ongoing_charges_pct",
			"etf.benchmark_index",
			"valuation.pe_ttm_holdings",
			"valuation.earnings_yield_ttm_holdings",
			"valuation.holdings_coverage_weight_pct",
			"valuation.holdings_coverage_count"
	);
	private static final Set<String> ETF_ISSUER_KEYWORDS = Set.of(
			"ishares",
			"blackrock",
			"vanguard",
			"spdr",
			"ssga",
			"statestreet",
			"xtrackers",
			"dws",
			"amundi",
			"lyxor",
			"invesco",
			"wisdomtree",
			"ubs",
			"pimco",
			"vaneck",
			"hanetf",
			"hsbc",
			"legalandgeneral",
			"lseg",
			"dbx"
	);
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
    public InstrumentDossierExtractionResponseDto fillMissingData(String isin,
                                      Boolean autoApprove,
                                      String actor) {
		String normalized = normalizeIsin(isin);
		KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
		boolean autoApproveFlag = autoApprove != null ? autoApprove : config.autoApprove();
		boolean applyOverrides = config.applyExtractionsToOverrides();
		KnowledgeBaseRun run = runService.startRun(normalized, KnowledgeBaseRunAction.MISSING_DATA, null, null);
		runService.incrementAttempt(run);
		try {
			InstrumentDossier dossier = dossierRepository.findFirstByIsinOrderByVersionDesc(normalized)
					.orElseThrow(() -> new IllegalArgumentException("Dossier not found"));
			InstrumentDossierExtractionResponseDto extraction = knowledgeBaseService.runExtraction(dossier.getDossierId());
			if (extraction.status() == DossierExtractionStatus.FAILED) {
				runService.markFailed(run, extraction.error());
				return extraction;
			}
			InstrumentDossierExtractionPayload payload = parseExtractionPayload(extraction.extractedJson());
			List<String> missingTargets = resolveMissingTargets(payload);
			if (missingTargets.isEmpty()) {
				runService.markSkipped(run, "no_missing_fields");
				return extraction;
			}
			PatchResult patchResult = patchMissingTargets(
					dossier,
					missingTargets,
					payload,
					autoApproveFlag,
					applyOverrides,
					actor
			);
			if (patchResult == null || patchResult.extraction() == null) {
				runService.markSkipped(run, "no_missing_fields");
				return extraction;
			}
			InstrumentDossierExtractionResponseDto patchedExtraction = patchResult.extraction();
			if (patchedExtraction.status() == DossierExtractionStatus.FAILED) {
				runService.markFailed(run, patchedExtraction.error());
				return patchedExtraction;
			}
			runService.markSucceeded(run);
			return patchedExtraction;
		} catch (CancellationException ex) {
			runService.markFailed(run, "Canceled");
			throw ex;
		} catch (Exception ex) {
			runService.markFailed(run, ex.getMessage());
			throw ex;
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
                InstrumentDossierResponseDto dossier = null;
                ExtractionFlowResult extractionResult = null;

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
                            error,
                            null
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
                        dossier = createDossierFromDraft(item.isin(), dossierDraft, actor, DossierStatus.PENDING_REVIEW);
                        dossierId = dossier.dossierId();
                        boolean requestedAutoApprove = autoApproveFlag;
                        extractionResult = runExtractionFlow(
                                item.isin(), dossier.status(), dossierId, actor, false, applyOverrides
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
                                    dossier = approved;
                                    if (approved.status() != DossierStatus.APPROVED) {
                                        error = "dossier_quality_gate_failed";
                                    } else {
                                        InstrumentDossierExtractionResponseDto extraction = knowledgeBaseService
                                                .approveExtraction(extractionId, actor, true, applyOverrides);
                                        extractionResult = new ExtractionFlowResult(extractionItem, extractionResult.payload(), extraction);
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

                KnowledgeBaseManualApprovalDto manualApproval = null;
                if (dossier != null || extractionResult != null) {
                    manualApproval = knowledgeBaseService.resolveManualApproval(
                            dossier == null ? null : dossier.status(),
                            extractionResult == null || extractionResult.extraction() == null
                                    ? null
                                    : extractionResult.extraction().status()
                    );
                }
                if (manualApproval == null) {
                    manualApproval = knowledgeBaseService.resolveManualApprovalForIsin(item.isin());
                }
                responseItems.add(new KnowledgeBaseAlternativeItemDto(
                        item.isin(),
                        item.rationale(),
                        item.citations(),
                        status,
                        dossierId,
                        extractionId,
                        error,
                        manualApproval
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
                                                   DossierStatus dossierStatus,
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
                KnowledgeBaseManualApprovalDto manualApproval = knowledgeBaseService.resolveManualApproval(dossierStatus, extraction.status());
                return new ExtractionFlowResult(
                        new KnowledgeBaseBulkResearchItemDto(isin, KnowledgeBaseBulkResearchItemStatus.FAILED, dossierId,
                                extraction.extractionId(), extraction.error(), manualApproval),
                        null,
                        extraction
                );
            }
            Long extractionId = extraction.extractionId();
            InstrumentDossierExtractionPayload payload = parseExtractionPayload(extraction.extractedJson());
            PatchResult patchResult = null;
            List<String> missingTargets = resolveMissingTargets(payload);
            if (missingTargets != null && !missingTargets.isEmpty()) {
                try {
                    InstrumentDossier baseDossier = dossierRepository.findById(dossierId).orElse(null);
					patchResult = patchMissingTargets(baseDossier, missingTargets, payload, autoApprove, applyOverrides, actor);
                } catch (Exception ex) {
                    logger.warn("Missing data patch failed for ISIN {}: {}", isin, ex.getMessage());
                }
            }
            if (patchResult != null && patchResult.extraction() != null
                    && patchResult.extraction().status() != DossierExtractionStatus.FAILED) {
                extraction = patchResult.extraction();
                extractionId = extraction.extractionId();
                payload = patchResult.payload() == null
                        ? parseExtractionPayload(extraction.extractedJson())
                        : patchResult.payload();
                if (patchResult.dossier() != null) {
                    dossierId = patchResult.dossier().dossierId();
                    dossierStatus = patchResult.dossier().status();
                }
            } else if (autoApprove) {
                extraction = knowledgeBaseService.approveExtraction(extractionId, actor, true, applyOverrides);
                extractionId = extraction.extractionId();
            }
            runService.markSucceeded(extractRun);
            KnowledgeBaseManualApprovalDto manualApproval = knowledgeBaseService.resolveManualApproval(dossierStatus, extraction.status());
            return new ExtractionFlowResult(
                    new KnowledgeBaseBulkResearchItemDto(isin, KnowledgeBaseBulkResearchItemStatus.SUCCEEDED, dossierId,
                            extractionId, null, manualApproval),
                    payload,
                    extraction
            );
        } catch (CancellationException ex) {
            runService.markFailed(extractRun, "Canceled");
            throw ex;
        } catch (Exception ex) {
            runService.markFailed(extractRun, ex.getMessage());
            return new ExtractionFlowResult(
                    new KnowledgeBaseBulkResearchItemDto(isin, KnowledgeBaseBulkResearchItemStatus.FAILED, dossierId, null,
                            messageOrFallback(ex), null),
                    null,
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

                ExtractionFlowResult extractionResult = runExtractionFlow(isin, dossier.status(), dossier.dossierId(), actor,
                        localAutoApprove, applyOverrides);
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
                        messageOrFallback(ex), null));
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
            InstrumentDossierExtractionPayload payload,
            InstrumentDossierExtractionResponseDto extraction
    ) {
    }

    private record PatchResult(
            InstrumentDossierResponseDto dossier,
            InstrumentDossierExtractionResponseDto extraction,
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

    public InstrumentDossierExtractionResponseDto patchMissingDataIfNeeded(Long dossierId,
                                                                            InstrumentDossierExtractionResponseDto extraction,
                                                                            Boolean autoApprove,
                                                                            String actor) {
        if (dossierId == null || extraction == null) {
            return extraction;
        }
        if (extraction.status() == DossierExtractionStatus.FAILED) {
            return extraction;
        }
        InstrumentDossierExtractionPayload payload = parseExtractionPayload(extraction.extractedJson());
        List<String> missingTargets = resolveMissingTargets(payload);
        if (missingTargets == null || missingTargets.isEmpty()) {
            return extraction;
        }
        InstrumentDossier baseDossier = dossierRepository.findById(dossierId).orElse(null);
        if (baseDossier == null) {
            return extraction;
        }
        KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
        boolean autoApproveFlag = autoApprove != null ? autoApprove : config.autoApprove();
        boolean applyOverrides = config.applyExtractionsToOverrides();
		try {
			PatchResult patchResult = patchMissingTargets(baseDossier, missingTargets, payload, autoApproveFlag, applyOverrides, actor);
			if (patchResult == null || patchResult.extraction() == null) {
				return extraction;
			}
            if (patchResult.extraction().status() == DossierExtractionStatus.FAILED) {
                return extraction;
            }
            return patchResult.extraction();
        } catch (Exception ex) {
            logger.warn("Missing data patch failed for dossier {}: {}", dossierId, ex.getMessage());
            return extraction;
        }
    }

	private PatchResult patchMissingTargets(InstrumentDossier baseDossier,
									 List<String> missingTargets,
									 InstrumentDossierExtractionPayload payload,
									 boolean autoApproveFlag,
									 boolean applyOverrides,
									 String actor) {
		if (baseDossier == null || missingTargets == null || missingTargets.isEmpty()) {
			return null;
		}
		PatchResult first = patchOnce(
				baseDossier,
				missingTargets,
				payload,
				false,
				autoApproveFlag,
				applyOverrides,
				actor,
				configService.getSnapshot().websearchAllowedDomains()
		);
		if (first == null || first.extraction() == null) {
			return first;
		}
		InstrumentDossierExtractionPayload firstPayload = first.payload() == null
				? parseExtractionPayload(first.extraction().extractedJson())
				: first.payload();
		List<String> remaining = resolveMissingTargets(firstPayload);
		if (!shouldRetryForMissing(firstPayload, remaining)) {
			return first;
		}
		InstrumentDossier retryBase = baseDossier;
		if (first.dossier() != null) {
			retryBase = dossierRepository.findById(first.dossier().dossierId()).orElse(baseDossier);
		}
		List<String> retryAllowedDomains = buildRetryAllowedDomains(firstPayload, retryBase, first);
		PatchResult second = patchOnce(
				retryBase,
				remaining,
				firstPayload,
				true,
				autoApproveFlag,
				applyOverrides,
				actor,
				retryAllowedDomains
		);
		if (second == null || second.extraction() == null) {
			return first;
		}
		if (second.extraction().status() == DossierExtractionStatus.FAILED) {
			return first;
		}
		return second;
	}

	private PatchResult patchOnce(InstrumentDossier baseDossier,
									 List<String> missingTargets,
									 InstrumentDossierExtractionPayload payload,
									 boolean retryMode,
									 boolean autoApproveFlag,
									 boolean applyOverrides,
									 String actor,
									 List<String> allowedDomains) {
		if (baseDossier == null || missingTargets == null || missingTargets.isEmpty()) {
			return null;
		}
		KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
		String context = buildPatchContext(payload, retryMode);
		List<String> resolvedDomains = allowedDomains == null || allowedDomains.isEmpty()
				? config.websearchAllowedDomains()
				: allowedDomains;
		KnowledgeBaseLlmDossierDraft patchDraft = llmClient.patchDossierMissingFields(
				baseDossier.getIsin(),
				baseDossier.getContentMd(),
				baseDossier.getCitationsJson(),
				missingTargets,
				context,
				resolvedDomains,
				config.dossierMaxChars()
		);
		JsonNode mergedCitations = mergeCitations(baseDossier.getCitationsJson(), patchDraft.citations());
		String displayName = patchDraft.displayName() == null ? baseDossier.getDisplayName() : patchDraft.displayName();
		KnowledgeBaseLlmDossierDraft mergedDraft = new KnowledgeBaseLlmDossierDraft(
				patchDraft.contentMd(),
				displayName,
				mergedCitations,
				patchDraft.model()
		);
		InstrumentDossierResponseDto patchedDossier = createDossierFromDraft(
				baseDossier.getIsin(),
				mergedDraft,
				actor,
				DossierStatus.PENDING_REVIEW
		);
		if (autoApproveFlag) {
			patchedDossier = knowledgeBaseService.approveDossier(patchedDossier.dossierId(), actor, true);
		}
		InstrumentDossierExtractionResponseDto patchedExtraction = knowledgeBaseService.runExtraction(patchedDossier.dossierId());
		if (patchedExtraction.status() == DossierExtractionStatus.FAILED) {
			return new PatchResult(patchedDossier, patchedExtraction, null);
		}
		if (autoApproveFlag) {
			patchedExtraction = knowledgeBaseService.approveExtraction(
					patchedExtraction.extractionId(),
					actor,
					true,
					applyOverrides
			);
		}
		InstrumentDossierExtractionPayload patchedPayload = parseExtractionPayload(patchedExtraction.extractedJson());
		return new PatchResult(patchedDossier, patchedExtraction, patchedPayload);
	}

	private boolean shouldRetryForMissing(InstrumentDossierExtractionPayload payload, List<String> missingTargets) {
		if (payload == null || missingTargets == null || missingTargets.isEmpty()) {
			return false;
		}
		boolean singleStock = isSingleStockType(payload.instrumentType(), payload.layer());
		boolean etf = isEtfType(payload.instrumentType());
		if (!singleStock && !etf) {
			return false;
		}
		for (String field : missingTargets) {
			if (field == null || field.isBlank()) {
				continue;
			}
			String normalized = field.trim().toLowerCase(Locale.ROOT);
			if (singleStock && SINGLE_STOCK_RETRY_FIELDS.contains(normalized)) {
				return true;
			}
			if (etf && ETF_RETRY_FIELDS.contains(normalized)) {
				return true;
			}
			if (normalized.startsWith("valuation.")) {
				String stripped = normalized.substring("valuation.".length());
				if (singleStock && SINGLE_STOCK_RETRY_FIELDS.contains(stripped)) {
					return true;
				}
				if (etf && ETF_RETRY_FIELDS.contains(stripped)) {
					return true;
				}
			}
		}
		return false;
	}

	private String buildPatchContext(InstrumentDossierExtractionPayload payload, boolean retryMode) {
		if (payload == null) {
			return retryMode ? "Retry mode: true" : null;
		}
		StringBuilder context = new StringBuilder();
		if (payload.name() != null && !payload.name().isBlank()) {
			context.append("Instrument name: ").append(payload.name().trim()).append('\n');
		}
		if (payload.instrumentType() != null && !payload.instrumentType().isBlank()) {
			context.append("Instrument type: ").append(payload.instrumentType().trim()).append('\n');
		}
		if (payload.assetClass() != null && !payload.assetClass().isBlank()) {
			context.append("Asset class: ").append(payload.assetClass().trim()).append('\n');
		}
		if (payload.layer() != null) {
			context.append("Layer: ").append(payload.layer()).append('\n');
		}
		boolean singleStock = isSingleStockType(payload.instrumentType(), payload.layer());
		context.append("Single stock: ").append(singleStock ? "true" : "false").append('\n');
		boolean etf = isEtfType(payload.instrumentType());
		context.append("ETF: ").append(etf ? "true" : "false").append('\n');
		if (retryMode) {
			context.append("Retry mode: true\n");
			context.append("Use alternative sources if primary sources miss valuation metrics.\n");
		}
		return context.toString().trim();
	}

	private List<String> buildRetryAllowedDomains(InstrumentDossierExtractionPayload payload,
									 InstrumentDossier baseDossier,
									 PatchResult firstAttempt) {
		KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
		LinkedHashSet<String> domains = new LinkedHashSet<>(config.websearchAllowedDomains());
		JsonNode citations = null;
		if (firstAttempt != null && firstAttempt.dossier() != null && firstAttempt.dossier().citations() != null) {
			citations = firstAttempt.dossier().citations();
		} else if (baseDossier != null) {
			citations = baseDossier.getCitationsJson();
		}
		domains.addAll(extractIssuerDomains(payload, baseDossier, citations));
		return List.copyOf(domains);
	}

	private Set<String> extractIssuerDomains(InstrumentDossierExtractionPayload payload,
									 InstrumentDossier baseDossier,
									 JsonNode citations) {
		Set<String> domains = new LinkedHashSet<>();
		if (citations == null || !citations.isArray()) {
			return domains;
		}
		boolean singleStock = payload != null && isSingleStockType(payload.instrumentType(), payload.layer());
		boolean etf = payload != null && isEtfType(payload.instrumentType());
		if (!singleStock && !etf) {
			return domains;
		}
		String name = payload != null && payload.name() != null ? payload.name() : null;
		if ((name == null || name.isBlank()) && baseDossier != null) {
			name = baseDossier.getDisplayName();
		}
		Set<String> nameTokens = normalizeNameTokens(name);
		Set<String> issuerTokens = new LinkedHashSet<>(nameTokens);
		if (etf) {
			issuerTokens.addAll(ETF_ISSUER_KEYWORDS);
		}
		for (JsonNode item : citations) {
			if (item == null || !item.isObject()) {
				continue;
			}
			String url = textOrNull(item, "url");
			String host = extractHost(url);
			if (host == null) {
				continue;
			}
			String publisher = textOrNull(item, "publisher");
			String title = textOrNull(item, "title");
			if (matchesIssuerDomain(host, issuerTokens, publisher, title)) {
				domains.add(host);
			}
		}
		return domains;
	}

	private boolean matchesIssuerDomain(String host,
									 Set<String> issuerTokens,
									 String publisher,
									 String title) {
		if (host == null || host.isBlank()) {
			return false;
		}
		String hostLower = host.toLowerCase(Locale.ROOT);
		if (containsAnyToken(hostLower, issuerTokens)) {
			return true;
		}
		String combined = (publisher == null ? "" : publisher) + " " + (title == null ? "" : title);
		return containsAnyToken(combined.toLowerCase(Locale.ROOT), issuerTokens);
	}

	private boolean containsAnyToken(String value, Set<String> tokens) {
		if (value == null || value.isBlank() || tokens == null || tokens.isEmpty()) {
			return false;
		}
		for (String token : tokens) {
			if (token != null && !token.isBlank() && value.contains(token)) {
				return true;
			}
		}
		return false;
	}

	private Set<String> normalizeNameTokens(String name) {
		if (name == null) {
			return Set.of();
		}
		String normalized = name.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9]+", " ")
				.trim();
		if (normalized.isBlank()) {
			return Set.of();
		}
		Set<String> tokens = new LinkedHashSet<>();
		for (String token : normalized.split("\\s+")) {
			if (token.length() >= 3) {
				tokens.add(token);
			}
		}
		return tokens;
	}

	private String extractHost(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		String trimmed = url.trim();
		try {
			URI uri = new URI(trimmed);
			String host = uri.getHost();
			if (host == null) {
				uri = new URI("https://" + trimmed);
				host = uri.getHost();
			}
			if (host == null) {
				return null;
			}
			String normalized = host.toLowerCase(Locale.ROOT);
			if (normalized.startsWith("www.")) {
				normalized = normalized.substring(4);
			}
			return normalized;
		} catch (Exception ex) {
			return null;
		}
	}

	private boolean isEtfType(String instrumentType) {
		if (instrumentType == null || instrumentType.isBlank()) {
			return false;
		}
		String normalized = instrumentType.toLowerCase(Locale.ROOT);
		return normalized.contains("etf") || normalized.contains("exchange traded fund");
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

	private List<String> resolveMissingTargets(InstrumentDossierExtractionPayload payload) {
		if (payload == null) {
			return List.of();
		}
		java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
		List<InstrumentDossierExtractionPayload.MissingFieldPayload> missingFields = payload.missingFields();
		if (missingFields != null) {
			for (InstrumentDossierExtractionPayload.MissingFieldPayload item : missingFields) {
				if (item == null || item.field() == null) {
					continue;
				}
				String field = item.field().trim();
				if (!field.isBlank()) {
					targets.add(field);
				}
			}
		}
		InstrumentDossierExtractionPayload.ValuationPayload valuation = payload.valuation();
		boolean singleStock = isSingleStockType(payload.instrumentType(), payload.layer());
		if (singleStock && valuation != null) {
			if (valuation.epsHistory() == null || valuation.epsHistory().isEmpty()) {
				targets.add("valuation.eps_history");
			}
			if (valuation.price() == null) {
				targets.add("valuation.price");
			}
			if (valuation.peCurrent() == null) {
				targets.add("valuation.pe_current");
			}
			if (valuation.pbCurrent() == null) {
				targets.add("valuation.pb_current");
			}
		}
		return List.copyOf(targets);
	}

	private boolean isSingleStockType(String instrumentType, Integer layer) {
		if (layer != null && layer == 4) {
			return true;
		}
		if (instrumentType == null || instrumentType.isBlank()) {
			return false;
		}
		String normalized = instrumentType.toLowerCase(Locale.ROOT);
		return normalized.contains("equity") || normalized.contains("stock") || normalized.contains("reit") || normalized.contains("share");
	}

	private JsonNode mergeCitations(JsonNode existing, JsonNode patch) {
		ArrayNode merged = objectMapper.createArrayNode();
		java.util.Set<String> seen = new java.util.HashSet<>();
		addCitations(merged, seen, existing);
		addCitations(merged, seen, patch);
		return merged;
	}

	private void addCitations(ArrayNode merged, java.util.Set<String> seen, JsonNode node) {
		if (node == null || !node.isArray()) {
			return;
		}
		for (JsonNode item : node) {
			if (item == null || !item.isObject()) {
				continue;
			}
			String key = citationKey(item);
			if (seen.add(key)) {
				merged.add(item);
			}
		}
	}

	private String citationKey(JsonNode node) {
		String url = textOrNull(node, "url");
		if (url != null) {
			return "url:" + url.toLowerCase(Locale.ROOT);
		}
		String id = textOrNull(node, "id");
		if (id != null) {
			return "id:" + id.toLowerCase(Locale.ROOT);
		}
		return node.toString();
	}

	private String textOrNull(JsonNode node, String field) {
		if (node == null || field == null) {
			return null;
		}
		JsonNode value = node.get(field);
		if (value == null || value.isNull() || !value.isTextual()) {
			return null;
		}
		String trimmed = value.asText().trim();
		return trimmed.isBlank() ? null : trimmed;
	}
}
