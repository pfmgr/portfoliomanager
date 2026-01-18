package my.portfoliomanager.app.service;

import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmClient;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmAlternativesDraft;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmProvider;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmResponse;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmOutputException;
import my.portfoliomanager.app.llm.LlmRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseLlmServiceTest {
	@Test
	void generateDossier_retriesOnRetryableErrors() {
		AtomicInteger calls = new AtomicInteger();
		AtomicReference<String> schemaName = new AtomicReference<>();
		KnowledgeBaseLlmProvider provider = new KnowledgeBaseLlmProvider() {
			@Override
			public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains) {
				throw new UnsupportedOperationException("Use schema-based call");
			}

			@Override
			public KnowledgeBaseLlmResponse runWebSearch(String prompt,
														List<String> allowedDomains,
														String schemaNameArg,
														Map<String, Object> schema) {
				schemaName.set(schemaNameArg);
				int attempt = calls.incrementAndGet();
				if (attempt < 2) {
					throw new LlmRequestException("rate_limit", 429, true, null);
				}
				return new KnowledgeBaseLlmResponse("{\"contentMd\":\"# DE0000000001 - Test\",\"displayName\":\"Test\",\"citations\":[{\"id\":\"1\",\"title\":\"Source\",\"url\":\"https://example.com\",\"publisher\":\"Example\",\"accessed_at\":\"2024-01-01\"}]}", "test-model");
			}

			@Override
			public KnowledgeBaseLlmResponse runJsonPrompt(String prompt) {
				throw new UnsupportedOperationException();
			}
		};
		KnowledgeBaseConfigService configService = mock(KnowledgeBaseConfigService.class);
		when(configService.getSnapshot()).thenReturn(defaultSnapshot());
		KnowledgeBaseLlmClient client = new KnowledgeBaseLlmService(provider, configService, new ObjectMapper());

		client.generateDossier("DE0000000001", null, List.of("example.com"), 15000);

		assertThat(calls.get()).isEqualTo(2);
		assertThat(schemaName.get()).isEqualTo("kb_dossier_websearch");
	}

	@Test
	void findAlternatives_filtersBaseIsinAndDuplicates() {
		AtomicReference<String> schemaName = new AtomicReference<>();
		KnowledgeBaseLlmProvider provider = new KnowledgeBaseLlmProvider() {
			@Override
			public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains) {
				throw new UnsupportedOperationException("Use schema-based call");
			}

			@Override
			public KnowledgeBaseLlmResponse runWebSearch(String prompt,
														List<String> allowedDomains,
														String schemaNameArg,
														Map<String, Object> schema) {
				schemaName.set(schemaNameArg);
				return new KnowledgeBaseLlmResponse("""
						{
						  "items": [
						    { "isin": "DE0000000001", "rationale": "Same instrument", "citations": [ { "id": "1" } ] },
						    { "isin": "DE0000000002", "rationale": "Alt", "citations": [ { "id": "2" } ] },
						    { "isin": "DE0000000002", "rationale": "Duplicate", "citations": [ { "id": "3" } ] }
						  ]
						}
						""", "test-model");
			}

			@Override
			public KnowledgeBaseLlmResponse runJsonPrompt(String prompt) {
				throw new UnsupportedOperationException();
			}
		};
		KnowledgeBaseConfigService configService = mock(KnowledgeBaseConfigService.class);
		when(configService.getSnapshot()).thenReturn(defaultSnapshot());
		KnowledgeBaseLlmClient client = new KnowledgeBaseLlmService(provider, configService, new ObjectMapper());

		KnowledgeBaseLlmAlternativesDraft draft = client.findAlternatives("DE0000000001", List.of("example.com"));

		assertThat(draft.items()).hasSize(1);
		assertThat(draft.items().getFirst().isin()).isEqualTo("DE0000000002");
		assertThat(schemaName.get()).isEqualTo("kb_alternatives_websearch");
	}

	@Test
	void findAlternatives_rejectsOnlyBaseIsin() {
		KnowledgeBaseLlmProvider provider = new KnowledgeBaseLlmProvider() {
			@Override
			public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains) {
				return new KnowledgeBaseLlmResponse("""
						{
						  "items": [
						    { "isin": "DE0000000001", "rationale": "Same instrument", "citations": [ { "id": "1" } ] }
						  ]
						}
						""", "test-model");
			}

			@Override
			public KnowledgeBaseLlmResponse runJsonPrompt(String prompt) {
				throw new UnsupportedOperationException();
			}
		};
		KnowledgeBaseConfigService configService = mock(KnowledgeBaseConfigService.class);
		when(configService.getSnapshot()).thenReturn(defaultSnapshot());
		KnowledgeBaseLlmClient client = new KnowledgeBaseLlmService(provider, configService, new ObjectMapper());

		assertThatThrownBy(() -> client.findAlternatives("DE0000000001", List.of("example.com")))
				.isInstanceOf(KnowledgeBaseLlmOutputException.class)
				.hasMessageContaining("No valid alternatives returned");
	}

	@Test
	void extractMetadata_usesSchemaAndParsesOutput() {
		AtomicReference<String> schemaName = new AtomicReference<>();
		KnowledgeBaseLlmProvider provider = new KnowledgeBaseLlmProvider() {
			@Override
			public KnowledgeBaseLlmResponse runWebSearch(String prompt, List<String> allowedDomains) {
				throw new UnsupportedOperationException();
			}

			@Override
			public KnowledgeBaseLlmResponse runJsonPrompt(String prompt) {
				throw new UnsupportedOperationException("Use schema-based call");
			}

			@Override
			public KnowledgeBaseLlmResponse runJsonPrompt(String prompt,
														 String schemaNameArg,
														 Map<String, Object> schema) {
				schemaName.set(schemaNameArg);
				return new KnowledgeBaseLlmResponse("""
						{
						  "isin": "DE0000000001",
						  "name": "Test Instrument",
						  "instrument_type": "ETF",
						  "asset_class": "Equity",
						  "sub_class": "Global",
						  "layer": 1,
						  "layer_notes": "broad",
						  "etf": {
						    "ongoing_charges_pct": 0.2,
						    "benchmark_index": "MSCI World"
						  },
						  "risk": {
						    "summary_risk_indicator": {
						      "value": 4
						    }
						  },
						  "regions": [],
						  "top_holdings": [],
						  "missing_fields": [],
						  "warnings": []
						}
						""", "test-model");
			}
		};
		KnowledgeBaseConfigService configService = mock(KnowledgeBaseConfigService.class);
		when(configService.getSnapshot()).thenReturn(defaultSnapshot());
		KnowledgeBaseLlmClient client = new KnowledgeBaseLlmService(provider, configService, new ObjectMapper());

		var draft = client.extractMetadata("Dossier text");

		assertThat(schemaName.get()).isEqualTo("kb_extraction_response");
		assertThat(draft.extractionJson().get("isin").asText()).isEqualTo("DE0000000001");
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
