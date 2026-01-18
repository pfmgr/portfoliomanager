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
import my.portfoliomanager.app.support.TestDatabaseCleaner;

import java.time.LocalDate;
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
