package my.portfoliomanager.app.service;

import org.springframework.context.annotation.Primary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import my.portfoliomanager.app.dto.LayerTargetConfigRequestDto;
import my.portfoliomanager.app.dto.LayerTargetConfigResponseDto;
import my.portfoliomanager.app.dto.SavingPlanProposalDto;
import my.portfoliomanager.app.dto.SavingPlanProposalLayerDto;
import my.portfoliomanager.app.support.TestDatabaseCleaner;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
@Import(AdvisorServiceTest.TestConfig.class)
class AdvisorServiceTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private AdvisorService advisorService;

	@Autowired
	private LayerTargetConfigService layerTargetConfigService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
	}

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from snapshot_positions");
		jdbcTemplate.update("update depots set active_snapshot_id = null");
		jdbcTemplate.update("delete from snapshots");
		jdbcTemplate.update("delete from sparplans_history");
		jdbcTemplate.update("delete from sparplans");
		jdbcTemplate.update("delete from instruments");
		jdbcTemplate.update("delete from depots");

		jdbcTemplate.update("insert into depots (depot_id, depot_code, name, provider) values (1, 'tr', 'Trade Republic', 'TR')");
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, asset_class, is_deleted) values ('DE000B', 'Bond ETF', 'tr', 2, 'Bonds', false)");
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, asset_class, is_deleted) values ('DE000S', 'Stock ETF', 'tr', 1, 'Equity', false)");
		jdbcTemplate.update("insert into snapshots (snapshot_id, depot_id, as_of_date, source, file_hash) values (11, 1, ?, 'TR_PDF', 'hash')", LocalDate.now());
		jdbcTemplate.update("update depots set active_snapshot_id = 11 where depot_id = 1");
		jdbcTemplate.update("insert into snapshot_positions (snapshot_id, isin, name, value_eur, currency) values (11, 'DE000B', 'Bond ETF', 2000.00, 'EUR')");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (1, 1, 'DE000B', 30.00, 'monthly', true)");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (2, 1, 'DE000S', 70.00, 'monthly', true)");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void summaryProvidesLayerAllocation() {
		var summary = advisorService.summary(null);
		assertThat(summary.layerAllocations()).isNotEmpty();
		assertThat(summary.assetClassAllocations()).isNotEmpty();
		assertThat(summary.topPositions()).isNotEmpty();
		assertThat(summary.savingPlanSummary()).isNotNull();
		assertThat(summary.savingPlanSummary().monthlyTotalAmountEur()).isEqualTo(100.0d);
		assertThat(summary.savingPlanTargets()).isNotEmpty();
		assertThat(summary.savingPlanProposal()).isNotNull();
		assertThat(summary.savingPlanProposal().getSource()).isEqualTo("targets");
		assertThat(summary.savingPlanProposal().getNotes()).isNotNull();
		assertThat(summary.savingPlanProposal().getLayers())
				.extracting(layer -> {
				if (layer.getTargetAmountEur() == null) {
					return 0.0d;
				}
				return layer.getTargetAmountEur() % 1;
				})
				.containsOnly(0.0d);
		double proposalTotal = summary.savingPlanProposal().getLayers().stream()
				.mapToDouble(layer -> layer.getTargetAmountEur() == null ? 0.0d : layer.getTargetAmountEur())
				.sum();
		assertThat(proposalTotal).isEqualTo(summary.savingPlanSummary().monthlyTotalAmountEur());
	}

	@Test
	void summaryAsOfUsesSnapshotHistory() {
		var summary = advisorService.summary(LocalDate.now());
		assertThat(summary.layerAllocations()).isNotEmpty();
	}

	@Test
	void summaryUsesEffectiveInstrumentLayersAndAssetClasses() {
		jdbcTemplate.update("insert into instrument_overrides (isin, layer, asset_class) values ('DE000B', 4, 'Themes')");

		var summary = advisorService.summary(null);

		assertThat(summary.layerAllocations())
				.anyMatch(allocation -> "4".equals(allocation.label()) && allocation.valueEur() == 2000.0d);
		assertThat(summary.assetClassAllocations())
				.anyMatch(allocation -> "Themes".equals(allocation.label()) && allocation.valueEur() == 2000.0d);
	}

	@Test
	void targetTotalsMatchHoldingsPlusProjectedContributions() {
		layerTargetConfigService.resetToDefault();
		LayerTargetConfigResponseDto config = layerTargetConfigService.getConfigResponse();
		String profileKey = config.getActiveProfileKey();
		layerTargetConfigService.saveConfig(new LayerTargetConfigRequestDto(
				profileKey,
				false,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				Map.of(profileKey, 120),
				null,
				null
		));

		jdbcTemplate.update("delete from snapshot_positions");
		jdbcTemplate.update("delete from sparplans");
		jdbcTemplate.update("delete from instruments");

		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, asset_class, is_deleted) values ('L1', 'Layer1 ETF', 'tr', 1, 'Equity', false)");
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, asset_class, is_deleted) values ('L2', 'Layer2 ETF', 'tr', 2, 'Bonds', false)");
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, asset_class, is_deleted) values ('L3', 'Layer3 ETF', 'tr', 3, 'Themes', false)");
		jdbcTemplate.update("insert into instruments (isin, name, depot_code, layer, asset_class, is_deleted) values ('L4', 'Layer4 Stock', 'tr', 4, 'Equity', false)");

		jdbcTemplate.update("insert into snapshot_positions (snapshot_id, isin, name, value_eur, currency) values (11, 'L1', 'Layer1 ETF', 70000.00, 'EUR')");
		jdbcTemplate.update("insert into snapshot_positions (snapshot_id, isin, name, value_eur, currency) values (11, 'L2', 'Layer2 ETF', 20000.00, 'EUR')");
		jdbcTemplate.update("insert into snapshot_positions (snapshot_id, isin, name, value_eur, currency) values (11, 'L3', 'Layer3 ETF', 8000.00, 'EUR')");
		jdbcTemplate.update("insert into snapshot_positions (snapshot_id, isin, name, value_eur, currency) values (11, 'L4', 'Layer4 Stock', 2000.00, 'EUR')");

		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (11, 1, 'L1', 700.00, 'monthly', true)");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (12, 1, 'L2', 200.00, 'monthly', true)");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (13, 1, 'L3', 80.00, 'monthly', true)");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (14, 1, 'L4', 20.00, 'monthly', true)");

		var summary = advisorService.summary(null);
		assertThat(summary.savingPlanProposal()).isNotNull();
		var layers = summary.savingPlanProposal().getLayers();
		double totalTarget = layers.stream()
				.mapToDouble(layer -> layer.getTargetTotalAmountEur() == null ? 0.0d : layer.getTargetTotalAmountEur())
				.sum();
		assertThat(totalTarget).isEqualTo(220000.0d);
		Map<Integer, SavingPlanProposalLayerDto> byLayer = new HashMap<>();
		for (SavingPlanProposalLayerDto layer : layers) {
			byLayer.put(layer.getLayer(), layer);
		}
		assertThat(byLayer.get(1).getTargetTotalAmountEur()).isEqualTo(154000.0d);
		assertThat(byLayer.get(2).getTargetTotalAmountEur()).isEqualTo(44000.0d);
		assertThat(byLayer.get(3).getTargetTotalAmountEur()).isEqualTo(17600.0d);
		assertThat(byLayer.get(4).getTargetTotalAmountEur()).isEqualTo(4400.0d);
		assertThat(byLayer.get(1).getTargetTotalWeightPct()).isCloseTo(70.0d, org.assertj.core.data.Offset.offset(0.01d));
		assertThat(byLayer.get(2).getTargetTotalWeightPct()).isCloseTo(20.0d, org.assertj.core.data.Offset.offset(0.01d));
		assertThat(byLayer.get(3).getTargetTotalWeightPct()).isCloseTo(8.0d, org.assertj.core.data.Offset.offset(0.01d));
		assertThat(byLayer.get(4).getTargetTotalWeightPct()).isCloseTo(2.0d, org.assertj.core.data.Offset.offset(0.01d));
	}

	@Test
	void proposalIncreasesLayerOneWhenTotalBelowMinimum() {
		jdbcTemplate.update("delete from sparplans");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (3, 1, 'DE000S', 10.00, 'monthly', true)");

		var summary = advisorService.summary(null);

		assertThat(summary.savingPlanSummary().monthlyTotalAmountEur()).isEqualTo(10.0d);
		assertThat(summary.savingPlanProposal()).isNotNull();
		var layerOne = summary.savingPlanProposal().getLayers().stream()
				.filter(layer -> layer.getLayer() == 1)
				.findFirst()
				.orElseThrow();
		assertThat(layerOne.getTargetAmountEur()).isEqualTo(10.0d);
		double proposalTotal = summary.savingPlanProposal().getLayers().stream()
				.mapToDouble(layer -> layer.getTargetAmountEur() == null ? 0.0d : layer.getTargetAmountEur())
				.sum();
		assertThat(proposalTotal).isEqualTo(10.0d);
		assertThat(summary.savingPlanProposal().getNotes())
				.anyMatch(note -> note.contains("minimum rebalancing amount"));
	}

	@Test
	void longerProjectionHorizonKeepsProposalCloserToCurrentDistribution() {
		layerTargetConfigService.resetToDefault();
		LayerTargetConfigResponseDto config = layerTargetConfigService.getConfigResponse();
		String profileKey = config.getActiveProfileKey();

		jdbcTemplate.update("delete from sparplans");
		jdbcTemplate.update("insert into sparplans (sparplan_id, depot_id, isin, amount_eur, frequency, active) values (10, 1, 'DE000B', 500.00, 'monthly', true)");

		LayerTargetConfigRequestDto request12 = new LayerTargetConfigRequestDto(
				profileKey,
				false,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				Map.of(profileKey, 12),
				null,
				null
		);
		layerTargetConfigService.saveConfig(request12);
		var summary12 = advisorService.summary(null);
		assertThat(summary12.savingPlanProposal()).isNotNull();
		double delta12 = distributionDelta(summary12.savingPlanProposal());

		LayerTargetConfigRequestDto request120 = new LayerTargetConfigRequestDto(
				profileKey,
				false,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				Map.of(profileKey, 120),
				null,
				null
		);
		layerTargetConfigService.saveConfig(request120);
		var summary120 = advisorService.summary(null);
		assertThat(summary120.savingPlanProposal()).isNotNull();
		double delta120 = distributionDelta(summary120.savingPlanProposal());

		assertThat(delta12).isGreaterThan(0.0d);
		assertThat(delta120).isLessThan(delta12);
	}

	private double distributionDelta(SavingPlanProposalDto proposal) {
		Map<Integer, Double> actual = proposal == null ? null : proposal.getActualDistributionByLayer();
		Map<Integer, Double> proposed = proposal == null ? null : proposal.getProposedDistributionByLayer();
		double total = 0.0d;
		for (int layer = 1; layer <= 5; layer++) {
			double actualValue = actual == null ? 0.0d : actual.getOrDefault(layer, 0.0d);
			double proposedValue = proposed == null ? 0.0d : proposed.getOrDefault(layer, 0.0d);
			total += Math.abs(proposedValue - actualValue);
		}
		return total;
	}

	@Configuration
	static class TestConfig {
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
