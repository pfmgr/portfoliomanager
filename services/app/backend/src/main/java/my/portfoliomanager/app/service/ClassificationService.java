package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.Instrument;
import my.portfoliomanager.app.dto.ClassificationDto;
import my.portfoliomanager.app.dto.FiredRuleDto;
import my.portfoliomanager.app.dto.ImpactDto;
import my.portfoliomanager.app.dto.ReclassificationDto;
import my.portfoliomanager.app.repository.InstrumentRepository;
import my.portfoliomanager.app.repository.InstrumentOverrideRepository;
import my.portfoliomanager.app.repository.SavingPlanRepository;
import my.portfoliomanager.app.repository.SnapshotPositionRepository;
import my.portfoliomanager.app.rules.RulesEngine;
import my.portfoliomanager.app.rules.RulesetDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClassificationService {
	private final InstrumentRepository instrumentRepository;
	private final InstrumentOverrideRepository overrideRepository;
	private final SavingPlanRepository savingPlanRepository;
	private final SnapshotPositionRepository snapshotPositionRepository;
	private final AuditService auditService;
	private final KnowledgeBaseClassificationService knowledgeBaseClassificationService;
	private final RulesEngine rulesEngine;

	public ClassificationService(InstrumentRepository instrumentRepository,
								InstrumentOverrideRepository overrideRepository,
								SavingPlanRepository savingPlanRepository,
								SnapshotPositionRepository snapshotPositionRepository,
								AuditService auditService,
								KnowledgeBaseClassificationService knowledgeBaseClassificationService) {
		this.instrumentRepository = instrumentRepository;
		this.overrideRepository = overrideRepository;
		this.savingPlanRepository = savingPlanRepository;
		this.snapshotPositionRepository = snapshotPositionRepository;
		this.auditService = auditService;
		this.knowledgeBaseClassificationService = knowledgeBaseClassificationService;
		this.rulesEngine = new RulesEngine();
	}

	public List<ReclassificationDto> simulate(RulesetDefinition ruleset) {
		return simulateInternal(ruleset, null);
	}

	public List<ReclassificationDto> simulateAsOf(RulesetDefinition ruleset, LocalDate asOfDate) {
		return simulateInternal(ruleset, asOfDate);
	}

	@Transactional
	public List<ReclassificationDto> apply(RulesetDefinition ruleset, boolean dryRun, String editedBy,
										   List<String> isins) {
		List<ReclassificationDto> results = simulate(ruleset);
		if (isins != null && !isins.isEmpty()) {
			results = results.stream().filter(r -> isins.contains(r.isin())).toList();
		}
		if (dryRun) {
			return results;
		}
		for (ReclassificationDto result : results) {
			applyInstrumentChanges(result, editedBy);
		}
		return results;
	}

	private List<ReclassificationDto> simulateInternal(RulesetDefinition ruleset, LocalDate asOfDate) {
		Map<String, BigDecimal> savingPlanTotals = loadSavingPlanTotals();
		ImpactContext impactContext = loadImpactContext(asOfDate);

		List<Instrument> instruments = instrumentRepository.findAll();
		Map<String, KnowledgeBaseClassificationService.Suggestion> kbSuggestions =
				knowledgeBaseClassificationService.findSuggestions(
						instruments.stream().map(Instrument::getIsin).toList()
				);

		List<ReclassificationDto> results = new ArrayList<>();
		for (Instrument instrument : instruments) {
			boolean savingPlanActive = savingPlanRepository.existsActiveByIsin(instrument.getIsin());
			var evaluation = rulesEngine.evaluate(instrument, savingPlanActive, ruleset);
			ClassificationDto proposed = toDto(evaluation.proposed());
			double confidence = evaluation.confidence();
			List<FiredRuleDto> firedRules = evaluation.firedRules()
					.stream()
					.map(fr -> new FiredRuleDto(fr.id(), fr.priority(), fr.score()))
					.toList();
			KnowledgeBaseClassificationService.Suggestion kbSuggestion = kbSuggestions.get(instrument.getIsin());
			String suggestedName = kbSuggestion == null ? null : kbSuggestion.name();
			if (kbSuggestion != null) {
				proposed = mergeClassification(kbSuggestion.classification(), proposed);
				confidence = 1.0d;
			}
			ClassificationDto current = new ClassificationDto(
					instrument.getInstrumentType(),
					instrument.getAssetClass(),
					instrument.getSubClass(),
					instrument.getLayer()
			);
			PolicyAdjustment policyAdjustment = applyPolicies(ruleset, proposed, savingPlanActive);
			ImpactDto impact = buildImpact(instrument.getIsin(), impactContext, savingPlanTotals);

			results.add(new ReclassificationDto(
					instrument.getIsin(),
					instrument.getName(),
					suggestedName,
					current,
					proposed,
					policyAdjustment.classification(),
					confidence,
					firedRules,
					policyAdjustment.notes(),
					impact
			));
		}
		return results;
	}

	private PolicyAdjustment applyPolicies(RulesetDefinition ruleset, ClassificationDto proposed, boolean savingPlanActive) {
		List<String> notes = new ArrayList<>();
		ClassificationDto adjusted = proposed;
		if (ruleset.getPolicies() != null && ruleset.getPolicies().isLayer1RequiresSavingPlan()) {
			if (proposed.layer() != null && proposed.layer() == 1 && !savingPlanActive) {
				adjusted = new ClassificationDto(proposed.instrumentType(), proposed.assetClass(), proposed.subClass(), 2);
				notes.add("layer1_requires_savingPlan applied -> layer=2");
			}
		}
		return new PolicyAdjustment(adjusted, notes);
	}

	private ImpactContext loadImpactContext(LocalDate asOfDate) {
		Map<String, BigDecimal> valueByIsin = new HashMap<>();
		double totalValue = 0.0d;
		if (asOfDate == null) {
			List<Object[]> values = snapshotPositionRepository.sumValueEurByIsinActiveSnapshots();
			for (Object[] row : values) {
				String isin = (String) row[0];
				BigDecimal value = row[1] == null ? BigDecimal.ZERO : new BigDecimal(row[1].toString());
				valueByIsin.put(isin, value);
			}
			Double total = snapshotPositionRepository.sumValueEurActiveSnapshots();
			if (total != null) {
				totalValue = total;
			}
		} else {
			List<Object[]> values = snapshotPositionRepository.sumValueEurByIsinAsOf(asOfDate);
			for (Object[] row : values) {
				String isin = (String) row[0];
				BigDecimal value = row[1] == null ? BigDecimal.ZERO : new BigDecimal(row[1].toString());
				valueByIsin.put(isin, value);
			}
			Double total = snapshotPositionRepository.sumValueEurAsOf(asOfDate);
			if (total != null) {
				totalValue = total;
			}
		}
		return new ImpactContext(valueByIsin, totalValue);
	}

	private Map<String, BigDecimal> loadSavingPlanTotals() {
		Map<String, BigDecimal> totals = new HashMap<>();
		for (Object[] row : savingPlanRepository.sumActiveAmountsByIsin()) {
			String isin = (String) row[0];
			BigDecimal amount = row[1] == null ? BigDecimal.ZERO : new BigDecimal(row[1].toString());
			totals.put(isin, amount);
		}
		return totals;
	}

	private ImpactDto buildImpact(String isin, ImpactContext impactContext, Map<String, BigDecimal> savingPlanTotals) {
		BigDecimal valueEur = impactContext.valueByIsin().getOrDefault(isin, BigDecimal.ZERO);
		double weight = impactContext.totalValue() == 0.0d ? 0.0d : valueEur.doubleValue() / impactContext.totalValue() * 100.0d;
		BigDecimal savingPlan = savingPlanTotals.getOrDefault(isin, BigDecimal.ZERO);
		return new ImpactDto(valueEur, weight, savingPlan);
	}

	private ClassificationDto toDto(RulesEngine.Classification classification) {
		return new ClassificationDto(classification.instrumentType(), classification.assetClass(), classification.subClass(),
				classification.layer());
	}

	private void applyInstrumentChanges(ReclassificationDto result, String editedBy) {
		instrumentRepository.findById(result.isin()).ifPresent(instrument -> {
			ClassificationDto target = result.policyAdjusted();
			updateField(instrument.getName(), result.suggestedName(), "name", instrument::setName, result.isin(), editedBy);
			updateField(instrument.getInstrumentType(), target.instrumentType(), "instrument_type", instrument::setInstrumentType, result.isin(), editedBy);
			updateField(instrument.getAssetClass(), target.assetClass(), "asset_class", instrument::setAssetClass, result.isin(), editedBy);
			updateField(instrument.getSubClass(), target.subClass(), "sub_class", instrument::setSubClass, result.isin(), editedBy);
			updateField(instrument.getLayer() == null ? null : instrument.getLayer().toString(),
					target.layer() == null ? null : target.layer().toString(),
						"layer", val -> instrument.setLayer(val == null ? instrument.getLayer() : Integer.valueOf(val)),
						result.isin(), editedBy);
			instrumentRepository.save(instrument);
			applyOverrideChanges(result.isin(), target, result.suggestedName(), editedBy);
		});
	}

	private void applyOverrideChanges(String isin, ClassificationDto target, String suggestedName, String editedBy) {
		if (target == null) {
			return;
		}
		overrideRepository.findById(isin).ifPresent(override -> {
			boolean changed = false;

			changed = updateOverrideFieldIfPresent(
					override.getName(),
					suggestedName,
					"name",
					override::setName,
					isin,
					editedBy
			) || changed;
			changed = updateOverrideFieldIfPresent(
					override.getInstrumentType(),
					target.instrumentType(),
					"instrument_type",
					override::setInstrumentType,
					isin,
					editedBy
			) || changed;
			changed = updateOverrideFieldIfPresent(
					override.getAssetClass(),
					target.assetClass(),
					"asset_class",
					override::setAssetClass,
					isin,
					editedBy
			) || changed;
			changed = updateOverrideFieldIfPresent(
					override.getSubClass(),
					target.subClass(),
					"sub_class",
					override::setSubClass,
					isin,
					editedBy
			) || changed;

			Integer overrideLayer = override.getLayer();
			Integer targetLayer = target.layer();
			if (overrideLayer != null && targetLayer != null && !overrideLayer.equals(targetLayer)) {
				override.setLayer(targetLayer);
				override.setLayerLastChanged(LocalDate.now());
				auditService.recordEdit(isin, "layer", overrideLayer.toString(), targetLayer.toString(), editedBy, "ruleset_apply_override");
				changed = true;
			}

			if (changed) {
				override.setUpdatedAt(LocalDateTime.now());
				overrideRepository.save(override);
			}
		});
	}

	private ClassificationDto mergeClassification(ClassificationDto kb, ClassificationDto fallback) {
		if (kb == null) {
			return fallback;
		}
		return new ClassificationDto(
				kb.instrumentType() == null ? fallback.instrumentType() : kb.instrumentType(),
				kb.assetClass() == null ? fallback.assetClass() : kb.assetClass(),
				kb.subClass() == null ? fallback.subClass() : kb.subClass(),
				kb.layer() == null ? fallback.layer() : kb.layer()
		);
	}

	private boolean updateOverrideFieldIfPresent(String oldValue, String newValue, String field,
												java.util.function.Consumer<String> updater,
												String isin, String editedBy) {
		if (oldValue == null) {
			return false;
		}
		if (newValue == null || newValue.equals(oldValue)) {
			return false;
		}
		updater.accept(newValue);
		auditService.recordEdit(isin, field, oldValue, newValue, editedBy, "ruleset_apply_override");
		return true;
	}

	private void updateField(String oldValue, String newValue, String field,
													java.util.function.Consumer<String> updater,
													String isin, String editedBy) {
		if (newValue == null || newValue.isBlank() || newValue.equals(oldValue)) {
			return;
		}
		updater.accept(newValue);
		auditService.recordEdit(isin, field, oldValue, newValue, editedBy, "ruleset_apply");
	}

	private record ImpactContext(Map<String, BigDecimal> valueByIsin, double totalValue) {
	}

	private record PolicyAdjustment(ClassificationDto classification, List<String> notes) {
	}
}
