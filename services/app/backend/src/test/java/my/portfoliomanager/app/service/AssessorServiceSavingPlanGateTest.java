package my.portfoliomanager.app.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssessorServiceSavingPlanGateTest {
	private final AssessorService service = new AssessorService(null, null, null, null, null, null, null, null, new SavingPlanDeltaAllocator());
	private final SavingPlanDeltaAllocator allocator = new SavingPlanDeltaAllocator();

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
		List<SavingPlanDeltaAllocator.PlanInput> inputs = List.of(
				new SavingPlanDeltaAllocator.PlanInput(new AssessorEngine.PlanKey("AAA111", 1L), new BigDecimal("50"), BigDecimal.ONE),
				new SavingPlanDeltaAllocator.PlanInput(new AssessorEngine.PlanKey("BBB222", 1L), new BigDecimal("30"), BigDecimal.ONE)
		);
		SavingPlanDeltaAllocator.Allocation allocation = allocator.allocateToTarget(
				inputs,
				new BigDecimal("60"),
				new BigDecimal("10"),
				new BigDecimal("15")
		);
		Map<AssessorEngine.PlanKey, BigDecimal> deltas = allocation.deltas();

		assertThat(deltas.values())
				.allMatch(value -> value.signum() < 0)
				.allMatch(value -> value.abs().compareTo(new BigDecimal("10")) >= 0);
		assertThat(deltas.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
				.isEqualByComparingTo(new BigDecimal("-20"));
	}

	@Test
	void discardsPlanWhenReductionDropsBelowMinimumSize() {
		List<SavingPlanDeltaAllocator.PlanInput> inputs = List.of(
				new SavingPlanDeltaAllocator.PlanInput(new AssessorEngine.PlanKey("AAA111", 1L), new BigDecimal("20"), new BigDecimal("2")),
				new SavingPlanDeltaAllocator.PlanInput(new AssessorEngine.PlanKey("BBB222", 1L), new BigDecimal("20"), BigDecimal.ONE)
		);
		SavingPlanDeltaAllocator.Allocation allocation = allocator.allocateToTarget(
				inputs,
				new BigDecimal("25"),
				new BigDecimal("10"),
				new BigDecimal("15")
		);
		Map<AssessorEngine.PlanKey, BigDecimal> deltas = allocation.deltas();

		assertThat(deltas)
				.containsEntry(new AssessorEngine.PlanKey("BBB222", 1L), new BigDecimal("-20"))
				.containsEntry(new AssessorEngine.PlanKey("AAA111", 1L), new BigDecimal("5"));
		assertThat(deltas.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
				.isEqualByComparingTo(new BigDecimal("-15"));
	}

	@Test
	void reducesWithoutDiscardWhenCapacityAllows() {
		List<SavingPlanDeltaAllocator.PlanInput> inputs = List.of(
				new SavingPlanDeltaAllocator.PlanInput(new AssessorEngine.PlanKey("AAA111", 1L), new BigDecimal("40"), new BigDecimal("10")),
				new SavingPlanDeltaAllocator.PlanInput(new AssessorEngine.PlanKey("BBB222", 1L), new BigDecimal("20"), BigDecimal.ONE),
				new SavingPlanDeltaAllocator.PlanInput(new AssessorEngine.PlanKey("CCC333", 1L), new BigDecimal("20"), new BigDecimal("2"))
		);
		SavingPlanDeltaAllocator.Allocation allocation = allocator.allocateToTarget(
				inputs,
				new BigDecimal("50"),
				new BigDecimal("10"),
				new BigDecimal("15")
		);
		Map<AssessorEngine.PlanKey, BigDecimal> deltas = allocation.deltas();

		assertThat(allocation.discardedPlans()).isEmpty();
		assertThat(deltas.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
				.isEqualByComparingTo(new BigDecimal("-30"));
	}
}
