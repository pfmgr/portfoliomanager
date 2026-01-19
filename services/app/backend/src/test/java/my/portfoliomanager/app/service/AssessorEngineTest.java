package my.portfoliomanager.app.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
