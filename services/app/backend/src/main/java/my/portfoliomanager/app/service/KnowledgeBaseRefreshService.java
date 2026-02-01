package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.DossierExtractionStatus;
import my.portfoliomanager.app.domain.DossierOrigin;
import my.portfoliomanager.app.domain.DossierStatus;
import my.portfoliomanager.app.domain.KnowledgeBaseRun;
import my.portfoliomanager.app.domain.KnowledgeBaseRunAction;
import my.portfoliomanager.app.domain.KnowledgeBaseRunStatus;
import my.portfoliomanager.app.dto.*;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmClient;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmDossierDraft;
import my.portfoliomanager.app.repository.InstrumentDossierRepository;
import my.portfoliomanager.app.repository.projection.InstrumentDossierSearchProjection;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

@Service
public class KnowledgeBaseRefreshService {
	private static final Pattern ISIN_RE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");
	private final KnowledgeBaseConfigService configService;
	private final KnowledgeBaseLlmClient llmClient;
	private final KnowledgeBaseService knowledgeBaseService;
	private final KnowledgeBaseRunService runService;
	private final InstrumentDossierRepository dossierRepository;
	private final KnowledgeBaseBatchPlanner batchPlanner = new KnowledgeBaseBatchPlanner();

	public KnowledgeBaseRefreshService(KnowledgeBaseConfigService configService,
									   KnowledgeBaseLlmClient llmClient,
									   KnowledgeBaseService knowledgeBaseService,
									   KnowledgeBaseRunService runService,
									   InstrumentDossierRepository dossierRepository) {
		this.configService = configService;
		this.llmClient = llmClient;
		this.knowledgeBaseService = knowledgeBaseService;
		this.runService = runService;
		this.dossierRepository = dossierRepository;
	}

	public KnowledgeBaseRefreshItemDto refreshSingle(String isin, Boolean autoApprove, String actor) {
		return refreshSingle(isin, autoApprove, actor, Set.of());
	}

	public KnowledgeBaseRefreshItemDto refreshSingle(String isin,
													 Boolean autoApprove,
													 String actor,
													 Set<String> blockedIsins) {
		String normalized = normalizeIsin(isin);
		KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
		runService.markTimedOutRuns(Duration.ofMinutes(config.runTimeoutMinutes()));
		boolean autoApproveFlag = autoApprove != null ? autoApprove : config.autoApprove();
		boolean applyOverrides = config.applyExtractionsToOverrides();
        if (blockedIsins != null && blockedIsins.contains(normalized)) {
            return new KnowledgeBaseRefreshItemDto(normalized, KnowledgeBaseBulkResearchItemStatus.SKIPPED, null, null, "already_running", null);
        }
        return runRefreshForIsin(normalized, autoApproveFlag, applyOverrides, actor, null);
    }

	public KnowledgeBaseRefreshBatchResponseDto refreshBatch(KnowledgeBaseRefreshBatchRequestDto request, String actor) {
		return refreshBatch(request, actor, Set.of());
	}

