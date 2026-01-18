package my.portfoliomanager.app.service;

import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.dto.ClassificationDto;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeBaseClassificationService {
	private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseClassificationService.class);
	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
	private final ObjectMapper objectMapper;

	public KnowledgeBaseClassificationService(NamedParameterJdbcTemplate namedParameterJdbcTemplate,
											  ObjectMapper objectMapper) {
		this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
		this.objectMapper = objectMapper;
	}

	public Map<String, Suggestion> findSuggestions(List<String> isins) {
		Set<String> normalized = normalizeIsins(isins);
		if (normalized.isEmpty()) {
			return Map.of();
		}
		String sql = """
				SELECT t.isin, t.extracted_json, t.display_name
				FROM (
				  SELECT d.isin,
				         d.display_name,
				         e.extracted_json,
				         ROW_NUMBER() OVER (
				           PARTITION BY d.isin
				           ORDER BY e.applied_at DESC NULLS LAST,
				           			e.approved_at DESC NULLS LAST,
				           			e.created_at DESC,
				           			e.extraction_id DESC
				         ) AS rn
				  FROM instrument_dossiers d
				  JOIN instrument_dossier_extractions e ON e.dossier_id = d.dossier_id
				  WHERE d.isin IN (:isins)
				    AND e.status IN ('APPROVED', 'APPLIED')
				) t
				WHERE t.rn = 1
				""";
		MapSqlParameterSource params = new MapSqlParameterSource("isins", normalized);
		Map<String, Suggestion> results = new LinkedHashMap<>();
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			String isin = rs.getString("isin");
			String json = rs.getString("extracted_json");
			String displayName = rs.getString("display_name");
			Suggestion suggestion = parseSuggestion(isin, json, displayName);
			if (suggestion != null) {
				results.put(isin, suggestion);
			}
		});
		return results;
	}

	private Suggestion parseSuggestion(String isin, String json, String displayName) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			InstrumentDossierExtractionPayload payload = objectMapper.readValue(json, InstrumentDossierExtractionPayload.class);
			String name = trimToNull(payload.name());
			if (name == null) {
				name = trimToNull(displayName);
			}
			Integer layer = payload.layer();
			if (layer != null && (layer < 1 || layer > 5)) {
				layer = null;
			}
			ClassificationDto classification = new ClassificationDto(
					trimToNull(payload.instrumentType()),
					trimToNull(payload.assetClass()),
					trimToNull(payload.subClass()),
					layer
			);
			if (name == null
					&& classification.instrumentType() == null
					&& classification.assetClass() == null
					&& classification.subClass() == null
					&& classification.layer() == null) {
				return null;
			}
			return new Suggestion(name, classification);
		} catch (Exception ex) {
			logger.warn("Failed to parse KB extraction payload for {}: {}", isin, ex.getMessage());
			return null;
		}
	}

	private Set<String> normalizeIsins(List<String> isins) {
		if (isins == null || isins.isEmpty()) {
			return Set.of();
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (String isin : isins) {
			String value = trimToNull(isin);
			if (value != null) {
				normalized.add(value.toUpperCase(Locale.ROOT));
			}
		}
		return normalized;
	}

	private String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	public record Suggestion(String name, ClassificationDto classification) {
	}
}
