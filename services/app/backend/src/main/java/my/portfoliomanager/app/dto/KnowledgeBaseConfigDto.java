package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KnowledgeBaseConfigDto(
		@JsonProperty("enabled") Boolean enabled,
		@JsonProperty("refresh_interval_days") Integer refreshIntervalDays,
		@JsonProperty("auto_approve") Boolean autoApprove,
		@JsonProperty("apply_extractions_to_overrides") Boolean applyExtractionsToOverrides,
		@JsonProperty("overwrite_existing_overrides") Boolean overwriteExistingOverrides,
		@JsonProperty("batch_size_instruments") Integer batchSizeInstruments,
		@JsonProperty("batch_max_input_chars") Integer batchMaxInputChars,
		@JsonProperty("max_parallel_bulk_batches") Integer maxParallelBulkBatches,
		@JsonProperty("max_batches_per_run") Integer maxBatchesPerRun,
		@JsonProperty("poll_interval_seconds") Integer pollIntervalSeconds,
		@JsonProperty("max_instruments_per_run") Integer maxInstrumentsPerRun,
		@JsonProperty("max_retries_per_instrument") Integer maxRetriesPerInstrument,
		@JsonProperty("base_backoff_seconds") Integer baseBackoffSeconds,
		@JsonProperty("max_backoff_seconds") Integer maxBackoffSeconds,
		@JsonProperty("dossier_max_chars") Integer dossierMaxChars,
		@JsonProperty("kb_refresh_min_days_between_runs_per_instrument") Integer kbRefreshMinDaysBetweenRunsPerInstrument,
		@JsonProperty("run_timeout_minutes") Integer runTimeoutMinutes,
		@JsonProperty("websearch_reasoning_effort") String websearchReasoningEffort,
		@JsonProperty("websearch_allowed_domains") List<String> websearchAllowedDomains,
		@JsonProperty("bulk_min_citations") Integer bulkMinCitations,
		@JsonProperty("bulk_require_primary_source") Boolean bulkRequirePrimarySource,
		@JsonProperty("alternatives_min_similarity_score") Double alternativesMinSimilarityScore,
		@JsonProperty("extraction_evidence_required") Boolean extractionEvidenceRequired,
		@JsonProperty("quality_gate_retry_limit") Integer qualityGateRetryLimit,
		@JsonProperty("quality_gate_profiles") KnowledgeBaseQualityGateConfigDto qualityGateProfiles
) {
}
