package my.portfoliomanager.app.service;

import my.portfoliomanager.app.dto.AssessorRunRequestDto;
import my.portfoliomanager.app.dto.AssessorRunResponseDto;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AssessorBackupIntegrationTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private BackupService backupService;

	@Autowired
	private AssessorService assessorService;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void savingPlanSuggestionsMatchLayerDeltaAfterBackupImport() throws Exception {
		var resource = new ClassPathResource("imports/database-backup.zip");
		try (InputStream stream = resource.getInputStream()) {
			var file = new MockMultipartFile("file", "database-backup.zip", "application/zip", stream);
			backupService.importBackup(file);
		}

		AssessorRunResponseDto result = assessorService.run(new AssessorRunRequestDto(
				"AGGRESSIVE",
				15.0,
				null,
				null,
				null,
				"saving_plan_gaps"
		));

		Map<Integer, Double> current = result.currentLayerDistribution();
		Map<Integer, Double> target = result.targetLayerDistribution();
		Map<Integer, BigDecimal> suggestionTotals = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			suggestionTotals.put(layer, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
		}
		result.savingPlanSuggestions().stream()
				.filter(item -> item.layer() != null)
				.forEach(item -> suggestionTotals.put(item.layer(),
						suggestionTotals.get(item.layer()).add(toAmount(item.newAmount()))));
		result.savingPlanNewInstruments().stream()
				.filter(item -> item.layer() != null)
				.forEach(item -> suggestionTotals.put(item.layer(),
						suggestionTotals.get(item.layer()).add(toAmount(item.amount()))));
		result.savingPlanNewInstruments()
				.forEach(item -> assertThat(item.action()).isEqualTo("new"));
		BigDecimal currentLayer4 = toAmount(current.getOrDefault(4, 0.0));
		BigDecimal targetLayer4 = toAmount(target.getOrDefault(4, 0.0));
		BigDecimal layerDelta4 = targetLayer4.subtract(currentLayer4);
		BigDecimal suggestionDelta4 = result.savingPlanSuggestions().stream()
				.filter(item -> item.layer() != null && item.layer() == 4)
				.map(item -> toAmount(item.delta()))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal newInstrumentDelta4 = result.savingPlanNewInstruments().stream()
				.filter(item -> item.layer() != null && item.layer() == 4)
				.map(item -> toAmount(item.amount()))
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		assertThat(layerDelta4).isGreaterThan(BigDecimal.ZERO);
		assertThat(suggestionDelta4.add(newInstrumentDelta4)).isEqualByComparingTo(layerDelta4);

		BigDecimal suggestedTotal = result.savingPlanSuggestions().stream()
				.map(item -> toAmount(item.newAmount()))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal newInstrumentTotal = result.savingPlanNewInstruments().stream()
				.map(item -> toAmount(item.amount()))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		assertThat(suggestedTotal.add(newInstrumentTotal)).isEqualByComparingTo(toAmount(result.currentMonthlyTotal()));

		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal targetLayer = toAmount(target.getOrDefault(layer, 0.0));
			assertThat(targetLayer).isEqualByComparingTo(suggestionTotals.get(layer));
		}
	}

	@Test
	void savingPlanTargetsRemainConsistentWithGapPolicies() throws Exception {
		var resource = new ClassPathResource("imports/database-backup_assessor_saving_plan_missmatch.zip");
		try (InputStream stream = resource.getInputStream()) {
			var file = new MockMultipartFile("file", "database-backup_assessor_saving_plan_missmatch.zip", "application/zip", stream);
			backupService.importBackup(file);
		}

		assertSavingPlanTotals("saving_plan_gaps");
		assertSavingPlanTotals("portfolio_gaps");
	}

	private void assertSavingPlanTotals(String gapPolicy) {
		AssessorRunResponseDto result = assessorService.run(new AssessorRunRequestDto(
				null,
				500.0,
				null,
				null,
				null,
				gapPolicy
		));

		BigDecimal expectedTotal = BigDecimal.valueOf(750).setScale(2, RoundingMode.HALF_UP);
		BigDecimal reportedTotal = toAmount(result.currentMonthlyTotal());
		assertThat(reportedTotal).isEqualByComparingTo(expectedTotal);

		Map<Integer, Double> target = result.targetLayerDistribution();
		Map<Integer, BigDecimal> layerTotals = new LinkedHashMap<>();
		BigDecimal targetSum = BigDecimal.ZERO;
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal targetAmount = toAmount(target.getOrDefault(layer, 0.0));
			layerTotals.put(layer, BigDecimal.ZERO);
			targetSum = targetSum.add(targetAmount);
		}
		assertThat(targetSum).isEqualByComparingTo(expectedTotal);

		result.savingPlanSuggestions().stream()
				.filter(item -> item.layer() != null)
				.forEach(item -> layerTotals.put(item.layer(),
						layerTotals.get(item.layer()).add(toAmount(item.newAmount()))));
		result.savingPlanNewInstruments().stream()
				.filter(item -> item.layer() != null)
				.forEach(item -> layerTotals.put(item.layer(),
						layerTotals.get(item.layer()).add(toAmount(item.amount()))));

		BigDecimal suggestionsTotal = BigDecimal.ZERO;
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal targetAmount = toAmount(target.getOrDefault(layer, 0.0));
			assertThat(layerTotals.get(layer)).isEqualByComparingTo(targetAmount);
			suggestionsTotal = suggestionsTotal.add(layerTotals.get(layer));
		}
		assertThat(suggestionsTotal).isEqualByComparingTo(expectedTotal);
	}

	private BigDecimal toAmount(Double value) {
		if (value == null) {
			return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
		}
		return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
	}
}
