package my.portfoliomanager.app.service;

import my.portfoliomanager.app.dto.InstrumentProposalDto;
import my.portfoliomanager.app.dto.InstrumentProposalGatingDto;
import my.portfoliomanager.app.dto.SavingPlanProposalLayerDto;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
@Import(AdvisorServiceInstrumentTotalsTest.TestConfig.class)
class AdvisorServiceInstrumentTotalsTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private AdvisorService advisorService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@Autowired
	private InstrumentRebalanceService instrumentRebalanceService;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
	}

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from sparplans");
		jdbcTemplate.update("delete from instruments");
		jdbcTemplate.update("delete from depots");

		jdbcTemplate.update("insert into depots (depot_id, depot_code, name, provider) values (1, 'tr', 'Trade Republic', 'TR')");
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, asset_class, is_deleted) values ('DE000L1', 'Layer 1', 'tr', 1, 'Equity', false)");
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, asset_class, is_deleted) values ('DE000L2', 'Layer 2', 'tr', 2, 'Bonds', false)");
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, asset_class, is_deleted) values ('DE000L3', 'Layer 3', 'tr', 3, 'Equity', false)");
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, asset_class, is_deleted) values ('DE000L4', 'Layer 4', 'tr', 4, 'Equity', false)");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (1, 1, 'DE000L1', 50.00, 'monthly', true)");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (2, 1, 'DE000L2', 30.00, 'monthly', true)");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (3, 1, 'DE000L3', 15.00, 'monthly', true)");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (4, 1, 'DE000L4', 5.00, 'monthly', true)");

		List<InstrumentProposalDto> proposals = List.of(
				new InstrumentProposalDto("DE000L1", "Layer 1", 50.0d, 68.0d, 18.0d, 1, List.of("MIN_REBALANCE_AMOUNT")),
				new InstrumentProposalDto("DE000L2", "Layer 2", 30.0d, 22.0d, -8.0d, 2, List.of("MIN_REBALANCE_AMOUNT")),
				new InstrumentProposalDto("DE000L3", "Layer 3", 15.0d, 7.0d, -8.0d, 3, List.of("MIN_REBALANCE_AMOUNT")),
				new InstrumentProposalDto("DE000L4", "Layer 4", 5.0d, 3.0d, -2.0d, 4, List.of("MIN_REBALANCE_AMOUNT"))
		);
		var gating = new InstrumentProposalGatingDto(true, true, List.of());
		var result = new InstrumentRebalanceService.InstrumentProposalResult(gating, proposals, List.of(), List.of());
		Mockito.when(instrumentRebalanceService.buildInstrumentProposals(
						Mockito.anyList(),
						Mockito.anyMap(),
						ArgumentMatchers.<Integer>any(),
						ArgumentMatchers.<Integer>any(),
						Mockito.anyBoolean()))
				.thenReturn(result);
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void proposalUsesInstrumentTotalsForLayerBudgets() {
		var summary = advisorService.summary(null);
		assertThat(summary.savingPlanProposal()).isNotNull();
		assertThat(findTargetAmount(summary.savingPlanProposal().getLayers(), 1)).isEqualTo(68.0d);
		assertThat(findTargetAmount(summary.savingPlanProposal().getLayers(), 2)).isEqualTo(22.0d);
		assertThat(findTargetAmount(summary.savingPlanProposal().getLayers(), 3)).isEqualTo(7.0d);
		assertThat(findTargetAmount(summary.savingPlanProposal().getLayers(), 4)).isEqualTo(3.0d);
	}

	private double findTargetAmount(List<SavingPlanProposalLayerDto> layers, int layer) {
		return layers.stream()
				.filter(row -> row.getLayer() != null && row.getLayer() == layer)
				.findFirst()
				.map(row -> row.getTargetAmountEur() == null ? 0.0d : row.getTargetAmountEur())
				.orElse(0.0d);
	}

	@Configuration
	static class TestConfig {
		@Bean
		@Primary
		InstrumentRebalanceService instrumentRebalanceService() {
			return Mockito.mock(InstrumentRebalanceService.class);
		}

		@Bean
		@Primary
		LlmNarrativeService llmNarrativeService() {
			return new LlmNarrativeService() {
				@Override
				public boolean isEnabled() {
					return false;
				}

				@Override
				public String suggestSavingPlanNarrative(String prompt) {
					return null;
				}
			};
		}
	}
}
