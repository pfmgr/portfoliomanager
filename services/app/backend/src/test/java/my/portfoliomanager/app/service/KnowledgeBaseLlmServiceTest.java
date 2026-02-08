package my.portfoliomanager.app.service;

import tools.jackson.databind.JsonNode;
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
		KnowledgeBaseLlmClient client = new KnowledgeBaseLlmService(provider, configService, new ObjectMapper(), null);

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
		KnowledgeBaseLlmClient client = new KnowledgeBaseLlmService(provider, configService, new ObjectMapper(), null);

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
		KnowledgeBaseLlmClient client = new KnowledgeBaseLlmService(provider, configService, new ObjectMapper(), null);

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
						  "financials": null,
						  "valuation": null,
						  "missing_fields": [],
						  "warnings": []
						}
						""", "test-model");
			}
		};
		KnowledgeBaseConfigService configService = mock(KnowledgeBaseConfigService.class);
		when(configService.getSnapshot()).thenReturn(defaultSnapshot());
		KnowledgeBaseLlmClient client = new KnowledgeBaseLlmService(provider, configService, new ObjectMapper(), null);

		var draft = client.extractMetadata("Dossier text");

		assertThat(schemaName.get()).isEqualTo("kb_extraction_response");
		assertThat(draft.extractionJson().get("isin").asText()).isEqualTo("DE0000000001");
	}

	@Test
	void extractMetadata_appliesAsOfFallbacksAndSingleStockRules() {
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
						  "name": "Test Co",
						  "instrument_type": "Common stock",
						  "asset_class": "Equity",
						  "sub_class": "Single Stock",
						  "layer": 4,
						  "layer_notes": "Layer 4 - Single stock",
						  "etf": null,
						  "risk": null,
						  "regions": null,
						  "top_holdings": null,
						  "financials": null,
						  "valuation": {
						    "ebitda": null,
						    "ebitda_currency": null,
						    "ebitda_eur": null,
						    "fx_rate_to_eur": null,
						    "ebitda_period_end": null,
						    "ebitda_period_type": null,
						    "enterprise_value": null,
						    "net_debt": null,
						    "market_cap": null,
						    "shares_outstanding": null,
						    "ev_to_ebitda": null,
						    "net_rent": null,
						    "net_rent_currency": null,
						    "net_rent_period_end": null,
						    "net_rent_period_type": null,
						    "noi": null,
						    "noi_currency": null,
						    "noi_period_end": null,
						    "noi_period_type": null,
						    "affo": null,
						    "affo_currency": null,
						    "affo_period_end": null,
						    "affo_period_type": null,
						    "ffo": null,
						    "ffo_currency": null,
						    "ffo_period_end": null,
						    "ffo_period_type": null,
						    "ffo_type": null,
						    "price": 100.0,
						    "price_currency": "EUR",
						    "price_asof": null,
						    "eps_type": "adjusted",
						    "eps_norm": null,
						    "eps_norm_years_used": null,
						    "eps_norm_years_available": null,
						    "eps_history": [
						      {
						        "year": 2024,
						        "eps": 5.0,
						        "eps_type": "adjusted",
						        "eps_currency": "EUR",
						        "period_end": null
						      }
						    ],
						    "eps_floor_policy": null,
						    "eps_floor_value": null,
						    "eps_norm_period_end": null,
						    "pe_longterm": null,
						    "earnings_yield_longterm": null,
						    "pe_current": null,
						    "pe_current_asof": null,
						    "pb_current": 2.5,
						    "pb_current_asof": null,
						    "pe_ttm_holdings": 20.0,
						    "earnings_yield_ttm_holdings": 0.05,
						    "holdings_coverage_weight_pct": 95.0,
						    "holdings_coverage_count": 40,
						    "holdings_asof": "2024-12-31",
						    "holdings_weight_method": "provider_weighted_avg",
						    "pe_method": "provider_weighted_avg",
						    "pe_horizon": "normalized",
						    "neg_earnings_handling": "exclude"
						  },
						  "missing_fields": [],
						  "warnings": []
						}
						""", "test-model");
			}
		};
		KnowledgeBaseConfigService configService = mock(KnowledgeBaseConfigService.class);
		when(configService.getSnapshot()).thenReturn(defaultSnapshot());
		KnowledgeBaseLlmClient client = new KnowledgeBaseLlmService(provider, configService, new ObjectMapper(), null);

		var draft = client.extractMetadata("Research date: 2026-01-24");

		assertThat(schemaName.get()).isEqualTo("kb_extraction_response");
		JsonNode valuation = draft.extractionJson().get("valuation");
		assertThat(valuation.get("price_asof").asText()).isEqualTo("2026-01-24");
		assertThat(valuation.get("pe_current").asDouble()).isEqualTo(20.0);
		assertThat(valuation.get("pe_current_asof").asText()).isEqualTo("2026-01-24");
		assertThat(valuation.get("pb_current_asof").asText()).isEqualTo("2026-01-24");
		assertThat(valuation.get("pe_method").asText()).isEqualTo("ttm");
		assertThat(valuation.get("pe_horizon").asText()).isEqualTo("ttm");
		assertThat(valuation.get("holdings_asof").isNull()).isTrue();
		assertThat(valuation.get("holdings_coverage_weight_pct").isNull()).isTrue();
		assertThat(valuation.get("eps_history").get(0).get("period_end").asText()).isEqualTo("2026-01-24");
	}

	@Test
	void extractMetadata_forcesLayer3ForThematicEtf() {
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
				return new KnowledgeBaseLlmResponse("""
						{
						  "isin": "IE0002Y8CX98",
						  "name": "WisdomTree Europe Defence UCITS ETF",
						  "instrument_type": "ETF",
						  "asset_class": "Equity",
						  "sub_class": "Europe Defence",
						  "layer": 2,
						  "layer_notes": "Core-Plus; Europe exposure",
						  "etf": {
						    "ongoing_charges_pct": 0.4,
						    "benchmark_index": "Custom"
						  },
						  "risk": null,
						  "regions": null,
						  "top_holdings": null,
						  "financials": null,
						  "valuation": null,
						  "missing_fields": [],
						  "warnings": []
						}
						""", "test-model");
			}
		};
		KnowledgeBaseConfigService configService = mock(KnowledgeBaseConfigService.class);
		when(configService.getSnapshot()).thenReturn(defaultSnapshot());
		KnowledgeBaseLlmClient client = new KnowledgeBaseLlmService(provider, configService, new ObjectMapper(), null);

		var draft = client.extractMetadata("Research date: 2026-01-24");

		assertThat(draft.extractionJson().get("layer").asInt()).isEqualTo(3);
	}

	@Test
	void extractMetadata_stripsNullChars() {
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
				throw new UnsupportedOperationException("Not used");
			}

			@Override
			public KnowledgeBaseLlmResponse runJsonPrompt(String prompt) {
				return new KnowledgeBaseLlmResponse("""
						{
						  "payload": {
						    "isin": "DE0000000001",
						    "name": "Test\\u0000 Name",
						    "instrument_type": "Equity",
						    "asset_class": "Equity",
						    "sub_class": "Single stock",
						    "layer": 4,
						    "layer_notes": "Layer 4",
						    "etf": null,
						    "risk": null,
						    "regions": null,
						    "top_holdings": null,
						    "financials": null,
						    "valuation": null,
						    "missing_fields": [],
						    "warnings": []
						  }
						}
						""", "test-model");
			}
		};
		KnowledgeBaseConfigService configService = mock(KnowledgeBaseConfigService.class);
		when(configService.getSnapshot()).thenReturn(defaultSnapshot());
		KnowledgeBaseLlmClient client = new KnowledgeBaseLlmService(provider, configService, new ObjectMapper(), null);

		var draft = client.extractMetadata("Research date: 2026-01-24");
		assertThat(draft.extractionJson().get("name").asText()).isEqualTo("Test Name");
	}

	@Test
	void generateDossier_normalizesValuationSection() {
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
				return new KnowledgeBaseLlmResponse("""
						{
						  "contentMd": "# DE0000000001 — Test\\n\\n## Quick profile (table)\\n- ISIN: DE0000000001\\n\\n## Classification (instrument type, asset class, subclass, suggested layer)\\n- Instrument type: Equity\\n\\n## Risk (SRI and notes)\\n- SRI: unknown\\n\\n## Costs & structure (TER, replication, domicile, distribution, currency if relevant)\\n- TER: unknown\\n\\n## Exposures (regions, sectors, top holdings/top-10, benchmark/index)\\n- Regions: unknown\\n\\n## Valuation & profitability\\n- price: 7.46 EUR (as of 2026-01-12) ([source])\\n- pe_current: 19.26–20.02 (as of 2026-01-12)\\n- holdings_asof: 2025\\n- holdings_weight_method: provider_weighted_avg|unknown (not explicitly stated) -> unknown\\n- pe_method: ttm|forward\\n- pe_horizon: normalized\\n- neg_earnings_handling: exclude|set_null\\nNotes on valuation data: ignore this\\n\\n## Redundancy hints (qualitative; do not claim precise correlations without data)\\n- None\\n\\n## Sources\\n1. Example\\n",
						  "displayName": "Test",
						  "citations": [
						    {
						      "id": "1",
						      "title": "Source",
						      "url": "https://example.com",
						      "publisher": "Example",
						      "accessed_at": "2024-01-01"
						    }
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
		KnowledgeBaseLlmClient client = new KnowledgeBaseLlmService(provider, configService, new ObjectMapper(), null);

		var draft = client.generateDossier("DE0000000001", null, List.of("example.com"), 15000);

		String contentMd = draft.contentMd();
		assertThat(contentMd).contains("- pe_current: 19.64 (as of 2026-01-12)");
		assertThat(contentMd).contains("- holdings_asof: unknown");
		assertThat(contentMd).contains("- holdings_weight_method: unknown");
		assertThat(contentMd).contains("- pe_method: ttm");
		assertThat(contentMd).contains("- pe_horizon: ttm");
		assertThat(contentMd).contains("- neg_earnings_handling: exclude");
		assertThat(contentMd).doesNotContain("provider_weighted_avg|unknown");
		assertThat(contentMd).doesNotContain("Notes on valuation data");
	}

	@Test
	void generateDossier_insertsMissingValuationSection() {
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
				return new KnowledgeBaseLlmResponse("""
						{
						  "contentMd": "# DE0000000002 — Test\\n\\n## Quick profile (table)\\n- ISIN: DE0000000002\\n\\n## Redundancy hints (qualitative; do not claim precise correlations without data)\\n- None\\n\\n## Sources\\n1. Example\\n",
						  "displayName": "Test",
						  "citations": [
						    {
						      "id": "1",
						      "title": "Source",
						      "url": "https://example.com",
						      "publisher": "Example",
						      "accessed_at": "2024-01-01"
						    }
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
		KnowledgeBaseLlmClient client = new KnowledgeBaseLlmService(provider, configService, new ObjectMapper(), null);

		var draft = client.generateDossier("DE0000000002", null, List.of("example.com"), 15000);

		String contentMd = draft.contentMd();
		assertThat(contentMd).contains("## Valuation & profitability");
		assertThat(contentMd).contains("- price: unknown");
	}

	@Test
	void generateDossier_clearsHoldingsForSingleStock() {
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
				return new KnowledgeBaseLlmResponse("""
						{
						  "contentMd": "# DE0000000003 — Test\\n\\n## Quick profile (table)\\n- ISIN: DE0000000003\\n\\n## Classification (instrument type, asset class, subclass, suggested layer)\\n- Instrument type: Equity\\n\\n## Valuation & profitability\\n- holdings_coverage_weight_pct: 80\\n- holdings_asof: 2025-09-30\\n- holdings_weight_method: provider_weighted_avg\\n\\n## Redundancy hints (qualitative; do not claim precise correlations without data)\\n- None\\n\\n## Sources\\n1. Example\\n",
						  "displayName": "Test",
						  "citations": [
						    {
						      "id": "1",
						      "title": "Source",
						      "url": "https://example.com",
						      "publisher": "Example",
						      "accessed_at": "2024-01-01"
						    }
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
		KnowledgeBaseLlmClient client = new KnowledgeBaseLlmService(provider, configService, new ObjectMapper(), null);

		var draft = client.generateDossier("DE0000000003", null, List.of("example.com"), 15000);

		String contentMd = draft.contentMd();
		assertThat(contentMd).contains("- holdings_coverage_weight_pct: unknown");
		assertThat(contentMd).contains("- holdings_asof: unknown");
		assertThat(contentMd).contains("- holdings_weight_method: unknown");
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
				"low",
				List.of("example.com"),
				2,
				true,
				0.6,
				true,
				2,
				null
		);
	}
}
