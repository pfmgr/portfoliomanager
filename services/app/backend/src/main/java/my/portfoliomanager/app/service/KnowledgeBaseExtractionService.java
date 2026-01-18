package my.portfoliomanager.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import my.portfoliomanager.app.domain.KnowledgeBaseExtraction;
import my.portfoliomanager.app.domain.KnowledgeBaseExtractionStatus;
import my.portfoliomanager.app.repository.KnowledgeBaseExtractionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class KnowledgeBaseExtractionService {
	private final KnowledgeBaseExtractionRepository repository;

	public KnowledgeBaseExtractionService(KnowledgeBaseExtractionRepository repository) {
		this.repository = repository;
	}

	public KnowledgeBaseExtraction upsert(String isin, KnowledgeBaseExtractionStatus status, JsonNode extractedJson, LocalDateTime updatedAt) {
		String normalized = normalizeIsin(isin);
		if (normalized == null || status == null || extractedJson == null) {
			return null;
		}
		KnowledgeBaseExtraction extraction = repository.findById(normalized).orElseGet(KnowledgeBaseExtraction::new);
		extraction.setIsin(normalized);
		extraction.setStatus(status);
		extraction.setExtractedJson(extractedJson);
		extraction.setUpdatedAt(updatedAt == null ? LocalDateTime.now() : updatedAt);
		return repository.save(extraction);
	}

	public int deleteByIsins(List<String> isins) {
		if (isins == null || isins.isEmpty()) {
			return 0;
		}
		return repository.deleteByIsinIn(isins);
	}

	private String normalizeIsin(String isin) {
		if (isin == null || isin.isBlank()) {
			return null;
		}
		return isin.trim().toUpperCase(Locale.ROOT);
	}
}
