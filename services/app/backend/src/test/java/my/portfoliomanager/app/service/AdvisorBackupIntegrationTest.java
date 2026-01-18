package my.portfoliomanager.app.service;

import my.portfoliomanager.app.dto.AdvisorSummaryDto;
import my.portfoliomanager.app.dto.SavingPlanProposalDto;
import my.portfoliomanager.app.dto.SavingPlanProposalLayerDto;
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
class AdvisorBackupIntegrationTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private BackupService backupService;

	@Autowired
	private AdvisorService advisorService;

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
	void savingPlanInstrumentTotalsMatchLayerProposalAfterBackupImport() throws Exception {
		var resource = new ClassPathResource("imports/database-backup-advisor.zip");
		try (InputStream stream = resource.getInputStream()) {
			var file = new MockMultipartFile("file", "database-backup-advisor.zip", "application/zip", stream);
			backupService.importBackup(file);
		}

		AdvisorSummaryDto summary = advisorService.summary(null);
		SavingPlanProposalDto proposal = summary.savingPlanProposal();
		assertThat(proposal).isNotNull();
		assertThat(proposal.getInstrumentProposals()).isNotEmpty();

		Map<Integer, BigDecimal> instrumentTotals = initLayerTotals();
		proposal.getInstrumentProposals().stream()
				.filter(item -> item.getLayer() != null)
				.forEach(item -> instrumentTotals.put(item.getLayer(),
						instrumentTotals.getOrDefault(item.getLayer(), BigDecimal.ZERO).add(toAmount(item.getProposedAmountEur()))));

		for (SavingPlanProposalLayerDto layer : proposal.getLayers()) {
			if (layer.getLayer() == null) {
				continue;
			}
			BigDecimal proposed = toAmount(layer.getTargetAmountEur());
			assertThat(instrumentTotals.get(layer.getLayer())).isEqualByComparingTo(proposed);
		}
	}

	private Map<Integer, BigDecimal> initLayerTotals() {
		Map<Integer, BigDecimal> totals = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			totals.put(layer, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
		}
		return totals;
	}

	private BigDecimal toAmount(Double value) {
		if (value == null) {
			return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
		}
		return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
	}
}
