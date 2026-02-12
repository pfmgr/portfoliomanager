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
import java.util.regex.Pattern;

@Service
@Primary
@ConditionalOnProperty(name = "app.kb.llm-enabled", havingValue = "true")
public class LlmExtractorService implements ExtractorService {
	private final KnowledgeBaseLlmClient llmClient;
	private final ObjectMapper objectMapper;
	private final DossierPreParser preParser;
	private final KnowledgeBaseQualityGateService qualityGateService;
	private final KnowledgeBaseConfigService configService;
	private static final List<String> THEMATIC_KEYWORDS = List.of(
			"theme",
			"thematic",
			"sector",
			"industry",
			"defense",
			"defence",
			"energy",
			"lithium",
			"battery",
			"batteries",
			"clean",
			"renewable",
			"semiconductor",
			"robot",
			"ai",
			"artificial intelligence",
			"cyber",
			"cloud",
			"biotech",
			"healthcare",
			"pharma",
			"water",
			"gold",
			"silver",
			"oil",
			"gas",
			"uranium",
			"mining",
			"metals",
			"commodity",
			"commodities"
	);
	private static final Pattern RISK_SECTION_PATTERN =
			Pattern.compile("(?m)^##\\s+risk\\b", Pattern.CASE_INSENSITIVE);

	public LlmExtractorService(KnowledgeBaseLlmClient llmClient,
						   ObjectMapper objectMapper,
						   DossierPreParser preParser,
						   KnowledgeBaseQualityGateService qualityGateService,
						   KnowledgeBaseConfigService configService) {
		this.llmClient = llmClient;
		this.objectMapper = objectMapper;
		this.preParser = preParser;
		this.qualityGateService = qualityGateService;
		this.configService = configService;
	}