	public KnowledgeBaseRefreshBatchResponseDto refreshBatch(KnowledgeBaseRefreshBatchRequestDto request,
															 String actor,
															 Set<String> blockedIsins) {
		KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
		runService.markTimedOutRuns(Duration.ofMinutes(config.runTimeoutMinutes()));

		int limit = request != null && request.limit() != null ? request.limit() : config.maxInstrumentsPerRun();
		int batchSize = request != null && request.batchSize() != null ? request.batchSize() : config.batchSizeInstruments();
		boolean dryRun = request != null && request.dryRun() != null && request.dryRun();
		List<String> candidates = resolveCandidates(request, config, limit);
		List<List<String>> batches = batchPlanner.buildBatches(
				candidates,
				batchSize,
				config.batchMaxInputChars(),
				this::estimateInputChars
		);

		int maxBatches = config.maxBatchesPerRun();
		int maxInstruments = config.maxInstrumentsPerRun();
		List<KnowledgeBaseRefreshItemDto> items = new ArrayList<>();
		int processed = 0;
		int succeeded = 0;
		int failed = 0;
		int skipped = 0;

		String batchId = UUID.randomUUID().toString();

		for (int i = 0; i < batches.size() && i < maxBatches; i++) {
			List<String> batch = batches.get(i);
			for (String isin : batch) {
				if (processed >= maxInstruments) {
					break;
				}
				if (Thread.currentThread().isInterrupted()) {
					throw new CancellationException("Canceled");
				}
                if (blockedIsins != null && blockedIsins.contains(isin)) {
                    items.add(new KnowledgeBaseRefreshItemDto(isin, KnowledgeBaseBulkResearchItemStatus.SKIPPED, null, null, "already_running", null));
                    skipped++;
                    processed++;
                    continue;
                }
                if (dryRun) {
                    items.add(new KnowledgeBaseRefreshItemDto(isin, KnowledgeBaseBulkResearchItemStatus.SKIPPED, null, null, "dry_run", null));
                    skipped++;
                    processed++;
                    continue;
                }
				KnowledgeBaseRefreshItemDto item = runRefreshForIsin(isin, config.autoApprove(), config.applyExtractionsToOverrides(),
						actor, batchId);
				items.add(item);
				processed++;
				switch (item.status()) {
					case SUCCEEDED -> succeeded++;
					case FAILED -> failed++;
					case SKIPPED -> skipped++;
				}
			}
			if (processed >= maxInstruments) {
				break;
			}
		}
		return new KnowledgeBaseRefreshBatchResponseDto(candidates.size(), processed, succeeded, skipped, failed, dryRun, items);
	}

	public List<String> previewCandidates(KnowledgeBaseRefreshBatchRequestDto request) {
		KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
		int limit = request != null && request.limit() != null ? request.limit() : config.maxInstrumentsPerRun();
		return resolveCandidates(request, config, limit);
	}

	private KnowledgeBaseRefreshItemDto runRefreshForIsin(String isin,
														 boolean autoApprove,
														 boolean applyOverrides,
														 String actor,
														 String batchId) {
		KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
		if (Thread.currentThread().isInterrupted()) {
			throw new CancellationException("Canceled");
		}

        if (shouldSkipRefresh(isin, config)) {
            KnowledgeBaseRun skipped = runService.startRun(isin, KnowledgeBaseRunAction.REFRESH, batchId, null);
            runService.incrementAttempt(skipped);
            runService.markSkipped(skipped, "Recently refreshed");
            return new KnowledgeBaseRefreshItemDto(isin, KnowledgeBaseBulkResearchItemStatus.SKIPPED, null, null, "recently_refreshed", null);
        }

		KnowledgeBaseRun run = runService.startRun(isin, KnowledgeBaseRunAction.REFRESH, batchId, null);
		runService.incrementAttempt(run);
		try {
			KnowledgeBaseLlmDossierDraft draft = llmClient.generateDossier(
					isin,
					null,
					config.websearchAllowedDomains(),
					config.dossierMaxChars()
			);
            InstrumentDossierResponseDto dossier = createDossierFromDraft(isin, draft, actor, DossierStatus.PENDING_REVIEW);
            if (autoApprove) {
                dossier = knowledgeBaseService.approveDossier(dossier.dossierId(), actor, true);
            }
            runService.markSucceeded(run);
            KnowledgeBaseBulkResearchItemDto extractionResult = runExtractionFlow(
                    isin, dossier.status(), dossier.dossierId(), actor, autoApprove, applyOverrides
            );
            return new KnowledgeBaseRefreshItemDto(isin, extractionResult.status(), dossier.dossierId(),
                    extractionResult.extractionId(), extractionResult.error(), extractionResult.manualApproval());
        } catch (CancellationException ex) {
            runService.markFailed(run, "Canceled");
            throw ex;
        } catch (Exception ex) {
            runService.markFailed(run, ex.getMessage());
            return new KnowledgeBaseRefreshItemDto(isin, KnowledgeBaseBulkResearchItemStatus.FAILED, null, null,
                    messageOrFallback(ex), null);
        }
    }

