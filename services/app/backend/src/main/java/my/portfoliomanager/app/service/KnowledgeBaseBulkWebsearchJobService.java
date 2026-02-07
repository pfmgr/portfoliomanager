package my.portfoliomanager.app.service;

import jakarta.annotation.PreDestroy;
import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchItemDto;
import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchItemStatus;
import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchJobResponseDto;
import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchJobStatus;
import my.portfoliomanager.app.dto.InstrumentDossierBulkWebsearchResultDto;
import my.portfoliomanager.app.service.util.ISINUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

@Service
public class KnowledgeBaseBulkWebsearchJobService {
	private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseBulkWebsearchJobService.class);

	private static final Duration JOB_TTL = Duration.ofMinutes(60);
	private static final int MAX_CONCURRENT_JOBS = 1;
	private static final int BATCH_SIZE = 3;
	private static final int MAX_ISINS = 30;

	private final KnowledgeBaseService knowledgeBaseService;
	private final Map<String, BulkWebsearchJobState> jobs = new ConcurrentHashMap<>();
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Semaphore concurrency = new Semaphore(MAX_CONCURRENT_JOBS);

	public KnowledgeBaseBulkWebsearchJobService(KnowledgeBaseService knowledgeBaseService) {
		this.knowledgeBaseService = knowledgeBaseService;
	}

	public InstrumentDossierBulkWebsearchJobResponseDto start(List<String> isins, String createdBy) {
		List<String> normalized = ISINUtil.normalizeIsins(isins);
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("At least one ISIN is required");
		}
		if (normalized.size() > MAX_ISINS) {
			throw new IllegalArgumentException("Too many ISINs (max " + MAX_ISINS + ")");
		}
		cleanupExpired();
		String jobId = UUID.randomUUID().toString();
		BulkWebsearchJobState job = new BulkWebsearchJobState(jobId, normalized, createdBy, Instant.now());
		jobs.put(jobId, job);
		executor.submit(() -> runJob(jobId));
		return toDto(job);
	}

	public InstrumentDossierBulkWebsearchJobResponseDto get(String jobId) {
		cleanupExpired();
		BulkWebsearchJobState job = jobs.get(jobId);
		if (job == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bulk websearch job not found");
		}
		return toDto(job);
	}

	@PreDestroy
	public void shutdown() {
		executor.shutdownNow();
	}

	private void runJob(String jobId) {
		BulkWebsearchJobState job = jobs.get(jobId);
		if (job == null) {
			return;
		}
		try {
			concurrency.acquire();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			job.status = InstrumentDossierBulkWebsearchJobStatus.FAILED;
			job.error = failWithReference(job, ex);
			job.finishedAt = Instant.now();
			return;
		}

		List<InstrumentDossierBulkWebsearchItemDto> completedItems = new ArrayList<>();
		try {
			job.status = InstrumentDossierBulkWebsearchJobStatus.RUNNING;
			updateResult(job, completedItems);

			for (int offset = 0; offset < job.isins.size(); offset += BATCH_SIZE) {
				List<String> batch = job.isins.subList(offset, Math.min(job.isins.size(), offset + BATCH_SIZE));
				processBatch(job, batch, completedItems);
				updateResult(job, completedItems);
			}

			job.status = InstrumentDossierBulkWebsearchJobStatus.DONE;
		} catch (Exception ex) {
			job.status = InstrumentDossierBulkWebsearchJobStatus.FAILED;
			job.error = failWithReference(job, ex);
		} finally {
			updateResult(job, completedItems);
			job.finishedAt = Instant.now();
			concurrency.release();
		}
	}

	private void processBatch(BulkWebsearchJobState job,
							  List<String> batch,
							  List<InstrumentDossierBulkWebsearchItemDto> completedItems) {
		KnowledgeBaseService.BulkWebsearchDraftResult drafts;
		try {
			logger.info("Processing batch");
			drafts = knowledgeBaseService.createDossierDraftsViaWebsearchBulk(batch);
		} catch (Exception ex) {
			logger.error("Could not execute batch, retry each single isin",ex);
			for (String isin : batch) {
				try {
					logger.info("Retry create Dossier for ISIN {}",isin);
					var singleDraft = knowledgeBaseService.createDossierDraftViaWebsearch(isin);
					KnowledgeBaseService.DossierUpsertResult upsert = knowledgeBaseService.upsertDossierFromWebsearchDraft(
							isin,
							singleDraft.contentMd(),
							singleDraft.displayName(),
							singleDraft.citations(),
							job.createdBy
					);
					completedItems.add(new InstrumentDossierBulkWebsearchItemDto(
							isin,
							upsert.created() ? InstrumentDossierBulkWebsearchItemStatus.CREATED : InstrumentDossierBulkWebsearchItemStatus.UPDATED,
							upsert.dossier().dossierId(),
							null
					));
				} catch (Exception singleEx) {
					String message = singleEx.getMessage();
					completedItems.add(new InstrumentDossierBulkWebsearchItemDto(
							isin,
							InstrumentDossierBulkWebsearchItemStatus.FAILED,
							null,
							message == null || message.isBlank() ? singleEx.getClass().getSimpleName() : message
					));
				}
			}
			return;
		}

		for (KnowledgeBaseService.BulkWebsearchDraftItem item : drafts.items()) {
			if (item.error() != null && !item.error().isBlank()) {
				try {
					logger.info("Fallback to single websearch for ISIN {} after bulk error", item.isin());
					var singleDraft = knowledgeBaseService.createDossierDraftViaWebsearch(item.isin());
					KnowledgeBaseService.DossierUpsertResult upsert = knowledgeBaseService.upsertDossierFromWebsearchDraft(
							item.isin(),
							singleDraft.contentMd(),
							singleDraft.displayName(),
							singleDraft.citations(),
							job.createdBy
					);
					completedItems.add(new InstrumentDossierBulkWebsearchItemDto(
							item.isin(),
							upsert.created() ? InstrumentDossierBulkWebsearchItemStatus.CREATED : InstrumentDossierBulkWebsearchItemStatus.UPDATED,
							upsert.dossier().dossierId(),
							null
					));
				} catch (Exception ex) {
					String message = ex.getMessage();
					completedItems.add(new InstrumentDossierBulkWebsearchItemDto(
							item.isin(),
							InstrumentDossierBulkWebsearchItemStatus.FAILED,
							null,
							message == null || message.isBlank() ? ex.getClass().getSimpleName() : message
					));
				}
				continue;
			}
			try {
				KnowledgeBaseService.DossierUpsertResult upsert = knowledgeBaseService.upsertDossierFromWebsearchDraft(
						item.isin(),
						item.contentMd(),
						item.displayName(),
						item.citations(),
						job.createdBy
				);
				completedItems.add(new InstrumentDossierBulkWebsearchItemDto(
						item.isin(),
						upsert.created() ? InstrumentDossierBulkWebsearchItemStatus.CREATED : InstrumentDossierBulkWebsearchItemStatus.UPDATED,
						upsert.dossier().dossierId(),
						null
				));
			} catch (Exception ex) {
				String message = ex.getMessage();
				completedItems.add(new InstrumentDossierBulkWebsearchItemDto(
						item.isin(),
						InstrumentDossierBulkWebsearchItemStatus.FAILED,
						null,
						message == null || message.isBlank() ? ex.getClass().getSimpleName() : message
				));
			}
		}
	}

	private void updateResult(BulkWebsearchJobState job, List<InstrumentDossierBulkWebsearchItemDto> completedItems) {
		int created = 0;
		int updated = 0;
		int failed = 0;
		for (InstrumentDossierBulkWebsearchItemDto item : completedItems) {
			if (item == null || item.status() == null) {
				continue;
			}
			switch (item.status()) {
				case CREATED -> created++;
				case UPDATED -> updated++;
				case FAILED -> failed++;
			}
		}
		job.result = new InstrumentDossierBulkWebsearchResultDto(
				job.isins.size(),
				completedItems.size(),
				created,
				updated,
				failed,
				List.copyOf(completedItems)
		);
	}

	private InstrumentDossierBulkWebsearchJobResponseDto toDto(BulkWebsearchJobState job) {
		return new InstrumentDossierBulkWebsearchJobResponseDto(
				job.jobId,
				job.status,
				job.result,
				job.error
		);
	}

	private void cleanupExpired() {
		Instant now = Instant.now();
		jobs.entrySet().removeIf(entry -> {
			BulkWebsearchJobState job = entry.getValue();
			Instant base = job.finishedAt == null ? job.createdAt : job.finishedAt;
			return base.plus(JOB_TTL).isBefore(now);
		});
	}





	private String failWithReference(BulkWebsearchJobState job, Exception ex) {
		String message = ex == null ? null : ex.getMessage();
		String reference = "KB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
		logger.error("KB bulk websearch job failed (ref={}, jobId={}, isins={}, error={})",
				reference, job.jobId, job.isins, message, ex);
		return "Error ref " + reference;
	}

	private static final class BulkWebsearchJobState {
		private final String jobId;
		private final List<String> isins;
		private final String createdBy;
		private final Instant createdAt;

		private volatile Instant finishedAt;
		private volatile InstrumentDossierBulkWebsearchJobStatus status;
		private volatile InstrumentDossierBulkWebsearchResultDto result;
		private volatile String error;

		private BulkWebsearchJobState(String jobId, List<String> isins, String createdBy, Instant createdAt) {
			this.jobId = jobId;
			this.isins = List.copyOf(isins);
			this.createdBy = createdBy == null || createdBy.isBlank() ? "system" : createdBy;
			this.createdAt = createdAt;
			this.status = InstrumentDossierBulkWebsearchJobStatus.PENDING;
			this.result = new InstrumentDossierBulkWebsearchResultDto(isins.size(), 0, 0, 0, 0, List.of());
		}
	}
}