	@Override
	public ExtractionResult extract(InstrumentDossier dossier) {
		InstrumentDossierExtractionPayload preParsed = preParser.parse(dossier);
		PreParseAssessment assessment = assessPreParsed(dossier, preParsed);
		if (!assessment.useLlm()) {
			InstrumentDossierExtractionPayload skipped = applyMissingFieldsAndWarnings(
					preParsed,
					assessment.missingFields(),
					appendWarning(preParsed == null ? null : preParsed.warnings(),
							"LLM extraction skipped; parser evidence/missing checks passed.")
			);
			return new ExtractionResult(skipped, "parser");
		}
		KnowledgeBaseLlmExtractionDraft draft = llmClient.extractMetadata(dossier.getContentMd());
		InstrumentDossierExtractionPayload llmPayload = parseExtraction(dossier, draft.extractionJson());
		InstrumentDossierExtractionPayload merged = mergePayloads(preParsed, llmPayload);
		List<InstrumentDossierExtractionPayload.MissingFieldPayload> missingFields =
				filterMissingFields(llmPayload == null ? null : llmPayload.missingFields(), merged);
		if (missingFields == null || missingFields.isEmpty()) {
			missingFields = buildMissingFields(
					merged.name(),
					merged.instrumentType(),
					merged.assetClass(),
					merged.subClass(),
					merged.layer(),
					merged.layerNotes(),
					merged.etf() == null ? null : merged.etf().ongoingChargesPct(),
					merged.etf() == null ? null : merged.etf().benchmarkIndex(),
					merged.risk() == null || merged.risk().summaryRiskIndicator() == null
							? null
							: merged.risk().summaryRiskIndicator().value(),
					merged.financials(),
					merged.valuation(),
					merged.gicsSector(),
					merged.gicsIndustryGroup(),
					merged.gicsIndustry(),
					merged.gicsSubIndustry(),
					merged.sectors(),
					merged.instrumentType()
			);
		}
		List<InstrumentDossierExtractionPayload.WarningPayload> warnings = mergeWarnings(
				preParsed == null ? null : preParsed.warnings(),
				llmPayload == null ? null : llmPayload.warnings()
		);
		warnings = appendWarning(warnings, "LLM extraction merged with parser prefill.");
		InstrumentDossierExtractionPayload payload = new InstrumentDossierExtractionPayload(
				merged.isin(),
				merged.name(),
				merged.instrumentType(),
				merged.assetClass(),
				merged.subClass(),
				merged.gicsSector(),
				merged.gicsIndustryGroup(),
				merged.gicsIndustry(),
				merged.gicsSubIndustry(),
				merged.layer(),
				merged.layerNotes(),
				merged.etf(),
				merged.risk(),
				merged.regions(),
				merged.sectors(),
				merged.topHoldings(),
				merged.financials(),
				merged.valuation(),
				merged.sources(),
				missingFields,
				warnings
		);
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
		String gicsSector = textOrNull(data, "gics_sector", "gicsSector");
		String gicsIndustryGroup = textOrNull(data, "gics_industry_group", "gicsIndustryGroup");
		String gicsIndustry = textOrNull(data, "gics_industry", "gicsIndustry");
		String gicsSubIndustry = textOrNull(data, "gics_sub_industry", "gicsSubIndustry");
		Integer layer = integerOrNull(data, "layer");
		if (layer != null && (layer < 1 || layer > 5)) {
			warnings.add(new InstrumentDossierExtractionPayload.WarningPayload(
					"Invalid layer value (" + layer + "); expected 1..5, set to null."
			));
			layer = null;
		}
		String layerNotes = textOrNull(data, "layer_notes", "layerNotes");
		ThemeLayerDecision themeDecision = forceThemeLayerIfNeeded(layer, instrumentType, name, subClass, layerNotes);
		layer = themeDecision.layer();
		layerNotes = themeDecision.layerNotes();

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
		List<InstrumentDossierExtractionPayload.SectorExposurePayload> sectors = parseSectors(data, warnings);
		sectors = applySingleStockSectorFallback(sectors, gicsSector, instrumentType, warnings);
		List<InstrumentDossierExtractionPayload.HoldingPayload> topHoldings = parseTopHoldings(data, warnings);
		topHoldings = sanitizeEtfHoldings(topHoldings, instrumentType, layer, warnings);
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
		boolean riskSectionPresent = hasRiskSection(dossier);
		InstrumentDossierExtractionPayload.RiskPayload riskPayload =
				sriValue == null && !riskSectionPresent
						? null
						: new InstrumentDossierExtractionPayload.RiskPayload(
								sriValue == null
										? null
										: new InstrumentDossierExtractionPayload.SummaryRiskIndicatorPayload(sriValue),
								riskSectionPresent
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
				valuation,
				gicsSector,
				gicsIndustryGroup,
				gicsIndustry,
				gicsSubIndustry,
				sectors,
				instrumentType
		);
		}
		if (fallbackResult.applied() && missingFields != null && !missingFields.isEmpty()) {
			List<InstrumentDossierExtractionPayload.MissingFieldPayload> updatedMissing = new ArrayList<>(missingFields);
			updatedMissing.removeIf(item -> item != null
					&& ("pe_current_asof".equals(item.field()) || "valuation.pe_current_asof".equals(item.field())));
			missingFields = updatedMissing;
		}
		missingFields = augmentMissingFields(missingFields, valuation, instrumentType, layer);
		missingFields = sanitizeMissingFields(missingFields, instrumentType, regions, sectors, topHoldings);
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
				gicsSector,
				gicsIndustryGroup,
				gicsIndustry,
				gicsSubIndustry,
				layer,
				layerNotes,
				etfPayload,
				riskPayload,
				regions,
				sectors,
				topHoldings,
				financials,
				valuation,
				sources,
				missingFields,
				warnings.isEmpty() ? null : warnings
		);
	}

	private InstrumentDossierExtractionPayload mergePayloads(InstrumentDossierExtractionPayload pre,
								InstrumentDossierExtractionPayload llm) {
		if (pre == null) {
			return llm;
		}
		if (llm == null) {
			return pre;
		}
		return new InstrumentDossierExtractionPayload(
				first(pre.isin(), llm.isin()),
				first(pre.name(), llm.name()),
				first(pre.instrumentType(), llm.instrumentType()),
				first(pre.assetClass(), llm.assetClass()),
				first(pre.subClass(), llm.subClass()),
				first(pre.gicsSector(), llm.gicsSector()),
				first(pre.gicsIndustryGroup(), llm.gicsIndustryGroup()),
				first(pre.gicsIndustry(), llm.gicsIndustry()),
				first(pre.gicsSubIndustry(), llm.gicsSubIndustry()),
				first(pre.layer(), llm.layer()),
				first(pre.layerNotes(), llm.layerNotes()),
				mergeEtf(pre.etf(), llm.etf()),
				mergeRisk(pre.risk(), llm.risk()),
				mergeList(pre.regions(), llm.regions()),
				mergeList(pre.sectors(), llm.sectors()),
				mergeList(pre.topHoldings(), llm.topHoldings()),
				mergeFinancials(pre.financials(), llm.financials()),
				mergeValuation(pre.valuation(), llm.valuation()),
				mergeList(pre.sources(), llm.sources()),
				null,
				null
		);
	}

	private PreParseAssessment assessPreParsed(InstrumentDossier dossier,
									InstrumentDossierExtractionPayload preParsed) {
		if (preParsed == null) {
			return new PreParseAssessment(true, null);
		}
		List<InstrumentDossierExtractionPayload.MissingFieldPayload> missing = preParsed.missingFields();
		missing = augmentMissingFields(missing, preParsed.valuation(), preParsed.instrumentType(), preParsed.layer());
		missing = sanitizeMissingFields(missing,
				preParsed.instrumentType(),
				preParsed.regions(),
				preParsed.sectors(),
				preParsed.topHoldings());
		boolean hasMissing = missing != null && !missing.isEmpty();
		boolean evidenceFailed = false;
		if (qualityGateService != null) {
			KnowledgeBaseQualityGateService.EvidenceResult result = qualityGateService.evaluateExtractionEvidence(
				dossier == null ? null : dossier.getContentMd(),
				preParsed,
				configService == null ? null : configService.getSnapshot()
			);
			evidenceFailed = !result.passed();
		}
		return new PreParseAssessment(hasMissing || evidenceFailed, missing);
	}

	private InstrumentDossierExtractionPayload applyMissingFieldsAndWarnings(
			InstrumentDossierExtractionPayload payload,
			List<InstrumentDossierExtractionPayload.MissingFieldPayload> missing,
			List<InstrumentDossierExtractionPayload.WarningPayload> warnings) {
		if (payload == null) {
			return null;
		}
		List<InstrumentDossierExtractionPayload.MissingFieldPayload> resolvedMissing =
				missing == null || missing.isEmpty() ? null : missing;
		List<InstrumentDossierExtractionPayload.WarningPayload> resolvedWarnings =
				warnings == null || warnings.isEmpty() ? null : warnings;
		return new InstrumentDossierExtractionPayload(
				payload.isin(),
				payload.name(),
				payload.instrumentType(),
				payload.assetClass(),
				payload.subClass(),
				payload.gicsSector(),
				payload.gicsIndustryGroup(),
				payload.gicsIndustry(),
				payload.gicsSubIndustry(),
				payload.layer(),
				payload.layerNotes(),
				payload.etf(),
				payload.risk(),
				payload.regions(),
				payload.sectors(),
				payload.topHoldings(),
				payload.financials(),
				payload.valuation(),
				payload.sources(),
				resolvedMissing,
				resolvedWarnings
		);
	}

	private InstrumentDossierExtractionPayload.EtfPayload mergeEtf(
			InstrumentDossierExtractionPayload.EtfPayload pre,
			InstrumentDossierExtractionPayload.EtfPayload llm) {
		if (pre == null) {
			return llm;
		}
		if (llm == null) {
			return pre;
		}
		return new InstrumentDossierExtractionPayload.EtfPayload(
				first(pre.ongoingChargesPct(), llm.ongoingChargesPct()),
				first(pre.benchmarkIndex(), llm.benchmarkIndex())
		);
	}

	private InstrumentDossierExtractionPayload.RiskPayload mergeRisk(
			InstrumentDossierExtractionPayload.RiskPayload pre,
			InstrumentDossierExtractionPayload.RiskPayload llm) {
		if (pre == null) {
			return llm;
		}
		if (llm == null) {
			return pre;
		}
		InstrumentDossierExtractionPayload.SummaryRiskIndicatorPayload preSri = pre.summaryRiskIndicator();
		InstrumentDossierExtractionPayload.SummaryRiskIndicatorPayload llmSri = llm.summaryRiskIndicator();
		InstrumentDossierExtractionPayload.SummaryRiskIndicatorPayload merged = preSri != null ? preSri : llmSri;
		Boolean sectionPresent = null;
		if (Boolean.TRUE.equals(pre.sectionPresent()) || Boolean.TRUE.equals(llm.sectionPresent())) {
			sectionPresent = true;
		} else if (pre.sectionPresent() != null) {
			sectionPresent = pre.sectionPresent();
		} else if (llm.sectionPresent() != null) {
			sectionPresent = llm.sectionPresent();
		}
		if (merged == null && sectionPresent == null) {
			return null;
		}
		return new InstrumentDossierExtractionPayload.RiskPayload(merged, sectionPresent);
	}

	private InstrumentDossierExtractionPayload.FinancialsPayload mergeFinancials(
			InstrumentDossierExtractionPayload.FinancialsPayload pre,
			InstrumentDossierExtractionPayload.FinancialsPayload llm) {
		if (pre == null) {
			return llm;
		}
		if (llm == null) {
			return pre;
		}
		return new InstrumentDossierExtractionPayload.FinancialsPayload(
				first(pre.revenue(), llm.revenue()),
				first(pre.revenueCurrency(), llm.revenueCurrency()),
				first(pre.revenueEur(), llm.revenueEur()),
				first(pre.revenuePeriodEnd(), llm.revenuePeriodEnd()),
				first(pre.revenuePeriodType(), llm.revenuePeriodType()),
				first(pre.netIncome(), llm.netIncome()),
				first(pre.netIncomeCurrency(), llm.netIncomeCurrency()),
				first(pre.netIncomeEur(), llm.netIncomeEur()),
				first(pre.netIncomePeriodEnd(), llm.netIncomePeriodEnd()),
				first(pre.netIncomePeriodType(), llm.netIncomePeriodType()),
				first(pre.dividendPerShare(), llm.dividendPerShare()),
				first(pre.dividendCurrency(), llm.dividendCurrency()),
				first(pre.dividendAsOf(), llm.dividendAsOf()),
				first(pre.fxRateToEur(), llm.fxRateToEur())
		);
	}

	private InstrumentDossierExtractionPayload.ValuationPayload mergeValuation(
			InstrumentDossierExtractionPayload.ValuationPayload pre,
			InstrumentDossierExtractionPayload.ValuationPayload llm) {
		if (pre == null) {
			return llm;
		}
		if (llm == null) {
			return pre;
		}
		return new InstrumentDossierExtractionPayload.ValuationPayload(
				first(pre.ebitda(), llm.ebitda()),
				first(pre.ebitdaCurrency(), llm.ebitdaCurrency()),
				first(pre.ebitdaEur(), llm.ebitdaEur()),
				first(pre.fxRateToEur(), llm.fxRateToEur()),
				first(pre.ebitdaPeriodEnd(), llm.ebitdaPeriodEnd()),
				first(pre.ebitdaPeriodType(), llm.ebitdaPeriodType()),
				first(pre.enterpriseValue(), llm.enterpriseValue()),
				first(pre.netDebt(), llm.netDebt()),
				first(pre.marketCap(), llm.marketCap()),
				first(pre.sharesOutstanding(), llm.sharesOutstanding()),
				first(pre.evToEbitda(), llm.evToEbitda()),
				first(pre.netRent(), llm.netRent()),
				first(pre.netRentCurrency(), llm.netRentCurrency()),
				first(pre.netRentPeriodEnd(), llm.netRentPeriodEnd()),
				first(pre.netRentPeriodType(), llm.netRentPeriodType()),
				first(pre.noi(), llm.noi()),
				first(pre.noiCurrency(), llm.noiCurrency()),
				first(pre.noiPeriodEnd(), llm.noiPeriodEnd()),
				first(pre.noiPeriodType(), llm.noiPeriodType()),
				first(pre.affo(), llm.affo()),
				first(pre.affoCurrency(), llm.affoCurrency()),
				first(pre.affoPeriodEnd(), llm.affoPeriodEnd()),
				first(pre.affoPeriodType(), llm.affoPeriodType()),
				first(pre.ffo(), llm.ffo()),
				first(pre.ffoCurrency(), llm.ffoCurrency()),
				first(pre.ffoPeriodEnd(), llm.ffoPeriodEnd()),
				first(pre.ffoPeriodType(), llm.ffoPeriodType()),
				first(pre.ffoType(), llm.ffoType()),
				first(pre.price(), llm.price()),
				first(pre.priceCurrency(), llm.priceCurrency()),
				first(pre.priceAsOf(), llm.priceAsOf()),
				first(pre.epsType(), llm.epsType()),
				first(pre.epsNorm(), llm.epsNorm()),
				first(pre.epsNormYearsUsed(), llm.epsNormYearsUsed()),
				first(pre.epsNormYearsAvailable(), llm.epsNormYearsAvailable()),
				mergeEpsHistory(pre.epsHistory(), llm.epsHistory()),
				first(pre.epsFloorPolicy(), llm.epsFloorPolicy()),
				first(pre.epsFloorValue(), llm.epsFloorValue()),
				first(pre.epsNormPeriodEnd(), llm.epsNormPeriodEnd()),
				first(pre.peLongterm(), llm.peLongterm()),
				first(pre.earningsYieldLongterm(), llm.earningsYieldLongterm()),
				first(pre.peCurrent(), llm.peCurrent()),
				first(pre.peCurrentAsOf(), llm.peCurrentAsOf()),
				first(pre.pbCurrent(), llm.pbCurrent()),
				first(pre.pbCurrentAsOf(), llm.pbCurrentAsOf()),
				first(pre.peTtmHoldings(), llm.peTtmHoldings()),
				first(pre.earningsYieldTtmHoldings(), llm.earningsYieldTtmHoldings()),
				first(pre.holdingsCoverageWeightPct(), llm.holdingsCoverageWeightPct()),
				first(pre.holdingsCoverageCount(), llm.holdingsCoverageCount()),
				first(pre.holdingsAsOf(), llm.holdingsAsOf()),
				first(pre.holdingsWeightMethod(), llm.holdingsWeightMethod()),
				first(pre.peMethod(), llm.peMethod()),
				first(pre.peHorizon(), llm.peHorizon()),
				first(pre.negEarningsHandling(), llm.negEarningsHandling())
		);
	}

	private List<InstrumentDossierExtractionPayload.EpsHistoryPayload> mergeEpsHistory(
			List<InstrumentDossierExtractionPayload.EpsHistoryPayload> pre,
			List<InstrumentDossierExtractionPayload.EpsHistoryPayload> llm) {
		if (pre == null || pre.isEmpty()) {
			return llm;
		}
		if (llm == null || llm.isEmpty()) {
			return pre;
		}
		java.util.LinkedHashMap<Integer, InstrumentDossierExtractionPayload.EpsHistoryPayload> merged =
				new java.util.LinkedHashMap<>();
		List<InstrumentDossierExtractionPayload.EpsHistoryPayload> extra = new ArrayList<>();
		for (InstrumentDossierExtractionPayload.EpsHistoryPayload item : llm) {
			if (item == null) {
				continue;
			}
			if (item.year() == null) {
				extra.add(item);
			} else {
				merged.put(item.year(), item);
			}
		}
		for (InstrumentDossierExtractionPayload.EpsHistoryPayload item : pre) {
			if (item == null) {
				continue;
			}
			if (item.year() == null) {
				extra.add(item);
			} else {
				merged.put(item.year(), item);
			}
		}
		List<InstrumentDossierExtractionPayload.EpsHistoryPayload> result = new ArrayList<>(merged.values());
		result.addAll(extra);
		return result.isEmpty() ? null : result;
	}

	private <T> List<T> mergeList(List<T> pre, List<T> llm) {
		if (pre != null && !pre.isEmpty()) {
			return pre;
		}
		return llm;
	}

	private <T> T first(T pre, T llm) {
		return pre != null ? pre : llm;
	}

	private List<InstrumentDossierExtractionPayload.WarningPayload> mergeWarnings(
			List<InstrumentDossierExtractionPayload.WarningPayload> pre,
			List<InstrumentDossierExtractionPayload.WarningPayload> llm) {
		List<InstrumentDossierExtractionPayload.WarningPayload> merged = new ArrayList<>();
		if (pre != null) {
			merged.addAll(pre);
		}
		if (llm != null) {
			merged.addAll(llm);
		}
		return merged.isEmpty() ? null : merged;
	}

	private List<InstrumentDossierExtractionPayload.WarningPayload> appendWarning(
			List<InstrumentDossierExtractionPayload.WarningPayload> warnings,
			String message) {
		if (message == null || message.isBlank()) {
			return warnings;
		}
		List<InstrumentDossierExtractionPayload.WarningPayload> next = new ArrayList<>();
		if (warnings != null) {
			next.addAll(warnings);
		}
		next.add(new InstrumentDossierExtractionPayload.WarningPayload(message));
		return next;
	}

	private record PreParseAssessment(
			boolean useLlm,
			List<InstrumentDossierExtractionPayload.MissingFieldPayload> missingFields
	) {
	}

	private List<InstrumentDossierExtractionPayload.MissingFieldPayload> filterMissingFields(
			List<InstrumentDossierExtractionPayload.MissingFieldPayload> missing,
			InstrumentDossierExtractionPayload payload) {
		if (missing == null || missing.isEmpty() || payload == null) {
			return missing;
		}
		List<InstrumentDossierExtractionPayload.MissingFieldPayload> filtered = new ArrayList<>();
		for (InstrumentDossierExtractionPayload.MissingFieldPayload item : missing) {
			if (item == null || item.field() == null) {
				continue;
			}
			if (!isFieldPresent(payload, item.field())) {
				filtered.add(item);
			}
		}
		return filtered.isEmpty() ? null : filtered;
	}

	private boolean isFieldPresent(InstrumentDossierExtractionPayload payload, String field) {
		String normalized = field.toLowerCase(Locale.ROOT).trim();
		if (normalized.startsWith("valuation.")) {
			normalized = normalized.substring("valuation.".length());
		}
		if (normalized.startsWith("financials.")) {
			normalized = normalized.substring("financials.".length());
		}
		switch (normalized) {
			case "name" -> {
				return payload.name() != null;
			}
			case "instrument_type" -> {
				return payload.instrumentType() != null;
			}
			case "asset_class" -> {
				return payload.assetClass() != null;
			}
			case "sub_class" -> {
				return payload.subClass() != null;
			}
			case "layer" -> {
				return payload.layer() != null;
			}
			case "layer_notes" -> {
				return payload.layerNotes() != null;
			}
			case "etf.ongoing_charges_pct", "ongoing_charges_pct", "ter" -> {
				return payload.etf() != null && payload.etf().ongoingChargesPct() != null;
			}
			case "etf.benchmark_index", "benchmark_index" -> {
				return payload.etf() != null && payload.etf().benchmarkIndex() != null;
			}
			case "risk.summary_risk_indicator.value", "summary_risk_indicator", "sri" -> {
				return payload.risk() != null
						&& payload.risk().summaryRiskIndicator() != null
						&& payload.risk().summaryRiskIndicator().value() != null;
			}
			case "financials" -> {
				return payload.financials() != null;
			}
			case "valuation" -> {
				return payload.valuation() != null;
			}
			case "revenue" -> {
				return payload.financials() != null && payload.financials().revenue() != null;
			}
			case "net_income" -> {
				return payload.financials() != null && payload.financials().netIncome() != null;
			}
			case "dividend_per_share" -> {
				return payload.financials() != null && payload.financials().dividendPerShare() != null;
			}
			case "price" -> {
				return payload.valuation() != null && payload.valuation().price() != null;
			}
			case "pe_current" -> {
				return payload.valuation() != null && payload.valuation().peCurrent() != null;
			}
			case "pb_current" -> {
				return payload.valuation() != null && payload.valuation().pbCurrent() != null;
			}
			case "market_cap" -> {
				return payload.valuation() != null && payload.valuation().marketCap() != null;
			}
			case "shares_outstanding" -> {
				return payload.valuation() != null && payload.valuation().sharesOutstanding() != null;
			}
			case "ebitda" -> {
				return payload.valuation() != null && payload.valuation().ebitda() != null;
			}
			case "enterprise_value" -> {
				return payload.valuation() != null && payload.valuation().enterpriseValue() != null;
			}
			case "net_debt" -> {
				return payload.valuation() != null && payload.valuation().netDebt() != null;
			}
			case "ev_to_ebitda" -> {
				return payload.valuation() != null && payload.valuation().evToEbitda() != null;
			}
			case "eps_history" -> {
				return payload.valuation() != null && payload.valuation().epsHistory() != null
						&& !payload.valuation().epsHistory().isEmpty();
			}
			case "eps_norm" -> {
				return payload.valuation() != null && payload.valuation().epsNorm() != null;
			}
			case "pe_longterm" -> {
				return payload.valuation() != null && payload.valuation().peLongterm() != null;
			}
			case "earnings_yield_longterm" -> {
				return payload.valuation() != null && payload.valuation().earningsYieldLongterm() != null;
			}
			case "pe_ttm_holdings" -> {
				return payload.valuation() != null && payload.valuation().peTtmHoldings() != null;
			}
			case "earnings_yield_ttm_holdings" -> {
				return payload.valuation() != null && payload.valuation().earningsYieldTtmHoldings() != null;
			}
			case "holdings_coverage_weight_pct" -> {
				return payload.valuation() != null && payload.valuation().holdingsCoverageWeightPct() != null;
			}
			case "holdings_coverage_count" -> {
				return payload.valuation() != null && payload.valuation().holdingsCoverageCount() != null;
			}
			case "holdings_asof" -> {
				return payload.valuation() != null && payload.valuation().holdingsAsOf() != null;
			}
			case "holdings_weight_method" -> {
				return payload.valuation() != null && payload.valuation().holdingsWeightMethod() != null;
			}
			case "pe_method" -> {
				return payload.valuation() != null && payload.valuation().peMethod() != null;
			}
			case "pe_horizon" -> {
				return payload.valuation() != null && payload.valuation().peHorizon() != null;
			}
			case "neg_earnings_handling" -> {
				return payload.valuation() != null && payload.valuation().negEarningsHandling() != null;
			}
			default -> {
				return false;
			}
		}
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
			InstrumentDossierExtractionPayload.ValuationPayload valuation,
			String gicsSector,
			String gicsIndustryGroup,
			String gicsIndustry,
			String gicsSubIndustry,
			List<InstrumentDossierExtractionPayload.SectorExposurePayload> sectors,
			String instrumentTypeHint
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
		boolean isEtf = isEtfType(instrumentTypeHint);
		boolean isSingleStock = isSingleStockType(instrumentTypeHint);
		if (isSingleStock) {
			addMissing(missing, "gics_sector", gicsSector);
			addMissing(missing, "gics_industry_group", gicsIndustryGroup);
			addMissing(missing, "gics_industry", gicsIndustry);
			addMissing(missing, "gics_sub_industry", gicsSubIndustry);
		}
		if (isEtf) {
			addMissing(missing, "sectors", sectors);
		}
		return missing;
	}

	private void addMissing(List<InstrumentDossierExtractionPayload.MissingFieldPayload> missing, String field, Object value) {
		if (value == null) {
			missing.add(new InstrumentDossierExtractionPayload.MissingFieldPayload(field, "Not found in dossier content."));
		}
	}

	private List<InstrumentDossierExtractionPayload.MissingFieldPayload> augmentMissingFields(
			List<InstrumentDossierExtractionPayload.MissingFieldPayload> missing,
			InstrumentDossierExtractionPayload.ValuationPayload valuation,
			String instrumentType,
			Integer layer) {
		if (valuation == null) {
			return missing;
		}
		boolean singleStock = isSingleStockType(instrumentType) || (layer != null && layer == 4);
		if (!singleStock) {
			return missing;
		}
		List<InstrumentDossierExtractionPayload.MissingFieldPayload> updated = missing == null
				? new ArrayList<>()
				: new ArrayList<>(missing);
		updated = addMissingIfAbsent(updated, "valuation.eps_history",
				valuation.epsHistory() == null || valuation.epsHistory().isEmpty() ? null : valuation.epsHistory());
		updated = addMissingIfAbsent(updated, "valuation.price", valuation.price());
		updated = addMissingIfAbsent(updated, "valuation.pe_current", valuation.peCurrent());
		updated = addMissingIfAbsent(updated, "valuation.pb_current", valuation.pbCurrent());
		return updated.isEmpty() ? null : updated;
	}

	private List<InstrumentDossierExtractionPayload.MissingFieldPayload> addMissingIfAbsent(
			List<InstrumentDossierExtractionPayload.MissingFieldPayload> missing,
			String field,
			Object value) {
		if (value != null || field == null || field.isBlank()) {
			return missing;
		}
		if (missing == null) {
			missing = new ArrayList<>();
		}
		for (InstrumentDossierExtractionPayload.MissingFieldPayload item : missing) {
			if (item != null && field.equalsIgnoreCase(item.field())) {
				return missing;
			}
		}
		missing.add(new InstrumentDossierExtractionPayload.MissingFieldPayload(field, "Not found in dossier content."));
		return missing;
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

	private boolean hasRiskSection(InstrumentDossier dossier) {
		if (dossier == null) {
			return false;
		}
		String content = dossier.getContentMd();
		if (content == null || content.isBlank()) {
			return false;
		}
		return RISK_SECTION_PATTERN.matcher(content).find();
	}

	private List<InstrumentDossierExtractionPayload.MissingFieldPayload> sanitizeMissingFields(
			List<InstrumentDossierExtractionPayload.MissingFieldPayload> missingFields,
			String instrumentType,
			List<InstrumentDossierExtractionPayload.RegionExposurePayload> regions,
			List<InstrumentDossierExtractionPayload.SectorExposurePayload> sectors,
			List<InstrumentDossierExtractionPayload.HoldingPayload> topHoldings) {
		if (missingFields == null || missingFields.isEmpty()) {
			return missingFields;
		}
		boolean hasRegions = regions != null && !regions.isEmpty();
		boolean hasSectors = sectors != null && !sectors.isEmpty();
		boolean hasHoldings = topHoldings != null && !topHoldings.isEmpty();
		boolean isEtf = isEtfType(instrumentType);
		List<InstrumentDossierExtractionPayload.MissingFieldPayload> filtered = new ArrayList<>();
		for (InstrumentDossierExtractionPayload.MissingFieldPayload item : missingFields) {
			if (item == null || item.field() == null || item.field().isBlank()) {
				continue;
			}
			String field = item.field().toLowerCase(Locale.ROOT).trim();
			if (hasHoldings && (field.equals("top_holdings") || field.equals("topholdings"))) {
				continue;
			}
			if (hasRegions && field.equals("regions")) {
				continue;
			}
			if (hasSectors && field.equals("sectors")) {
				continue;
			}
			if (isEtf && field.startsWith("gics_")) {
				continue;
			}
			if (!isEtf && isEtfOnlyMissingField(field)) {
				continue;
			}
			filtered.add(item);
		}
		return filtered.isEmpty() ? null : filtered;
	}

	private boolean isEtfOnlyMissingField(String normalizedField) {
		if (normalizedField == null || normalizedField.isBlank()) {
			return false;
		}
		if (normalizedField.startsWith("etf.") || normalizedField.startsWith("etf_") || normalizedField.equals("etf")) {
			return true;
		}
		return switch (normalizedField) {
			case "ter",
					"ongoing_charges_pct",
					"ongoing charges pct",
					"ongoing charges",
					"ongoing charge",
					"total expense ratio",
					"benchmark_index",
					"benchmark index" -> true;
			default -> false;
		};
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

	private ThemeLayerDecision forceThemeLayerIfNeeded(Integer layer,
									   String instrumentType,
									   String name,
									   String subClass,
									   String layerNotes) {
		if (instrumentType == null) {
			return new ThemeLayerDecision(layer, layerNotes);
		}
		String type = instrumentType.toLowerCase(Locale.ROOT);
		if (!(type.contains("etf") || type.contains("fund") || type.contains("etp"))) {
			return new ThemeLayerDecision(layer, layerNotes);
		}
		String combined = String.join(" ",
				name == null ? "" : name,
				subClass == null ? "" : subClass,
				layerNotes == null ? "" : layerNotes
		).toLowerCase(Locale.ROOT);
		for (String keyword : THEMATIC_KEYWORDS) {
			if (combined.contains(keyword)) {
				boolean changed = layer == null || layer != 3;
				String updatedNotes = normalizeThemeNotes(layerNotes);
				if (changed) {
					updatedNotes = appendPostprocessorHint(updatedNotes);
				}
				return new ThemeLayerDecision(3, updatedNotes);
			}
		}
		return new ThemeLayerDecision(layer, layerNotes);
	}

	private boolean isEtfType(String instrumentType) {
		if (instrumentType == null || instrumentType.isBlank()) {
			return false;
		}
		String normalized = instrumentType.toLowerCase(Locale.ROOT);
		return normalized.contains("etf") || normalized.contains("fund") || normalized.contains("etp");
	}

	private boolean isSingleStockType(String instrumentType) {
		if (instrumentType == null || instrumentType.isBlank()) {
			return false;
		}
		String normalized = instrumentType.toLowerCase(Locale.ROOT);
		return normalized.contains("equity") || normalized.contains("stock") || normalized.contains("reit");
	}

	private String normalizeThemeNotes(String layerNotes) {
		if (layerNotes == null || layerNotes.isBlank()) {
			return "Thematic ETF";
		}
		String normalized = layerNotes.toLowerCase(Locale.ROOT);
		if (normalized.contains("layer 1")
				|| normalized.contains("layer 2")
				|| normalized.contains("core-plus")
				|| normalized.contains("core plus")
				|| normalized.contains("core")) {
			return "Thematic ETF";
		}
		return layerNotes;
	}

	private String appendPostprocessorHint(String notes) {
		String hint = "Layer overridden by extraction postprocessor";
		if (notes == null || notes.isBlank()) {
			return hint;
		}
		if (notes.contains(hint)) {
			return notes;
		}
		return notes + " (" + hint + ")";
	}

	private record ThemeLayerDecision(Integer layer, String layerNotes) {
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

	private List<InstrumentDossierExtractionPayload.SectorExposurePayload> parseSectors(
			JsonNode data,
			List<InstrumentDossierExtractionPayload.WarningPayload> warnings) {
		JsonNode array = arrayOrNull(data, "sectors", "sector_allocations", "sectorAllocations");
		return parseSectorWeights(array, warnings);
	}

	private List<InstrumentDossierExtractionPayload.SectorExposurePayload> applySingleStockSectorFallback(
			List<InstrumentDossierExtractionPayload.SectorExposurePayload> sectors,
			String gicsSector,
			String instrumentType,
			List<InstrumentDossierExtractionPayload.WarningPayload> warnings) {
		if (sectors != null && !sectors.isEmpty()) {
			return sectors;
		}
		if (!isSingleStockType(instrumentType)) {
			return sectors;
		}
		if (gicsSector == null || gicsSector.isBlank()) {
			return sectors;
		}
		List<InstrumentDossierExtractionPayload.SectorExposurePayload> fallback = new ArrayList<>();
		fallback.add(new InstrumentDossierExtractionPayload.SectorExposurePayload(gicsSector, new BigDecimal("100")));
		warnings.add(new InstrumentDossierExtractionPayload.WarningPayload(
				"Sector exposures missing; defaulted to GICS sector " + gicsSector + " at 100% for single stock."
		));
		return fallback;
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

	private List<InstrumentDossierExtractionPayload.SectorExposurePayload> parseSectorWeights(
			JsonNode array,
			List<InstrumentDossierExtractionPayload.WarningPayload> warnings) {
		if (array == null || !array.isArray()) {
			return null;
		}
		List<InstrumentDossierExtractionPayload.SectorExposurePayload> result = new ArrayList<>();
		for (JsonNode item : array) {
			if (item == null || !item.isObject()) {
				continue;
			}
			String name = textOrNull(item, "name", "sector");
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
			result.add(new InstrumentDossierExtractionPayload.SectorExposurePayload(name, weight));
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

	private List<InstrumentDossierExtractionPayload.HoldingPayload> sanitizeEtfHoldings(
			List<InstrumentDossierExtractionPayload.HoldingPayload> holdings,
			String instrumentType,
			Integer layer,
			List<InstrumentDossierExtractionPayload.WarningPayload> warnings) {
		if (holdings == null || holdings.isEmpty()) {
			return holdings;
		}
		if (!isEtfType(instrumentType)) {
			return holdings;
		}
		BigDecimal threshold = resolveEtfHoldingThreshold(holdings.size(), layer);
		boolean hasSmall = false;
		for (InstrumentDossierExtractionPayload.HoldingPayload holding : holdings) {
			if (holding == null || holding.weightPct() == null) {
				continue;
			}
			BigDecimal normalized = normalizeWeightValue(holding.weightPct());
			if (normalized != null && normalized.compareTo(new BigDecimal("0.10")) <= 0) {
				hasSmall = true;
				break;
			}
		}
		if (!hasSmall) {
			return holdings;
		}
		List<InstrumentDossierExtractionPayload.HoldingPayload> sanitized = new ArrayList<>();
		int removed = 0;
		for (InstrumentDossierExtractionPayload.HoldingPayload holding : holdings) {
			if (holding == null) {
				continue;
			}
			BigDecimal normalized = normalizeWeightValue(holding.weightPct());
			if (normalized != null && threshold != null && normalized.compareTo(threshold) > 0) {
				removed++;
				continue;
			}
			sanitized.add(holding);
		}
		if (removed > 0 && sanitized.size() >= Math.min(3, holdings.size())) {
			warnings.add(new InstrumentDossierExtractionPayload.WarningPayload(
					"Removed " + removed + " ETF top holding(s) with weight_pct > "
							+ threshold.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString()
							+ "% (likely extraction error)."
			));
			return sanitized;
		}
		return holdings;
	}

	private BigDecimal resolveEtfHoldingThreshold(int count, Integer layer) {
		if (layer != null && layer == 1) {
			return new BigDecimal("0.30");
		}
		if (count >= 10) {
			return new BigDecimal("0.30");
		}
		if (count >= 5) {
			return new BigDecimal("0.40");
		}
		return new BigDecimal("0.60");
	}

	private BigDecimal normalizeWeightValue(BigDecimal weight) {
		if (weight == null || weight.signum() <= 0) {
			return null;
		}
		return weight.compareTo(BigDecimal.ONE) > 0
				? weight.divide(new BigDecimal("100"), 6, java.math.RoundingMode.HALF_UP)
				: weight;
	}
}
