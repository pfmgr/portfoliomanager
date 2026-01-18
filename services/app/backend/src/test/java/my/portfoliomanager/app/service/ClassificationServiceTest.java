package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.Instrument;
import my.portfoliomanager.app.dto.ClassificationDto;
import my.portfoliomanager.app.repository.InstrumentRepository;
import my.portfoliomanager.app.repository.InstrumentOverrideRepository;
import my.portfoliomanager.app.repository.SavingPlanRepository;
import my.portfoliomanager.app.repository.SnapshotPositionRepository;
import my.portfoliomanager.app.rules.ActionsDefinition;
import my.portfoliomanager.app.rules.MatchDefinition;
import my.portfoliomanager.app.rules.PoliciesDefinition;
import my.portfoliomanager.app.rules.RuleDefinition;
import my.portfoliomanager.app.rules.RulesetDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassificationServiceTest {
	@Mock
	private InstrumentRepository instrumentRepository;

	@Mock
	private InstrumentOverrideRepository overrideRepository;

	@Mock
	private SavingPlanRepository savingPlanRepository;

	@Mock
	private SnapshotPositionRepository snapshotPositionRepository;

	@Mock
	private AuditService auditService;

	@Mock
	private KnowledgeBaseClassificationService knowledgeBaseClassificationService;

	@InjectMocks
	private ClassificationService classificationService;

	@BeforeEach
	void stubKnowledgeBase() {
		when(knowledgeBaseClassificationService.findSuggestions(anyList())).thenReturn(Map.of());
	}

	@Test
	void applyUpdatesLayerAndWritesAudit() {
		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN1");
		instrument.setName("Test ETF");
		instrument.setLayer(5);
		instrument.setInstrumentType("ETF");

		when(instrumentRepository.findAll()).thenReturn(List.of(instrument));
		when(instrumentRepository.findById("ISIN1")).thenReturn(java.util.Optional.of(instrument));
		when(instrumentRepository.save(any(Instrument.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(overrideRepository.findById("ISIN1")).thenReturn(java.util.Optional.empty());
		when(savingPlanRepository.existsActiveByIsin("ISIN1")).thenReturn(false);
		when(savingPlanRepository.sumActiveAmountsByIsin()).thenReturn(List.of());
		when(snapshotPositionRepository.sumValueEurByIsinActiveSnapshots())
				.thenReturn(List.<Object[]>of(new Object[]{"ISIN1", BigDecimal.valueOf(1000)}));
		when(snapshotPositionRepository.sumValueEurActiveSnapshots()).thenReturn(1000.0);

		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("default");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		rule.setPriority(10);
		rule.setScore(50);
		MatchDefinition match = new MatchDefinition();
		match.setField("name_norm");
		match.setOperator("CONTAINS");
		match.setValue("etf");
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(1);
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));
		PoliciesDefinition policies = new PoliciesDefinition();
		policies.setLayer1RequiresSavingPlan(true);
		ruleset.setPolicies(policies);

		var results = classificationService.apply(ruleset, false, "tester", List.of("ISIN1"));
		assertThat(results).hasSize(1);
		assertThat(results.get(0).policyAdjusted().layer()).isEqualTo(2);

		ArgumentCaptor<Instrument> captor = ArgumentCaptor.forClass(Instrument.class);
		verify(instrumentRepository).save(captor.capture());
		assertThat(captor.getValue().getLayer()).isEqualTo(2);
		verify(auditService, times(1)).recordEdit(any(), any(), any(), any(), any(), any());
	}

	@Test
	void dryRunDoesNotPersistChanges() {
		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN2");
		instrument.setName("Global ETF");
		instrument.setLayer(5);

		when(instrumentRepository.findAll()).thenReturn(List.of(instrument));
		when(savingPlanRepository.existsActiveByIsin("ISIN2")).thenReturn(true);
		when(savingPlanRepository.sumActiveAmountsByIsin()).thenReturn(List.of());
		when(snapshotPositionRepository.sumValueEurByIsinActiveSnapshots()).thenReturn(List.of());
		when(snapshotPositionRepository.sumValueEurActiveSnapshots()).thenReturn(0.0);

		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("default");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		rule.setPriority(10);
		rule.setScore(50);
		MatchDefinition match = new MatchDefinition();
		match.setField("name_norm");
		match.setOperator("CONTAINS");
		match.setValue("etf");
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(1);
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));

		var results = classificationService.apply(ruleset, true, "tester", List.of("ISIN2"));
		assertThat(results).hasSize(1);
		verify(instrumentRepository, times(0)).save(any());
		verify(auditService, times(0)).recordEdit(any(), any(), any(), any(), any(), any());
	}

	@Test
	void kbSuggestionOverridesRulesInSimulation() {
		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN-KB");
		instrument.setName("Legacy Name");
		instrument.setLayer(5);

		when(instrumentRepository.findAll()).thenReturn(List.of(instrument));
		when(savingPlanRepository.existsActiveByIsin("ISIN-KB")).thenReturn(false);
		when(savingPlanRepository.sumActiveAmountsByIsin()).thenReturn(List.of());
		when(snapshotPositionRepository.sumValueEurByIsinActiveSnapshots()).thenReturn(List.of());
		when(snapshotPositionRepository.sumValueEurActiveSnapshots()).thenReturn(0.0);

		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("default");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		rule.setPriority(10);
		rule.setScore(50);
		MatchDefinition match = new MatchDefinition();
		match.setField("name_norm");
		match.setOperator("CONTAINS");
		match.setValue("legacy");
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(3);
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));

		KnowledgeBaseClassificationService.Suggestion kbSuggestion =
				new KnowledgeBaseClassificationService.Suggestion(
						"KB Name",
						new ClassificationDto("ETF", "Equity", "Global", 1)
				);
		when(knowledgeBaseClassificationService.findSuggestions(anyList()))
				.thenReturn(Map.of("ISIN-KB", kbSuggestion));

		var results = classificationService.simulate(ruleset);
		assertThat(results).hasSize(1);
		assertThat(results.get(0).proposed().layer()).isEqualTo(1);
		assertThat(results.get(0).suggestedName()).isEqualTo("KB Name");
		assertThat(results.get(0).confidence()).isEqualTo(1.0d);
	}

	@Test
	void simulateAsOfUsesAsOfQueries() {
		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN3");
		instrument.setName("Test Bond");
		instrument.setLayer(5);

		when(instrumentRepository.findAll()).thenReturn(List.of(instrument));
		when(savingPlanRepository.existsActiveByIsin("ISIN3")).thenReturn(false);
		when(savingPlanRepository.sumActiveAmountsByIsin()).thenReturn(List.of());
		when(snapshotPositionRepository.sumValueEurByIsinAsOf(any()))
				.thenReturn(List.<Object[]>of(new Object[]{"ISIN3", BigDecimal.valueOf(200)}));
		when(snapshotPositionRepository.sumValueEurAsOf(any())).thenReturn(200.0);

		RulesetDefinition ruleset = new RulesetDefinition();
		ruleset.setSchemaVersion(1);
		ruleset.setName("default");
		RuleDefinition rule = new RuleDefinition();
		rule.setId("r1");
		rule.setPriority(10);
		rule.setScore(10);
		MatchDefinition match = new MatchDefinition();
		match.setField("name_norm");
		match.setOperator("CONTAINS");
		match.setValue("bond");
		rule.setMatch(match);
		ActionsDefinition actions = new ActionsDefinition();
		actions.setLayer(3);
		rule.setActions(actions);
		ruleset.setRules(List.of(rule));

		var results = classificationService.simulateAsOf(ruleset, java.time.LocalDate.now());
		assertThat(results).hasSize(1);
		assertThat(results.get(0).impact().valueEur()).isEqualTo(BigDecimal.valueOf(200));
	}
}
