package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import my.portfoliomanager.app.dto.KnowledgeBaseAlternativesResponseDto;
import my.portfoliomanager.app.dto.KnowledgeBaseBulkResearchResponseDto;
import my.portfoliomanager.app.dto.KnowledgeBaseRefreshBatchResponseDto;
import my.portfoliomanager.app.dto.KnowledgeBaseRefreshItemDto;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionResponseDto;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeBaseLlmActionDto(
		String actionId,
		KnowledgeBaseLlmActionType type,
		KnowledgeBaseLlmActionStatus status,
		KnowledgeBaseLlmActionTrigger trigger,
		List<String> isins,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		String message,
		KnowledgeBaseBulkResearchResponseDto bulkResearchResult,
		KnowledgeBaseAlternativesResponseDto alternativesResult,
		KnowledgeBaseRefreshBatchResponseDto refreshBatchResult,
		KnowledgeBaseRefreshItemDto refreshItemResult,
		InstrumentDossierExtractionResponseDto extractionResult
) {
}
