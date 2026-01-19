package my.portfoliomanager.app.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssessorServiceSavingPlanGateTest {
	private final AssessorService service = new AssessorService(null, null, null, null, null, null, null);

	@Test
	void shiftsSmallLayerDeltasUpwardToMeetGate() {
		Map<Integer, BigDecimal> current = Map.of(
				1, new BigDecimal("50"),
				2, new BigDecimal("30"),
				3, new BigDecimal("20")
		);
		Map<Integer, BigDecimal> targets = Map.of(
				1, new BigDecimal("55"),
				2, new BigDecimal("30"),
				3, new BigDecimal("15")
		);

		AssessorService.LayerDeltaGateAdjustment adjustment =
				service.applySavingPlanLayerGates(targets, current, 15, 10);

		assertThat(adjustment.changed()).isTrue();
		assertThat(adjustment.deltas().get(1)).isEqualByComparingTo(new BigDecimal("15"));
		assertThat(adjustment.deltas().get(2)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(adjustment.deltas().get(3)).isEqualByComparingTo(new BigDecimal("-15"));
	}

	@Test
	void allocatesNegativeDeltasWithoutSubMinimumRebalance() {
		List<AssessorEngine.SavingPlanItem> plans = List.of(
				new AssessorEngine.SavingPlanItem("AAA111", 1L, new BigDecimal("50"), 1),
				new AssessorEngine.SavingPlanItem("BBB222", 1L, new BigDecimal("30"), 1)
		);
		Map<AssessorEngine.PlanKey, BigDecimal> weights = Map.of(
				new AssessorEngine.PlanKey("AAA111", 1L), BigDecimal.ONE,
				new AssessorEngine.PlanKey("BBB222", 1L), BigDecimal.ONE
		);

		Map<AssessorEngine.PlanKey, BigDecimal> deltas = service.allocatePlanDeltasByWeights(
				plans,
				new BigDecimal("-20"),
				weights,
				new BigDecimal("10"),
				new BigDecimal("15")
		);

		assertThat(deltas.values())
				.allMatch(value -> value.signum() < 0)
				.allMatch(value -> value.abs().compareTo(new BigDecimal("10")) >= 0);
		assertThat(deltas.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
				.isEqualByComparingTo(new BigDecimal("-20"));
	}

	@Test
	void discardsPlanWhenReductionDropsBelowMinimumSize() {
		List<AssessorEngine.SavingPlanItem> plans = List.of(
				new AssessorEngine.SavingPlanItem("AAA111", 1L, new BigDecimal("20"), 1),
				new AssessorEngine.SavingPlanItem("BBB222", 1L, new BigDecimal("20"), 1)
		);
		Map<AssessorEngine.PlanKey, BigDecimal> weights = Map.of(
				new AssessorEngine.PlanKey("AAA111", 1L), new BigDecimal("2"),
				new AssessorEngine.PlanKey("BBB222", 1L), BigDecimal.ONE
		);

		Map<AssessorEngine.PlanKey, BigDecimal> deltas = service.allocatePlanDeltasByWeights(
				plans,
				new BigDecimal("-15"),
				weights,
				new BigDecimal("10"),
				new BigDecimal("15")
		);

		assertThat(deltas)
				.containsEntry(new AssessorEngine.PlanKey("BBB222", 1L), new BigDecimal("-20"));
		assertThat(deltas).doesNotContainKey(new AssessorEngine.PlanKey("AAA111", 1L));
	}
}