    private KnowledgeBaseBulkResearchItemDto runExtractionFlow(String isin,
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
                return new KnowledgeBaseBulkResearchItemDto(isin, KnowledgeBaseBulkResearchItemStatus.FAILED, dossierId,
                        extraction.extractionId(), extraction.error(),
                        knowledgeBaseService.resolveManualApproval(dossierStatus, extraction.status()));
            }
            Long extractionId = extraction.extractionId();
            if (autoApprove) {
                extraction = knowledgeBaseService.approveExtraction(extractionId, actor, true, applyOverrides);
                extractionId = extraction.extractionId();
            }
            runService.markSucceeded(extractRun);
            return new KnowledgeBaseBulkResearchItemDto(isin, KnowledgeBaseBulkResearchItemStatus.SUCCEEDED, dossierId,
                    extractionId, null, knowledgeBaseService.resolveManualApproval(dossierStatus, extraction.status()));
        } catch (CancellationException ex) {
            runService.markFailed(extractRun, "Canceled");
            throw ex;
        } catch (Exception ex) {
            runService.markFailed(extractRun, ex.getMessage());
            return new KnowledgeBaseBulkResearchItemDto(isin, KnowledgeBaseBulkResearchItemStatus.FAILED, dossierId, null,
                    messageOrFallback(ex), null);
        }
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

	private List<String> resolveCandidates(KnowledgeBaseRefreshBatchRequestDto request,
										  KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config,
										  int limit) {
		LocalDateTime staleBefore = LocalDateTime.now().minusDays(config.refreshIntervalDays());
		List<String> scopeIsins = request != null && request.scope() != null ? normalizeScopeIsins(request.scope().isins()) : List.of();
		List<String> candidates = new ArrayList<>();
		if (!scopeIsins.isEmpty()) {
			for (String isin : scopeIsins) {
				if (!candidates.contains(isin)) {
					candidates.add(isin);
				}
			}
		} else {
			List<InstrumentDossierSearchProjection> rows = dossierRepository.searchDossiers(
					null,
					DossierStatus.APPROVED.name(),
					true,
					staleBefore,
					limit,
					0
			);
			for (InstrumentDossierSearchProjection row : rows) {
				candidates.add(row.getIsin());
			}
		}
		if (candidates.size() > limit) {
			return candidates.subList(0, limit);
		}
		return candidates;
	}

	private boolean shouldSkipRefresh(String isin, KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config) {
		return runService.findLatest(isin, KnowledgeBaseRunAction.REFRESH)
				.filter(run -> {
					if (run.getStatus() == KnowledgeBaseRunStatus.IN_PROGRESS) {
						if (run.getStartedAt() == null) {
							return true;
						}
						LocalDateTime timeout = LocalDateTime.now().minusMinutes(config.runTimeoutMinutes());
						return run.getStartedAt().isAfter(timeout);
					}
					if (run.getStatus() != KnowledgeBaseRunStatus.SUCCEEDED) {
						return false;
					}
					if (run.getStartedAt() == null) {
						return false;
					}
					return run.getStartedAt().isAfter(
							LocalDateTime.now().minusDays(config.kbRefreshMinDaysBetweenRunsPerInstrument())
					);
				})
				.isPresent();
	}

	private int estimateInputChars(String isin) {
		return 1200 + (isin == null ? 0 : isin.length());
	}

	private List<String> normalizeScopeIsins(List<String> values) {
		if (values == null) {
			return List.of();
		}
		List<String> normalized = new ArrayList<>();
		for (String isin : values) {
			try {
				normalized.add(normalizeIsin(isin));
			} catch (Exception ex) {
				continue;
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
