package my.portfoliomanager.app.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssessorEngineTest {
	private final AssessorEngine engine = new AssessorEngine();

	@Test
	void suppressesLayerDeltasBelowMinimum() {
		List<AssessorEngine.SavingPlanItem> plans = List.of(
				plan("AAA111", 1L, 180.0, 1),
				plan("BBB222", 1L, 20.0, 2)
		);
		Map<Integer, BigDecimal> targets = Map.of(
				1, new BigDecimal("0.92"),
				2, new BigDecimal("0.08")
		);

		AssessorEngine.AssessorEngineResult result = engine.assess(new AssessorEngine.AssessorEngineInput(
				"BALANCED",
				targets,
				BigDecimal.ZERO,
				15,
				10,
				25,
				null,
				null,
				null,
				plans,
				null,
				null,
				Map.of(),
				false
		));

		assertThat(result.diagnostics().withinTolerance()).isFalse();
		assertThat(result.diagnostics().suppressedDeltasCount()).isEqualTo(2);
		assertThat(result.diagnostics().suppressedAmountTotal()).isEqualByComparingTo(new BigDecimal("8"));
		assertThat(result.savingPlanSuggestions()).isEmpty();
	}

	@Test
	void redistributesResidualDeterministically() {
		Map<Integer, BigDecimal> deltas = Map.of(
				1, new BigDecimal("-20"),
				2, new BigDecimal("12"),
				3, new BigDecimal("8"),
				4, BigDecimal.ZERO,
				5, BigDecimal.ZERO
		);
		AssessorEngine.LayerDeltaResult result = AssessorEngine.adjustLayerDeltas(deltas, new BigDecimal("10"));

		assertThat(result.adjustedDeltas().get(1)).isEqualByComparingTo(new BigDecimal("-12"));
		assertThat(result.adjustedDeltas().get(2)).isEqualByComparingTo(new BigDecimal("12"));
		assertThat(result.adjustedDeltas().get(3)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(result.adjustedDeltas().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
				.isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void preservesTargetTotalWhenSuppressingSmallDeltas() {
		Map<Integer, BigDecimal> deltas = Map.of(
				1, new BigDecimal("-6"),
				2, new BigDecimal("-105"),
				3, new BigDecimal("108"),
				4, new BigDecimal("23"),
				5, BigDecimal.ZERO
		);

		AssessorEngine.LayerDeltaResult result = AssessorEngine.adjustLayerDeltas(deltas, new BigDecimal("10"));

		assertThat(result.adjustedDeltas().get(1)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(result.adjustedDeltas().get(2)).isEqualByComparingTo(new BigDecimal("-105"));
		assertThat(result.adjustedDeltas().get(3)).isEqualByComparingTo(new BigDecimal("102"));
		assertThat(result.adjustedDeltas().get(4)).isEqualByComparingTo(new BigDecimal("23"));
		assertThat(result.adjustedDeltas().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
				.isEqualByComparingTo(new BigDecimal("20"));
	}

	@Test
	void aggregatesSmallPlanDeltas() {
		List<AssessorEngine.SavingPlanItem> plans = List.of(
				plan("AAA111", 1L, 80.0, 1),
				plan("BBB222", 1L, 10.0, 1),
				plan("CCC333", 1L, 10.0, 1),
				plan("DDD444", 1L, 40.0, 2)
		);
		Map<Integer, BigDecimal> targets = Map.of(
				1, new BigDecimal("0.8"),
				2, new BigDecimal("0.2")
		);

		AssessorEngine.AssessorEngineResult result = engine.assess(new AssessorEngine.AssessorEngineInput(
				"BALANCED",
				targets,
				BigDecimal.ZERO,
				15,
				10,
				25,
				null,
				null,
				null,
				plans,
				null,
				null,
				Map.of(),
				false
		));

		assertThat(result.savingPlanSuggestions())
				.extracting(AssessorEngine.SavingPlanSuggestion::isin)
				.containsExactlyInAnyOrder("AAA111", "DDD444");
		AssessorEngine.SavingPlanSuggestion increase = result.savingPlanSuggestions().stream()
				.filter(item -> "AAA111".equals(item.isin()))
				.findFirst()
				.orElseThrow();
		assertThat(increase.delta()).isEqualByComparingTo(new BigDecimal("12.00"));
	}

	@Test
	void discardsWhenBelowMinimumSavingPlanSize() {
		List<AssessorEngine.SavingPlanItem> plans = List.of(
				plan("AAA111", 1L, 20.0, 1),
				plan("BBB222", 1L, 20.0, 2)
		);
		Map<Integer, BigDecimal> targets = Map.of(
				1, BigDecimal.ZERO,
				2, BigDecimal.ONE
		);

		AssessorEngine.AssessorEngineResult result = engine.assess(new AssessorEngine.AssessorEngineInput(
				"BALANCED",
				targets,
				BigDecimal.ZERO,
				15,
				10,
				25,
				null,
				null,
				null,
				plans,
				null,
				null,
				Map.of(),
				false
		));

		AssessorEngine.SavingPlanSuggestion discard = result.savingPlanSuggestions().stream()
				.filter(item -> "AAA111".equals(item.isin()))
				.findFirst()
				.orElseThrow();
		assertThat(discard.type()).isEqualTo("discard");
		assertThat(discard.newAmount()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void withinToleranceReturnsNoSuggestions() {
		List<AssessorEngine.SavingPlanItem> plans = List.of(
				plan("AAA111", 1L, 70.0, 1),
				plan("BBB222", 1L, 30.0, 2)
		);
		Map<Integer, BigDecimal> targets = Map.of(
				1, new BigDecimal("0.70"),
				2, new BigDecimal("0.30")
		);

		AssessorEngine.AssessorEngineResult result = engine.assess(new AssessorEngine.AssessorEngineInput(
				"BALANCED",
				targets,
				new BigDecimal("3.0"),
				15,
				10,
				25,
				null,
				null,
				null,
				plans,
				null,
				null,
				Map.of(),
				false
		));

		assertThat(result.diagnostics().withinTolerance()).isTrue();
		assertThat(result.savingPlanSuggestions()).isEmpty();
	}

	@Test
	void respectsMinimumInstrumentAmount() {
		List<AssessorEngine.SavingPlanItem> plans = List.of(
				plan("AAA111", 1L, 50.0, 1),
				plan("BBB222", 1L, 50.0, 1)
		);
		Map<Integer, BigDecimal> targets = Map.of(
				1, BigDecimal.ONE
		);

		AssessorEngine.AssessorEngineResult result = engine.assess(new AssessorEngine.AssessorEngineInput(
				"BALANCED",
				targets,
				BigDecimal.ZERO,
				15,
				10,
				60,
				null,
				null,
				null,
				plans,
				null,
				new BigDecimal("100"),
				Map.of(),
				true
		));

		assertThat(result.oneTimeAllocation()).isNotNull();
		assertThat(result.oneTimeAllocation().instrumentBuckets())
				.containsEntry("AAA111", new BigDecimal("100"));
		assertThat(result.oneTimeAllocation().instrumentBuckets())
				.doesNotContainKey("BBB222");
	}

	@Test
	void appliesSavingPlanAmountDeltaToTargets() {
		List<AssessorEngine.SavingPlanItem> plans = List.of(
				plan("AAA111", 1L, 100.0, 1)
		);
		Map<Integer, BigDecimal> targets = Map.of(
				1, BigDecimal.ONE
		);

		AssessorEngine.AssessorEngineResult result = engine.assess(new AssessorEngine.AssessorEngineInput(
				"BALANCED",
				targets,
				BigDecimal.ZERO,
				15,
				10,
				25,
				null,
				null,
				null,
				plans,
				new BigDecimal("50"),
				null,
				Map.of(),
				false
		));

		assertThat(result.currentMonthlyTotal()).isEqualByComparingTo(new BigDecimal("150"));
		assertThat(result.targetLayerDistribution().get(1)).isEqualByComparingTo(new BigDecimal("150"));
	}

	@Test
	void longerProjectionHorizonKeepsTargetsCloserToCurrentDistribution() {
		List<AssessorEngine.SavingPlanItem> plans = List.of(
				plan("AAA111", 1L, 500.0, 2)
		);
		Map<Integer, BigDecimal> targets = Map.of(
				1, new BigDecimal("0.60"),
				2, new BigDecimal("0.20"),
				3, new BigDecimal("0.15"),
				4, new BigDecimal("0.05")
		);
		Map<Integer, BigDecimal> holdings = Map.of(
				2, new BigDecimal("2000")
		);

		AssessorEngine.AssessorEngineResult result12 = engine.assess(new AssessorEngine.AssessorEngineInput(
				"BALANCED",
				targets,
				new BigDecimal("3.0"),
				15,
				10,
				25,
				12,
				null,
				null,
				plans,
				null,
				null,
				holdings,
				false
		));

		AssessorEngine.AssessorEngineResult result120 = engine.assess(new AssessorEngine.AssessorEngineInput(
				"BALANCED",
				targets,
				new BigDecimal("3.0"),
				15,
				10,
				25,
				120,
				null,
				null,
				plans,
				null,
				null,
				holdings,
				false
		));

		double delta12 = distributionDelta(result12.currentLayerDistribution(), result12.targetLayerDistribution(), result12.currentMonthlyTotal());
		double delta120 = distributionDelta(result120.currentLayerDistribution(), result120.targetLayerDistribution(), result120.currentMonthlyTotal());

		assertThat(delta12).isGreaterThan(0.0d);
		assertThat(delta120).isLessThan(delta12);
	}

	private double distributionDelta(Map<Integer, BigDecimal> current,
								 Map<Integer, BigDecimal> proposed,
								 BigDecimal total) {
		if (total == null || total.signum() <= 0) {
			return 0.0d;
		}
		double sum = 0.0d;
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal currentAmount = current.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal proposedAmount = proposed.getOrDefault(layer, BigDecimal.ZERO);
			BigDecimal currentWeight = currentAmount.divide(total, 6, RoundingMode.HALF_UP);
			BigDecimal proposedWeight = proposedAmount.divide(total, 6, RoundingMode.HALF_UP);
			sum += currentWeight.subtract(proposedWeight).abs().doubleValue();
		}
		return sum;
	}

	@Test
	void appliesSavingPlanAmountDeltaToSuggestions() {
		List<AssessorEngine.SavingPlanItem> plans = List.of(
				plan("AAA111", 1L, 60.0, 1),
				plan("BBB222", 1L, 40.0, 2)
		);
		Map<Integer, BigDecimal> targets = Map.of(
				1, new BigDecimal("0.6"),
				2, new BigDecimal("0.4")
		);

		AssessorEngine.AssessorEngineResult result = engine.assess(new AssessorEngine.AssessorEngineInput(
				"BALANCED",
				targets,
				BigDecimal.ZERO,
				1,
				1,
				25,
				null,
				null,
				null,
				plans,
				new BigDecimal("20"),
				null,
				Map.of(),
				false
		));

		assertThat(result.savingPlanSuggestions())
				.extracting(AssessorEngine.SavingPlanSuggestion::isin)
				.containsExactlyInAnyOrder("AAA111", "BBB222");
		BigDecimal totalDelta = result.savingPlanSuggestions().stream()
				.map(AssessorEngine.SavingPlanSuggestion::delta)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		assertThat(totalDelta).isEqualByComparingTo(new BigDecimal("20"));
	}

	@Test
	void appliesMinimumInstrumentAmountToOneTimeLayers() {
		List<AssessorEngine.SavingPlanItem> plans = List.of(
				plan("AAA111", 1L, 50.0, 1),
				plan("BBB222", 1L, 50.0, 2)
		);
		Map<Integer, BigDecimal> targets = Map.of(
				1, new BigDecimal("0.5"),
				2, new BigDecimal("0.5")
		);

		AssessorEngine.AssessorEngineResult result = engine.assess(new AssessorEngine.AssessorEngineInput(
				"BALANCED",
				targets,
				BigDecimal.ZERO,
				15,
				10,
				25,
				null,
				null,
				null,
				plans,
				null,
				new BigDecimal("40"),
				Map.of(),
				false
		));

		assertThat(result.oneTimeAllocation()).isNotNull();
		assertThat(result.oneTimeAllocation().layerBuckets())
				.containsEntry(1, new BigDecimal("40"))
				.containsEntry(2, BigDecimal.ZERO);
	}

	private AssessorEngine.SavingPlanItem plan(String isin, Long depotId, double amount, int layer) {
		return new AssessorEngine.SavingPlanItem(isin, depotId, BigDecimal.valueOf(amount), layer);
	}
}
