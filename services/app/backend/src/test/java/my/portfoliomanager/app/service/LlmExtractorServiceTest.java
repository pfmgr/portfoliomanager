package my.portfoliomanager.app.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import my.portfoliomanager.app.domain.InstrumentDossier;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmClient;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmExtractionDraft;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmExtractorServiceTest {

	@Test
	void extract_parserOnlyPathForcesLayer3ForRealEstateEtf() {
		KnowledgeBaseLlmClient llmClient = mock(KnowledgeBaseLlmClient.class);
		DossierPreParser preParser = mock(DossierPreParser.class);
		when(preParser.parse(any())).thenReturn(realEstatePreParsedPayload(null));

		LlmExtractorService service = new LlmExtractorService(
				llmClient,
				new ObjectMapper(),
				preParser,
				null,
				null
		);

		ExtractionResult result = service.extract(buildDossier());

		assertThat(result.model()).isEqualTo("parser");
		assertThat(result.payload().layer()).isEqualTo(3);
		assertThat(result.payload().layerNotes()).contains("keyword=real estate");
		assertThat(result.payload().warnings())
				.extracting(InstrumentDossierExtractionPayload.WarningPayload::message)
				.anyMatch(message -> message.contains("Layer forced to 3 (Themes) by extraction postprocessor"));
		verify(llmClient, never()).extractMetadata(anyString());
	}

	@Test
	void extract_parserPathClearsResolvedLayerMissingFields() {
		KnowledgeBaseLlmClient llmClient = mock(KnowledgeBaseLlmClient.class);
		DossierPreParser preParser = mock(DossierPreParser.class);
		List<InstrumentDossierExtractionPayload.MissingFieldPayload> missing = List.of(
				new InstrumentDossierExtractionPayload.MissingFieldPayload("layer", "missing"),
				new InstrumentDossierExtractionPayload.MissingFieldPayload("layer_notes", "missing")
		);
		when(preParser.parse(any())).thenReturn(realEstatePreParsedPayload(missing));

		LlmExtractorService service = new LlmExtractorService(
				llmClient,
				new ObjectMapper(),
				preParser,
				null,
				null
		);

		ExtractionResult result = service.extract(buildDossier());

		assertThat(result.model()).isEqualTo("parser");
		assertThat(result.payload().missingFields()).isNull();
		assertThat(result.payload().layer()).isEqualTo(3);
		verify(llmClient, never()).extractMetadata(anyString());
	}

	@Test
	void extract_mergePathForcesLayer3AfterPreparserLayer2() {
		KnowledgeBaseLlmClient llmClient = mock(KnowledgeBaseLlmClient.class);
		DossierPreParser preParser = mock(DossierPreParser.class);
		ObjectMapper mapper = new ObjectMapper();

		List<InstrumentDossierExtractionPayload.MissingFieldPayload> missing = List.of(
				new InstrumentDossierExtractionPayload.MissingFieldPayload("risk.summary_risk_indicator.value", "missing")
		);
		when(preParser.parse(any())).thenReturn(genericPreParsedPayload(missing));
		when(llmClient.extractMetadata(anyString())).thenReturn(new KnowledgeBaseLlmExtractionDraft(
				llmExtractionJson(mapper),
				"llm-model"
		));

		LlmExtractorService service = new LlmExtractorService(
				llmClient,
				mapper,
				preParser,
				null,
				null
		);

		ExtractionResult result = service.extract(buildDossier());

		assertThat(result.model()).isEqualTo("llm-model");
		assertThat(result.payload().layer()).isEqualTo(3);
		List<String> warningMessages = result.payload().warnings()
				.stream()
				.map(InstrumentDossierExtractionPayload.WarningPayload::message)
				.toList();
		assertThat(warningMessages)
				.anyMatch(message -> message.contains("keyword 'real estate'"));
		assertThat(warningMessages)
				.filteredOn(message -> message.contains("Layer forced to 3 (Themes) by extraction postprocessor"))
				.hasSize(1);
		verify(llmClient).extractMetadata(anyString());
	}

	@Test
	void extract_parserOnlyPathForcesLayer3ForSectorEtf() {
		KnowledgeBaseLlmClient llmClient = mock(KnowledgeBaseLlmClient.class);
		DossierPreParser preParser = mock(DossierPreParser.class);
		when(preParser.parse(any())).thenReturn(sectorPreParsedPayload(null));

		LlmExtractorService service = new LlmExtractorService(
				llmClient,
				new ObjectMapper(),
				preParser,
				null,
				null
		);

		ExtractionResult result = service.extract(buildDossier());

		assertThat(result.model()).isEqualTo("parser");
		assertThat(result.payload().layer()).isEqualTo(3);
		assertThat(result.payload().layerNotes()).contains("keyword=sector");
		verify(llmClient, never()).extractMetadata(anyString());
	}

	@Test
	void extract_parserOnlyPathDoesNotTreatFundamentalAsFundHint() {
		KnowledgeBaseLlmClient llmClient = mock(KnowledgeBaseLlmClient.class);
		DossierPreParser preParser = mock(DossierPreParser.class);
		when(preParser.parse(any())).thenReturn(fundamentalPreParsedPayload());

		LlmExtractorService service = new LlmExtractorService(
				llmClient,
				new ObjectMapper(),
				preParser,
				null,
				null
		);

		ExtractionResult result = service.extract(buildDossier());

		assertThat(result.model()).isEqualTo("parser");
		assertThat(result.payload().layer()).isEqualTo(2);
		assertThat(result.payload().warnings())
				.extracting(InstrumentDossierExtractionPayload.WarningPayload::message)
				.noneMatch(message -> message.contains("Layer forced to 3 (Themes) by extraction postprocessor"));
		verify(llmClient, never()).extractMetadata(anyString());
	}

	private InstrumentDossier buildDossier() {
		InstrumentDossier dossier = new InstrumentDossier();
		dossier.setIsin("DE000A0Q4R44");
		dossier.setContentMd("# DE000A0Q4R44 - Test\n\n## Risk (SRI and notes)\n- SRI: unknown\n");
		return dossier;
	}

	private InstrumentDossierExtractionPayload realEstatePreParsedPayload(
			List<InstrumentDossierExtractionPayload.MissingFieldPayload> missingFields) {
		return new InstrumentDossierExtractionPayload(
				"DE000A0Q4R44",
				"iShares STOXX Europe 600 Real Estate",
				"Equity",
				"Real Estate",
				"Regional Equity",
				null,
				null,
				null,
				null,
				2,
				"Core-Plus; Europe exposure",
				new InstrumentDossierExtractionPayload.EtfPayload(new BigDecimal("0.46"), "STOXX Europe 600 Real Estate"),
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				missingFields,
				null
		);
	}

	private InstrumentDossierExtractionPayload genericPreParsedPayload(
			List<InstrumentDossierExtractionPayload.MissingFieldPayload> missingFields) {
		return new InstrumentDossierExtractionPayload(
				"DE000A0Q4R44",
				"Europe Equity",
				"Equity",
				"Equity",
				"Regional Equity",
				null,
				null,
				null,
				null,
				2,
				"Core-Plus; Europe exposure",
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				missingFields,
				null
		);
	}

	private InstrumentDossierExtractionPayload sectorPreParsedPayload(
			List<InstrumentDossierExtractionPayload.MissingFieldPayload> missingFields) {
		return new InstrumentDossierExtractionPayload(
				"DE000A0Q4R44",
				"iShares STOXX Europe 600 Technology Sector",
				"Equity",
				"Equity",
				"Regional Equity",
				null,
				null,
				null,
				null,
				2,
				"Core-Plus; Europe exposure",
				new InstrumentDossierExtractionPayload.EtfPayload(new BigDecimal("0.46"), "STOXX Europe 600 Technology Sector"),
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				missingFields,
				null
		);
	}

	private InstrumentDossierExtractionPayload fundamentalPreParsedPayload() {
		return new InstrumentDossierExtractionPayload(
				"DE000A0Q4R44",
				"Europe Fundamental Real Estate Equity",
				"Equity",
				"Equity",
				"Fundamental Equity",
				null,
				null,
				null,
				null,
				2,
				"Core-Plus; Europe exposure",
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null
		);
	}

	private ObjectNode llmExtractionJson(ObjectMapper mapper) {
		ObjectNode root = mapper.createObjectNode();
		root.put("isin", "DE000A0Q4R44");
		root.put("name", "Europe Equity UCITS ETF");
		root.put("instrument_type", "ETF");
		root.put("asset_class", "Equity");
		root.put("sub_class", "Regional Equity");
		root.putNull("gics_sector");
		root.putNull("gics_industry_group");
		root.putNull("gics_industry");
		root.putNull("gics_sub_industry");
		root.put("layer", 2);
		root.put("layer_notes", "Core-Plus; Europe exposure");
		ObjectNode etf = root.putObject("etf");
		etf.put("ongoing_charges_pct", 0.46);
		etf.put("benchmark_index", "STOXX Europe 600 Real Estate");
		root.putNull("risk");
		root.putArray("regions");
		root.putArray("sectors");
		root.putArray("top_holdings");
		root.putNull("financials");
		root.putNull("valuation");
		root.putArray("missing_fields");
		root.putArray("warnings");
		return root;
	}
}
