package my.portfoliomanager.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmClient;
import my.portfoliomanager.app.llm.LlmClient;
import my.portfoliomanager.app.llm.LlmSuggestion;
import my.portfoliomanager.app.repository.InstrumentDossierExtractionRepository;
import my.portfoliomanager.app.repository.InstrumentDossierRepository;
import my.portfoliomanager.app.repository.InstrumentFactRepository;
import my.portfoliomanager.app.repository.InstrumentOverrideRepository;
import my.portfoliomanager.app.repository.InstrumentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceBulkWebsearchTest {
	@Test
	void createDossierDraftsViaWebsearchBulk_usesSchemaAndParsesOutput() {
		AtomicReference<String> schemaName = new AtomicReference<>();
		AtomicReference<Map<String, Object>> schema = new AtomicReference<>();
		LlmClient llmClient = new LlmClient() {
			@Override
			public LlmSuggestion suggestReclassification(String context) {
				return new LlmSuggestion("", "test");
			}

			@Override
			public LlmSuggestion suggestSavingPlanProposal(String context) {
				return new LlmSuggestion("", "test");
			}

			@Override
			public LlmSuggestion createInstrumentDossierViaWebSearch(String context,
																	 String schemaNameArg,
																	 Map<String, Object> schemaArg) {
				schemaName.set(schemaNameArg);
				schema.set(schemaArg);
				return new LlmSuggestion("""
						{
						  "items": [
						    {
						      "isin": "DE0000000001",
						      "contentMd": "# DE0000000001 - Test",
						      "displayName": "Test",
						      "citations": [
						        {
						          "id": "1",
						          "title": "Source",
						          "url": "https://example.com",
						          "publisher": "Example",
						          "accessed_at": "2024-01-01"
						        }
						      ],
						      "error": null
						    }
						  ]
						}
						""", "test-model");
			}
		};
		KnowledgeBaseConfigService configService = mock(KnowledgeBaseConfigService.class);
		when(configService.getSnapshot()).thenReturn(defaultSnapshot());

		KnowledgeBaseService service = new KnowledgeBaseService(
				mock(InstrumentRepository.class),
				mock(InstrumentDossierRepository.class),
				mock(InstrumentDossierExtractionRepository.class),
				mock(InstrumentOverrideRepository.class),
				mock(InstrumentFactRepository.class),
				mock(AuditService.class),
				mock(ExtractorService.class),
				mock(KnowledgeBaseExtractionService.class),
				configService,
				mock(KnowledgeBaseLlmClient.class),
				mock(KnowledgeBaseRunService.class),
				llmClient,
				new ObjectMapper()
		);

		KnowledgeBaseService.BulkWebsearchDraftResult result =
				service.createDossierDraftsViaWebsearchBulk(List.of("DE0000000001"));

		assertThat(schemaName.get()).isEqualTo("kb_bulk_dossier_websearch");
		assertThat(schema.get()).isNotNull();
		assertThat(result.items()).hasSize(1);
		assertThat(result.items().getFirst().isin()).isEqualTo("DE0000000001");
		assertThat(result.items().getFirst().contentMd()).contains("# DE0000000001");
		assertThat(result.items().getFirst().error()).isNull();
	}

	private KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot defaultSnapshot() {
		return new KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot(
				true,
				30,
				false,
				false,
				false,
				10,
				120000,
				2,
				5,
				300,
				100,
				2,
				1,
				1,
				15000,
				7,
				30,
				List.of("example.com")
		);
	}
}
