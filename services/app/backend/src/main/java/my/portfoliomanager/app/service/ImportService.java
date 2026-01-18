package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.Depot;
import my.portfoliomanager.app.domain.ImportFile;
import my.portfoliomanager.app.domain.Instrument;
import my.portfoliomanager.app.domain.Snapshot;
import my.portfoliomanager.app.domain.SnapshotPosition;
import my.portfoliomanager.app.domain.SnapshotPositionId;
import my.portfoliomanager.app.dto.ImportResultDto;
import my.portfoliomanager.app.importer.DekaCsvParser;
import my.portfoliomanager.app.importer.DepotParser;
import my.portfoliomanager.app.importer.Position;
import my.portfoliomanager.app.importer.TrPdfParser;
import my.portfoliomanager.app.repository.DepotRepository;
import my.portfoliomanager.app.repository.ImportFileRepository;
import my.portfoliomanager.app.repository.InstrumentRepository;
import my.portfoliomanager.app.repository.SnapshotPositionRepository;
import my.portfoliomanager.app.repository.SnapshotRepository;
import my.portfoliomanager.app.rules.RulesetDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ImportService {
	private final DepotRepository depotRepository;
	private final InstrumentRepository instrumentRepository;
	private final SnapshotRepository snapshotRepository;
	private final SnapshotPositionRepository snapshotPositionRepository;
	private final ImportFileRepository importFileRepository;
	private final RulesetService rulesetService;
	private final ClassificationService classificationService;
	private final Map<String, DepotParser> parsers;

	public ImportService(DepotRepository depotRepository,
						 InstrumentRepository instrumentRepository,
						 SnapshotRepository snapshotRepository,
						 SnapshotPositionRepository snapshotPositionRepository,
						 ImportFileRepository importFileRepository,
						 RulesetService rulesetService,
						 ClassificationService classificationService) {
		this.depotRepository = depotRepository;
		this.instrumentRepository = instrumentRepository;
		this.snapshotRepository = snapshotRepository;
		this.snapshotPositionRepository = snapshotPositionRepository;
		this.importFileRepository = importFileRepository;
		this.rulesetService = rulesetService;
		this.classificationService = classificationService;
		this.parsers = Map.of(
				"tr", new TrPdfParser(),
				"deka", new DekaCsvParser()
		);
	}

	@Transactional
	public ImportResultDto importDepotStatement(MultipartFile file, String depotCode, boolean forceReimport,
												boolean pruneMissing, boolean applyRules, String editedBy) {
		String normalizedDepot = normalizeDepotCode(depotCode);
		DepotParser parser = parsers.get(normalizedDepot);
		if (parser == null) {
			throw new IllegalArgumentException("Unsupported depot_code: " + depotCode);
		}
		String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
		validateSuffix(normalizedDepot, filename);

		byte[] payload = readFile(file);
		String fileHash = sha256(payload);
		List<Position> positions = parser.parse(payload, filename, normalizedDepot, fileHash);
		if (positions.isEmpty()) {
			throw new IllegalArgumentException("Parser returned 0 positions");
		}

		Depot depot = depotRepository.findByDepotCode(normalizedDepot)
				.orElseThrow(() -> new IllegalArgumentException("Depot not found"));

		Position first = positions.get(0);
		if (!forceReimport && importFileRepository.findByDepotCodeAndFileHashAndStatus(normalizedDepot, fileHash, "imported").isPresent()) {
			snapshotRepository.findByDepotIdAndAsOfDateAndSourceAndFileHash(depot.getDepotId(), first.asOfDate(),
							first.source(), fileHash)
					.ifPresent(snapshot -> {
						depot.setActiveSnapshotId(snapshot.getSnapshotId());
						depotRepository.save(depot);
					});
			return new ImportResultDto(0, "skipped", null, 0, 0);
			}

			UpsertResult upsertResult = upsertInstruments(positions, pruneMissing);
			Snapshot snapshot = upsertSnapshot(depot, first, fileHash);
			int positionsImported = upsertSnapshotPositions(snapshot.getSnapshotId(), positions);

		upsertImportFile(normalizedDepot, filename, fileHash, first, "imported", null);

		depot.setActiveSnapshotId(snapshot.getSnapshotId());
		depotRepository.save(depot);

			int rulesAppliedCount = 0;
			if (applyRules) {
				RulesetDefinition ruleset = rulesetService.getActiveRuleset("default")
						.map(r -> rulesetService.parseRuleset(r.getContentYaml()))
						.orElse(null);
				if (ruleset != null) {
					List<String> errors = rulesetService.validateDefinition(ruleset);
					if (!errors.isEmpty()) {
					throw new IllegalArgumentException("Active ruleset invalid: " + String.join("; ", errors));
				}
				List<String> isins = positions.stream().map(Position::isin).distinct().toList();
				rulesAppliedCount = classificationService.apply(ruleset, false, editedBy, isins).size();
			}
		}

		return new ImportResultDto(upsertResult.created, "imported", snapshot.getSnapshotId(), positionsImported,
				rulesAppliedCount);
	}

	private String normalizeDepotCode(String depotCode) {
		return (depotCode == null ? "" : depotCode).trim().toLowerCase(Locale.ROOT);
	}

	private void validateSuffix(String depotCode, String filename) {
		String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
		String expected = depotCode.equals("tr") ? ".pdf" : ".csv";
		if (!lower.endsWith(expected)) {
			throw new IllegalArgumentException("Expected " + expected + " file for depot " + depotCode);
		}
	}

	private byte[] readFile(MultipartFile file) {
		try {
			byte[] payload = file.getBytes();
			if (payload.length == 0) {
				throw new IllegalArgumentException("File is empty");
			}
			return payload;
		} catch (IOException exc) {
			throw new IllegalArgumentException("Failed to read upload: " + exc.getMessage(), exc);
		}
	}

	private String sha256(byte[] payload) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(payload);
			return HexFormat.of().formatHex(hash);
		} catch (Exception exc) {
			throw new IllegalArgumentException("Failed to hash upload: " + exc.getMessage(), exc);
		}
	}

	private UpsertResult upsertInstruments(List<Position> positions, boolean pruneMissing) {
		Map<String, Position> dedup = new LinkedHashMap<>();
		for (Position position : positions) {
			dedup.putIfAbsent(position.isin(), position);
		}
		List<String> isins = new ArrayList<>(dedup.keySet());
		Map<String, Instrument> existing = new LinkedHashMap<>();
		for (Instrument instrument : instrumentRepository.findByIsinIn(isins)) {
			existing.put(instrument.getIsin(), instrument);
		}

		int created = 0;
		LocalDate today = LocalDate.now();
		List<Instrument> toSave = new ArrayList<>();
		for (Position position : dedup.values()) {
			Instrument instrument = existing.get(position.isin());
			if (instrument == null) {
				instrument = new Instrument();
				instrument.setIsin(position.isin());
				instrument.setName(position.name());
				instrument.setDepotCode(position.depotCode());
				instrument.setLayer(5);
				instrument.setLayerLastChanged(today);
				instrument.setDeleted(false);
				toSave.add(instrument);
				created += 1;
			} else {
				String existingName = instrument.getName();
				if (existingName == null || existingName.isBlank()) {
					instrument.setName(position.name());
				}
				instrument.setDepotCode(position.depotCode());
				instrument.setDeleted(false);
				toSave.add(instrument);
			}
		}
		instrumentRepository.saveAll(toSave);
		if (pruneMissing && !isins.isEmpty()) {
			instrumentRepository.markDeletedForDepot(positions.get(0).depotCode(), isins);
		}
		return new UpsertResult(created, toSave);
	}

	private static class UpsertResult {
		private final int created;
		private final List<Instrument> instruments;

		private UpsertResult(int created, List<Instrument> instruments) {
			this.created = created;
			this.instruments = instruments;
		}
	}

	private Snapshot upsertSnapshot(Depot depot, Position first, String fileHash) {
		Snapshot snapshot = snapshotRepository.findByDepotIdAndAsOfDateAndSourceAndFileHash(
				depot.getDepotId(), first.asOfDate(), first.source(), fileHash
		).orElseGet(Snapshot::new);
		snapshot.setDepotId(depot.getDepotId());
		snapshot.setAsOfDate(first.asOfDate());
		snapshot.setSource(first.source());
		snapshot.setFileHash(fileHash);
		snapshot.setImportedAt(LocalDateTime.now());
		return snapshotRepository.save(snapshot);
	}

	private int upsertSnapshotPositions(Long snapshotId, List<Position> positions) {
		Map<String, Aggregation> aggregated = new LinkedHashMap<>();
		for (Position position : positions) {
			Aggregation existing = aggregated.get(position.isin());
			if (existing == null) {
				aggregated.put(position.isin(), new Aggregation(position));
			} else {
				existing.add(position);
			}
		}
		List<SnapshotPosition> toSave = new ArrayList<>();
		for (Aggregation aggregation : aggregated.values()) {
			SnapshotPosition sp = new SnapshotPosition();
			sp.setId(new SnapshotPositionId(snapshotId, aggregation.position.isin()));
			sp.setName(aggregation.position.name());
			sp.setShares(aggregation.shares.signum() == 0 ? null : aggregation.shares);
			sp.setValueEur(aggregation.value.signum() == 0 ? null : aggregation.value);
			sp.setCurrency(aggregation.position.currency());
			toSave.add(sp);
		}
		snapshotPositionRepository.saveAll(toSave);
		return toSave.size();
	}

	private void upsertImportFile(String depotCode, String filename, String fileHash, Position first,
								  String status, String error) {
		ImportFile importFile = importFileRepository.findByDepotCodeAndFileHash(depotCode, fileHash)
				.orElseGet(ImportFile::new);
		importFile.setDepotCode(depotCode);
		importFile.setSource(first.source());
		importFile.setFilename(filename);
		importFile.setFileHash(fileHash);
		importFile.setAsOfDate(first.asOfDate());
		importFile.setImportedAt(LocalDateTime.now());
		importFile.setStatus(status);
		importFile.setError(error);
		importFileRepository.save(importFile);
	}

	private static class Aggregation {
		private final Position position;
		private BigDecimal shares;
		private BigDecimal value;

		private Aggregation(Position position) {
			this.position = position;
			this.shares = position.shares() == null ? BigDecimal.ZERO : position.shares();
			this.value = position.valueEur() == null ? BigDecimal.ZERO : position.valueEur();
		}

		private void add(Position position) {
			this.shares = this.shares.add(position.shares() == null ? BigDecimal.ZERO : position.shares());
			this.value = this.value.add(position.valueEur() == null ? BigDecimal.ZERO : position.valueEur());
		}
	}
}
