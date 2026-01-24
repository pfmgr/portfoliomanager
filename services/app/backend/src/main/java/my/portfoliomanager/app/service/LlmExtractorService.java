package my.portfoliomanager.app.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.domain.InstrumentDossier;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmClient;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmExtractionDraft;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@Primary
@ConditionalOnProperty(name = "app.kb.llm-enabled", havingValue = "true")
public class LlmExtractorService implements ExtractorService {
	private final KnowledgeBaseLlmClient llmClient;
	private final ObjectMapper objectMapper;

	public LlmExtractorService(KnowledgeBaseLlmClient llmClient,
							   ObjectMapper objectMapper) {
		this.llmClient = llmClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public ExtractionResult extract(InstrumentDossier dossier) {
		KnowledgeBaseLlmExtractionDraft draft = llmClient.extractMetadata(dossier.getContentMd());
		InstrumentDossierExtractionPayload payload = parseExtraction(dossier, draft.extractionJson());
		return new ExtractionResult(payload, draft.model());
	}

	private InstrumentDossierExtractionPayload parseExtraction(InstrumentDossier dossier, JsonNode data) {
		List<InstrumentDossierExtractionPayload.WarningPayload> warnings = new ArrayList<>();

		String llmIsin = textOrNull(data, "isin");
		if (llmIsin != null && dossier.getIsin() != null && !llmIsin.equalsIgnoreCase(dossier.getIsin())) {
			warnings.add(new InstrumentDossierExtractionPayload.WarningPayload(
					"LLM output ISIN (" + llmIsin + ") does not match dossier ISIN (" + dossier.getIsin() + "); dossier ISIN wins."
			));
		}

		String name = textOrNull(data, "name", "display_name", "displayName");
		String instrumentType = textOrNull(data, "instrument_type", "instrumentType");
		String assetClass = textOrNull(data, "asset_class", "assetClass");
		String subClass = textOrNull(data, "sub_class", "subClass");
		Integer layer = integerOrNull(data, "layer");
		if (layer != null && (layer < 1 || layer > 5)) {
			warnings.add(new InstrumentDossierExtractionPayload.WarningPayload(
					"Invalid layer value (" + layer + "); expected 1..5, set to null."
			));
			layer = null;
		}
		String layerNotes = textOrNull(data, "layer_notes", "layerNotes");

		JsonNode etfNode = objectOrNull(data, "etf");
		BigDecimal ongoingChargesPct = decimalOrNull(etfNode, "ongoing_charges_pct", "ongoingChargesPct");
		if (ongoingChargesPct != null && ongoingChargesPct.compareTo(BigDecimal.ZERO) < 0) {
			warnings.add(new InstrumentDossierExtractionPayload.WarningPayload(
					"Invalid ongoing_charges_pct value (" + ongoingChargesPct + "); expected >= 0, set to null."
			));
			ongoingChargesPct = null;
		}
		String benchmarkIndex = textOrNull(etfNode, "benchmark_index", "benchmarkIndex");

		JsonNode riskNode = objectOrNull(data, "risk");
		Integer sriValue = extractSri(riskNode);
		if (sriValue != null && (sriValue < 1 || sriValue > 7)) {
			warnings.add(new InstrumentDossierExtractionPayload.WarningPayload(
					"Invalid summary_risk_indicator.value (" + sriValue + "); expected 1..7, set to null."
			));
			sriValue = null;
		}

		List<InstrumentDossierExtractionPayload.RegionExposurePayload> regions = parseRegions(data, warnings);
		List<InstrumentDossierExtractionPayload.HoldingPayload> topHoldings = parseTopHoldings(data, warnings);
		InstrumentDossierExtractionPayload.FinancialsPayload financials = parsePayload(
				data == null ? null : data.get("financials"),
				InstrumentDossierExtractionPayload.FinancialsPayload.class,
				warnings,
				"financials"
		);
		InstrumentDossierExtractionPayload.ValuationPayload valuation = parsePayload(
				data == null ? null : data.get("valuation"),
				InstrumentDossierExtractionPayload.ValuationPayload.class,
				warnings,
				"valuation"
		);
		PeCurrentAsOfFallbackResult fallbackResult = applyPeCurrentAsOfFallback(dossier, valuation, warnings);
		valuation = fallbackResult.valuation();

		InstrumentDossierExtractionPayload.EtfPayload etfPayload =
				(ongoingChargesPct == null && benchmarkIndex == null)
						? null
						: new InstrumentDossierExtractionPayload.EtfPayload(ongoingChargesPct, benchmarkIndex);
		InstrumentDossierExtractionPayload.RiskPayload riskPayload =
				sriValue == null
						? null
						: new InstrumentDossierExtractionPayload.RiskPayload(
								new InstrumentDossierExtractionPayload.SummaryRiskIndicatorPayload(sriValue)
						);
		List<InstrumentDossierExtractionPayload.SourcePayload> sources = extractSources(dossier.getCitationsJson());
		List<InstrumentDossierExtractionPayload.MissingFieldPayload> missingFields = parseMissingFields(data);
		if (missingFields == null || missingFields.isEmpty()) {
			missingFields = buildMissingFields(
					name,
					instrumentType,
					assetClass,
					subClass,
					layer,
					layerNotes,
					ongoingChargesPct,
					benchmarkIndex,
					sriValue,
					financials,
					valuation
			);
		}
		if (fallbackResult.applied() && missingFields != null && !missingFields.isEmpty()) {
			List<InstrumentDossierExtractionPayload.MissingFieldPayload> updatedMissing = new ArrayList<>(missingFields);
			updatedMissing.removeIf(item -> item != null
					&& ("pe_current_asof".equals(item.field()) || "valuation.pe_current_asof".equals(item.field())));
			missingFields = updatedMissing;
		}
		List<InstrumentDossierExtractionPayload.WarningPayload> llmWarnings = parseWarnings(data);
		if (llmWarnings != null && !llmWarnings.isEmpty()) {
			warnings.addAll(llmWarnings);
		}

		return new InstrumentDossierExtractionPayload(
				dossier.getIsin(),
				name,
				instrumentType,
				assetClass,
				subClass,
				layer,
				layerNotes,
				etfPayload,
				riskPayload,
				regions,
				topHoldings,
				financials,
				valuation,
				sources,
				missingFields,
				warnings.isEmpty() ? null : warnings
		);
	}

	private Integer extractSri(JsonNode riskNode) {
		if (riskNode == null) {
			return null;
		}
		JsonNode sriNode = objectOrNull(riskNode, "summary_risk_indicator", "summaryRiskIndicator");
		Integer value = integerOrNull(sriNode, "value");
		if (value != null) {
			return value;
		}
		return integerOrNull(riskNode, "summary_risk_indicator", "summaryRiskIndicator", "sri");
	}

	private InstrumentDossierExtractionPayload.SourcePayload toSource(JsonNode node) {
		String id = textOrNull(node, "id");
		String title = textOrNull(node, "title");
		String url = textOrNull(node, "url");
		String publisher = textOrNull(node, "publisher");
		String accessedAt = textOrNull(node, "accessed_at", "accessedAt");
		return new InstrumentDossierExtractionPayload.SourcePayload(id, title, url, publisher, accessedAt);
	}

	private List<InstrumentDossierExtractionPayload.SourcePayload> extractSources(JsonNode citations) {
		if (citations == null || !citations.isArray()) {
			return List.of();
		}
		List<InstrumentDossierExtractionPayload.SourcePayload> sources = new ArrayList<>();
		for (JsonNode node : citations) {
			sources.add(toSource(node));
		}
		return sources;
	}

	private List<InstrumentDossierExtractionPayload.MissingFieldPayload> buildMissingFields(
			String name,
			String instrumentType,
			String assetClass,
			String subClass,
			Integer layer,
			String layerNotes,
			BigDecimal ongoingChargesPct,
			String benchmarkIndex,
			Integer summaryRiskIndicator,
			InstrumentDossierExtractionPayload.FinancialsPayload financials,
			InstrumentDossierExtractionPayload.ValuationPayload valuation
	) {
		List<InstrumentDossierExtractionPayload.MissingFieldPayload> missing = new ArrayList<>();
		addMissing(missing, "name", name);
		addMissing(missing, "instrument_type", instrumentType);
		addMissing(missing, "asset_class", assetClass);
		addMissing(missing, "sub_class", subClass);
		addMissing(missing, "layer", layer);
		addMissing(missing, "layer_notes", layerNotes);
		addMissing(missing, "etf.ongoing_charges_pct", ongoingChargesPct);
		addMissing(missing, "etf.benchmark_index", benchmarkIndex);
		addMissing(missing, "risk.summary_risk_indicator.value", summaryRiskIndicator);
		addMissing(missing, "financials", financials);
		addMissing(missing, "valuation", valuation);
		return missing;
	}

	private void addMissing(List<InstrumentDossierExtractionPayload.MissingFieldPayload> missing, String field, Object value) {
		if (value == null) {
			missing.add(new InstrumentDossierExtractionPayload.MissingFieldPayload(field, "Not found in dossier content."));
		}
	}

	private List<InstrumentDossierExtractionPayload.MissingFieldPayload> parseMissingFields(JsonNode data) {
		JsonNode missingNode = arrayOrNull(data, "missing_fields", "missingFields");
		if (missingNode == null || !missingNode.isArray()) {
			return null;
		}
		List<InstrumentDossierExtractionPayload.MissingFieldPayload> missing = new ArrayList<>();
		for (JsonNode node : missingNode) {
			if (node == null || !node.isObject()) {
				continue;
			}
			String field = textOrNull(node, "field");
			String reason = textOrNull(node, "reason");
			if (field == null || reason == null) {
				continue;
			}
			missing.add(new InstrumentDossierExtractionPayload.MissingFieldPayload(field, reason));
		}
		return missing.isEmpty() ? null : missing;
	}

	private List<InstrumentDossierExtractionPayload.WarningPayload> parseWarnings(JsonNode data) {
		JsonNode warningsNode = arrayOrNull(data, "warnings");
		if (warningsNode == null || !warningsNode.isArray()) {
			return null;
		}
		List<InstrumentDossierExtractionPayload.WarningPayload> warnings = new ArrayList<>();
		for (JsonNode node : warningsNode) {
			if (node == null || !node.isObject()) {
				continue;
			}
			String message = textOrNull(node, "message");
			if (message == null) {
				continue;
			}
			warnings.add(new InstrumentDossierExtractionPayload.WarningPayload(message));
		}
		return warnings.isEmpty() ? null : warnings;
	}

	private <T> T parsePayload(JsonNode node,
							   Class<T> target,
							   List<InstrumentDossierExtractionPayload.WarningPayload> warnings,
							   String label) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isObject()) {
			warnings.add(new InstrumentDossierExtractionPayload.WarningPayload(
					"Invalid " + label + " payload; expected object, set to null."
			));
			return null;
		}
		try {
			return objectMapper.treeToValue(node, target);
		} catch (Exception ex) {
			warnings.add(new InstrumentDossierExtractionPayload.WarningPayload(
					"Failed to parse " + label + " payload; set to null."
			));
			return null;
		}
	}

	private PeCurrentAsOfFallbackResult applyPeCurrentAsOfFallback(
			InstrumentDossier dossier,
			InstrumentDossierExtractionPayload.ValuationPayload valuation,
			List<InstrumentDossierExtractionPayload.WarningPayload> warnings) {
		if (valuation == null || valuation.peCurrent() == null || valuation.peCurrentAsOf() != null) {
			return new PeCurrentAsOfFallbackResult(valuation, false);
		}
		java.time.LocalDateTime updatedAt = dossier == null ? null : dossier.getUpdatedAt();
		if (updatedAt == null && dossier != null) {
			updatedAt = dossier.getCreatedAt();
		}
		if (updatedAt == null) {
			return new PeCurrentAsOfFallbackResult(valuation, false);
		}
		String asOf = updatedAt.toLocalDate().toString();
		try {
			java.util.Map<String, Object> raw = objectMapper.convertValue(
					valuation,
					new TypeReference<java.util.Map<String, Object>>() {
					}
			);
			raw.put("pe_current_asof", asOf);
			InstrumentDossierExtractionPayload.ValuationPayload updated = objectMapper.convertValue(
					raw,
					InstrumentDossierExtractionPayload.ValuationPayload.class
			);
			warnings.add(new InstrumentDossierExtractionPayload.WarningPayload(
					"pe_current_asof missing; defaulted to dossier updated date " + asOf + "."
			));
			return new PeCurrentAsOfFallbackResult(updated, true);
		} catch (IllegalArgumentException ex) {
			warnings.add(new InstrumentDossierExtractionPayload.WarningPayload(
					"Failed to apply pe_current_asof fallback; kept null."
			));
			return new PeCurrentAsOfFallbackResult(valuation, false);
		}
	}

	private record PeCurrentAsOfFallbackResult(
			InstrumentDossierExtractionPayload.ValuationPayload valuation,
			boolean applied
	) {
	}

	private String textOrNull(JsonNode node, String... fields) {
		if (node == null || fields == null) {
			return null;
		}
		for (String field : fields) {
			if (field == null || field.isBlank()) {
				continue;
			}
			JsonNode value = node.get(field);
			if (value == null || value.isNull()) {
				continue;
			}
			if (!value.isValueNode()) {
				continue;
			}
			String text = value.asText();
			if (text == null) {
				continue;
			}
			String trimmed = text.trim();
			String normalized = trimmed.toLowerCase(Locale.ROOT);
			if (normalized.equals("unknown") || normalized.equals("n/a") || normalized.equals("na") || normalized.equals("null")) {
				continue;
			}
			if (!trimmed.isBlank()) {
				return trimmed;
			}
		}
		return null;
	}

	private Integer integerOrNull(JsonNode node, String... fields) {
		if (node == null) {
			return null;
		}
		for (String field : fields) {
			JsonNode value = node.get(field);
			Integer parsed = integerOrNull(value);
			if (parsed != null) {
				return parsed;
			}
		}
		return null;
	}

	private Integer integerOrNull(JsonNode value) {
		if (value == null || value.isNull()) {
			return null;
		}
		if (value.isInt() || value.isLong() || value.isNumber()) {
			return value.intValue();
		}
		if (!value.isTextual()) {
			return null;
		}
		String raw = value.asText();
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.isBlank()) {
			return null;
		}
		try {
			String extracted = trimmed.replaceAll("^.*?(-?\\d+).*$", "$1");
			if (extracted.equals(trimmed) && !trimmed.matches("-?\\d+")) {
				return null;
			}
			return Integer.parseInt(extracted);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private BigDecimal decimalOrNull(JsonNode node, String... fields) {
		if (node == null) {
			return null;
		}
		for (String field : fields) {
			JsonNode value = node.get(field);
			BigDecimal parsed = decimalOrNull(value);
			if (parsed != null) {
				return parsed;
			}
		}
		return null;
	}

	private BigDecimal decimalOrNull(JsonNode value) {
		if (value == null || value.isNull()) {
			return null;
		}
		if (value.isNumber()) {
			return value.decimalValue();
		}
		if (!value.isTextual()) {
			return null;
		}
		String raw = value.asText();
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.isBlank()) {
			return null;
		}
		String cleaned = trimmed.replace("%", "").replace("pct", "").trim();
		if (cleaned.isBlank()) {
			return null;
		}
		String extracted = cleaned.replaceAll("^.*?(-?\\d+(?:[.,]\\d+)?).*$", "$1");
		if (extracted.equals(cleaned) && !cleaned.matches("-?\\d+(?:[.,]\\d+)?")) {
			return null;
		}
		extracted = extracted.replace(",", ".");
		try {
			return new BigDecimal(extracted);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private JsonNode objectOrNull(JsonNode node, String... fields) {
		if (node == null || fields == null) {
			return null;
		}
		for (String field : fields) {
			if (field == null || field.isBlank()) {
				continue;
			}
			JsonNode value = node.get(field);
			if (value == null || value.isNull() || !value.isObject()) {
				continue;
			}
			return value;
		}
		return null;
	}

	private JsonNode arrayOrNull(JsonNode node, String... fields) {
		if (node == null || fields == null) {
			return null;
		}
		for (String field : fields) {
			if (field == null || field.isBlank()) {
				continue;
			}
			JsonNode value = node.get(field);
			if (value == null || value.isNull() || !value.isArray()) {
				continue;
			}
			return value;
		}
		return null;
	}

	private List<InstrumentDossierExtractionPayload.RegionExposurePayload> parseRegions(
			JsonNode data,
			List<InstrumentDossierExtractionPayload.WarningPayload> warnings) {
		JsonNode array = arrayOrNull(data, "regions", "region_allocations", "regionAllocations");
		return parseRegionWeights(array, warnings);
	}

	private List<InstrumentDossierExtractionPayload.HoldingPayload> parseTopHoldings(
			JsonNode data,
			List<InstrumentDossierExtractionPayload.WarningPayload> warnings) {
		JsonNode array = arrayOrNull(data, "top_holdings", "topHoldings", "holdings");
		return parseHoldingWeights(array, warnings);
	}

	private List<InstrumentDossierExtractionPayload.RegionExposurePayload> parseRegionWeights(
			JsonNode array,
			List<InstrumentDossierExtractionPayload.WarningPayload> warnings) {
		if (array == null || !array.isArray()) {
			return null;
		}
		List<InstrumentDossierExtractionPayload.RegionExposurePayload> result = new ArrayList<>();
		for (JsonNode item : array) {
			if (item == null || !item.isObject()) {
				continue;
			}
			String name = textOrNull(item, "name", "region");
			BigDecimal weight = decimalOrNull(item, "weight_pct", "weightPct", "weight");
			if (weight != null && (weight.compareTo(BigDecimal.ZERO) < 0 || weight.compareTo(new BigDecimal("100")) > 0)) {
				warnings.add(new InstrumentDossierExtractionPayload.WarningPayload(
						"Invalid weight_pct value (" + weight + "); expected 0..100, set to null."
				));
				weight = null;
			}
			if (name == null) {
				continue;
			}
			result.add(new InstrumentDossierExtractionPayload.RegionExposurePayload(name, weight));
		}
		return result.isEmpty() ? null : result;
	}

	private List<InstrumentDossierExtractionPayload.HoldingPayload> parseHoldingWeights(
			JsonNode array,
			List<InstrumentDossierExtractionPayload.WarningPayload> warnings) {
		if (array == null || !array.isArray()) {
			return null;
		}
		List<InstrumentDossierExtractionPayload.HoldingPayload> result = new ArrayList<>();
		for (JsonNode item : array) {
			if (item == null || !item.isObject()) {
				continue;
			}
			String name = textOrNull(item, "name", "holding", "issuer");
			BigDecimal weight = decimalOrNull(item, "weight_pct", "weightPct", "weight");
			if (weight != null && (weight.compareTo(BigDecimal.ZERO) < 0 || weight.compareTo(new BigDecimal("100")) > 0)) {
				warnings.add(new InstrumentDossierExtractionPayload.WarningPayload(
						"Invalid weight_pct value (" + weight + "); expected 0..100, set to null."
				));
				weight = null;
			}
			if (name == null) {
				continue;
			}
			result.add(new InstrumentDossierExtractionPayload.HoldingPayload(name, weight));
		}
		return result.isEmpty() ? null : result;
	}
}
