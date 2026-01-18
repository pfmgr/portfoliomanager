package my.portfoliomanager.app.service;

import tools.jackson.databind.JsonNode;
import my.portfoliomanager.app.domain.InstrumentDossier;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StubExtractorService implements ExtractorService {
	private static final Pattern KEY_VALUE = Pattern.compile("^\\s*([A-Za-z0-9_.\\s-]+?)\\s*:\\s*(.+)$");

	@Override
	public ExtractionResult extract(InstrumentDossier dossier) {
		Map<String, String> values = parseKeyValues(dossier.getContentMd());
		String name = findValue(values, "name");
		String instrumentType = findValue(values, "instrument_type", "instrument type", "type");
		String assetClass = findValue(values, "asset_class", "asset class");
		String subClass = findValue(values, "sub_class", "sub class", "subclass");
		Integer layer = parseInteger(findValue(values, "layer"));
		if (layer != null && (layer < 1 || layer > 5)) {
			layer = null;
		}
		String layerNotes = findValue(values, "layer_notes", "layer notes");
		BigDecimal ongoingChargesPct = parseDecimal(findValue(values, "etf.ongoing_charges_pct", "ongoing_charges_pct", "ongoing charges pct", "ter"));
		String benchmarkIndex = findValue(values, "etf.benchmark_index", "benchmark_index", "benchmark index");
		Integer summaryRiskIndicator = parseInteger(findValue(values, "risk.summary_risk_indicator.value", "summary_risk_indicator", "sri"));
		if (summaryRiskIndicator != null && (summaryRiskIndicator < 1 || summaryRiskIndicator > 7)) {
			summaryRiskIndicator = null;
		}

		List<InstrumentDossierExtractionPayload.SourcePayload> sources = extractSources(dossier.getCitationsJson());
		List<InstrumentDossierExtractionPayload.MissingFieldPayload> missingFields = buildMissingFields(
				name, instrumentType, assetClass, subClass, layer, layerNotes, ongoingChargesPct, benchmarkIndex, summaryRiskIndicator
		);
		List<InstrumentDossierExtractionPayload.WarningPayload> warnings = List.of(
				new InstrumentDossierExtractionPayload.WarningPayload("Stub extractor used; validate fields before applying.")
		);

		InstrumentDossierExtractionPayload.EtfPayload etfPayload =
				(ongoingChargesPct == null && benchmarkIndex == null)
						? null
						: new InstrumentDossierExtractionPayload.EtfPayload(ongoingChargesPct, benchmarkIndex);
		InstrumentDossierExtractionPayload.RiskPayload riskPayload =
				summaryRiskIndicator == null
						? null
						: new InstrumentDossierExtractionPayload.RiskPayload(
								new InstrumentDossierExtractionPayload.SummaryRiskIndicatorPayload(summaryRiskIndicator)
						);

		InstrumentDossierExtractionPayload payload = new InstrumentDossierExtractionPayload(
				dossier.getIsin(),
				name,
				instrumentType,
				assetClass,
				subClass,
				layer,
				layerNotes,
				etfPayload,
				riskPayload,
				null,
				null,
				sources,
				missingFields,
				warnings
		);
		return new ExtractionResult(payload, "stub");
	}

	private Map<String, String> parseKeyValues(String content) {
		Map<String, String> values = new HashMap<>();
		if (content == null || content.isBlank()) {
			return values;
		}
		for (String line : content.split("\\R")) {
			Matcher matcher = KEY_VALUE.matcher(line);
			if (!matcher.matches()) {
				continue;
			}
			String key = normalizeKey(matcher.group(1));
			String value = matcher.group(2).trim();
			if (key.isBlank() || value.isBlank()) {
				continue;
			}
			values.putIfAbsent(key, value);
		}
		return values;
	}

	private String normalizeKey(String raw) {
		String normalized = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s]+", "_");
		return normalized.replaceAll("[^a-z0-9_.]", "");
	}

	private String findValue(Map<String, String> values, String... keys) {
		for (String key : keys) {
			String normalized = normalizeKey(key);
			String value = values.get(normalized);
			if (value != null && !value.isBlank()) {
				return value.trim();
			}
		}
		return null;
	}

	private Integer parseInteger(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private BigDecimal parseDecimal(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			return new BigDecimal(raw.trim().replace("%", ""));
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private List<InstrumentDossierExtractionPayload.SourcePayload> extractSources(JsonNode citations) {
		if (citations == null || !citations.isArray()) {
			return List.of();
		}
		List<InstrumentDossierExtractionPayload.SourcePayload> sources = new ArrayList<>();
		for (JsonNode node : citations) {
			String id = textOrNull(node, "id");
			String title = textOrNull(node, "title");
			String url = textOrNull(node, "url");
			String publisher = textOrNull(node, "publisher");
			String accessedAt = textOrNull(node, "accessed_at");
			if (accessedAt == null) {
				accessedAt = textOrNull(node, "accessedAt");
			}
			sources.add(new InstrumentDossierExtractionPayload.SourcePayload(id, title, url, publisher, accessedAt));
		}
		return sources;
	}

	private String textOrNull(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		String text = value.asText();
		return text == null || text.isBlank() ? null : text;
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
			Integer summaryRiskIndicator
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
		return missing;
	}

	private void addMissing(List<InstrumentDossierExtractionPayload.MissingFieldPayload> missing, String field, Object value) {
		if (value == null) {
			missing.add(new InstrumentDossierExtractionPayload.MissingFieldPayload(field, "Not found in dossier content."));
		}
	}
}
