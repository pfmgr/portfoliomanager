package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.Depot;
import my.portfoliomanager.app.domain.ImportFile;
import my.portfoliomanager.app.domain.Ruleset;
import my.portfoliomanager.app.domain.Snapshot;
import my.portfoliomanager.app.dto.ImportResultDto;
import my.portfoliomanager.app.repository.DepotRepository;
import my.portfoliomanager.app.repository.ImportFileRepository;
import my.portfoliomanager.app.repository.InstrumentRepository;
import my.portfoliomanager.app.repository.SnapshotPositionRepository;
import my.portfoliomanager.app.repository.SnapshotRepository;
import my.portfoliomanager.app.rules.RulesetDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportServiceTest {
	@Mock
	private DepotRepository depotRepository;

	@Mock
	private InstrumentRepository instrumentRepository;

	@Mock
	private SnapshotRepository snapshotRepository;

	@Mock
	private SnapshotPositionRepository snapshotPositionRepository;

	@Mock
	private ImportFileRepository importFileRepository;

	@Mock
	private RulesetService rulesetService;

	@Mock
	private ClassificationService classificationService;

	@InjectMocks
	private ImportService importService;

	@Test
	void importDepotStatementCreatesSnapshotAndPositions() {
		String csv = "Wertpapier;St_Nom;Wert;ISIN\n"
				+ "Alpha Fonds;1.000,00;2.000,00;DE0000000001\n";
		MockMultipartFile file = new MockMultipartFile("file", "deka.csv", "text/csv", csv.getBytes());

		Depot depot = new Depot();
		depot.setDepotId(1L);
		depot.setDepotCode("deka");
		depot.setName("Deka Depot");
		depot.setProvider("Deka");

		when(depotRepository.findByDepotCode("deka")).thenReturn(Optional.of(depot));
		when(importFileRepository.findByDepotCodeAndFileHashAndStatus(eq("deka"), any(), eq("imported")))
				.thenReturn(Optional.empty());
		when(instrumentRepository.findByIsinIn(anyList())).thenReturn(List.of());
		when(snapshotRepository.findByDepotIdAndAsOfDateAndSourceAndFileHash(eq(1L), any(LocalDate.class), any(), any()))
				.thenReturn(Optional.empty());
		when(snapshotRepository.save(any(Snapshot.class))).thenAnswer(invocation -> {
			Snapshot snapshot = invocation.getArgument(0);
			if (snapshot.getSnapshotId() == null) {
				snapshot.setSnapshotId(10L);
			}
			return snapshot;
		});
		when(snapshotPositionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(importFileRepository.findByDepotCodeAndFileHash(eq("deka"), any())).thenReturn(Optional.empty());
		when(importFileRepository.save(any(ImportFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(rulesetService.getActiveRuleset("default")).thenReturn(Optional.empty());
		ImportResultDto result = importService.importDepotStatement(file, "deka", false, true, true, "tester");

		assertThat(result.snapshotStatus()).isEqualTo("imported");
		assertThat(result.snapshotId()).isEqualTo(10L);
		assertThat(result.positions()).isEqualTo(1);
		verify(instrumentRepository).markDeletedForDepot(eq("deka"), anyList());
	}

	@Test
	void importDepotStatementSkipsDuplicateHash() {
		String csv = "Wertpapier;St_Nom;Wert;ISIN\n"
				+ "Alpha Fonds;1.000,00;2.000,00;DE0000000001\n";
		MockMultipartFile file = new MockMultipartFile("file", "deka.csv", "text/csv", csv.getBytes());

		Depot depot = new Depot();
		depot.setDepotId(1L);
		depot.setDepotCode("deka");
		depot.setName("Deka Depot");
		depot.setProvider("Deka");

		Snapshot snapshot = new Snapshot();
		snapshot.setSnapshotId(42L);

		when(depotRepository.findByDepotCode("deka")).thenReturn(Optional.of(depot));
		when(importFileRepository.findByDepotCodeAndFileHashAndStatus(eq("deka"), any(), eq("imported")))
				.thenReturn(Optional.of(new ImportFile()));
		when(snapshotRepository.findByDepotIdAndAsOfDateAndSourceAndFileHash(eq(1L), any(LocalDate.class), any(), any()))
				.thenReturn(Optional.of(snapshot));
		when(depotRepository.save(any(Depot.class))).thenAnswer(invocation -> invocation.getArgument(0));
		ImportResultDto result = importService.importDepotStatement(file, "deka", false, true, true, "tester");

		assertThat(result.snapshotStatus()).isEqualTo("skipped");
		assertThat(result.snapshotId()).isNull();
		assertThat(depot.getActiveSnapshotId()).isEqualTo(42L);
	}

	@Test
	void importDepotStatementAppliesRules() {
		String csv = "Wertpapier;St_Nom;Wert;ISIN\n"
				+ "Alpha Fonds;1.000,00;2.000,00;DE0000000001\n"
				+ "Alpha Fonds;2.000,00;3.000,00;DE0000000001\n";
		MockMultipartFile file = new MockMultipartFile("file", "deka.csv", "text/csv", csv.getBytes());

		Depot depot = new Depot();
		depot.setDepotId(1L);
		depot.setDepotCode("deka");
		depot.setName("Deka Depot");
		depot.setProvider("Deka");

		Ruleset ruleset = new Ruleset();
		ruleset.setContentYaml("schema_version: 1\nname: default\nrules: []\n");

		RulesetDefinition definition = new RulesetDefinition();
		definition.setSchemaVersion(1);
		definition.setName("default");

		when(depotRepository.findByDepotCode("deka")).thenReturn(Optional.of(depot));
		when(importFileRepository.findByDepotCodeAndFileHashAndStatus(eq("deka"), any(), eq("imported")))
				.thenReturn(Optional.empty());
		when(instrumentRepository.findByIsinIn(anyList())).thenReturn(List.of());
		when(snapshotRepository.findByDepotIdAndAsOfDateAndSourceAndFileHash(eq(1L), any(LocalDate.class), any(), any()))
				.thenReturn(Optional.empty());
		when(snapshotRepository.save(any(Snapshot.class))).thenAnswer(invocation -> {
			Snapshot snapshot = invocation.getArgument(0);
			if (snapshot.getSnapshotId() == null) {
				snapshot.setSnapshotId(11L);
			}
			return snapshot;
		});
		when(snapshotPositionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(importFileRepository.findByDepotCodeAndFileHash(eq("deka"), any())).thenReturn(Optional.empty());
		when(importFileRepository.save(any(ImportFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(rulesetService.getActiveRuleset("default")).thenReturn(Optional.of(ruleset));
			when(rulesetService.parseRuleset(ruleset.getContentYaml())).thenReturn(definition);
			when(rulesetService.validateDefinition(definition)).thenReturn(List.of());
			when(classificationService.apply(eq(definition), eq(false), eq("tester"), anyList())).thenReturn(List.of());

			ImportResultDto result = importService.importDepotStatement(file, "deka", false, false, true, "tester");

			assertThat(result.rulesApplied()).isZero();
	}

	@Test
	void importDepotStatementRejectsInvalidRuleset() {
		String csv = "Wertpapier;St_Nom;Wert;ISIN\n"
				+ "Alpha Fonds;1.000,00;2.000,00;DE0000000001\n";
		MockMultipartFile file = new MockMultipartFile("file", "deka.csv", "text/csv", csv.getBytes());

		Depot depot = new Depot();
		depot.setDepotId(1L);
		depot.setDepotCode("deka");
		depot.setName("Deka Depot");
		depot.setProvider("Deka");

		Ruleset ruleset = new Ruleset();
		ruleset.setContentYaml("schema_version: 1\nname: default\nrules: []\n");

		RulesetDefinition definition = new RulesetDefinition();
		definition.setSchemaVersion(1);
		definition.setName("default");

		when(depotRepository.findByDepotCode("deka")).thenReturn(Optional.of(depot));
		when(importFileRepository.findByDepotCodeAndFileHashAndStatus(eq("deka"), any(), eq("imported")))
				.thenReturn(Optional.empty());
		when(instrumentRepository.findByIsinIn(anyList())).thenReturn(List.of());
		when(snapshotRepository.findByDepotIdAndAsOfDateAndSourceAndFileHash(eq(1L), any(LocalDate.class), any(), any()))
				.thenReturn(Optional.empty());
		when(snapshotRepository.save(any(Snapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(snapshotPositionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(importFileRepository.findByDepotCodeAndFileHash(eq("deka"), any())).thenReturn(Optional.empty());
			when(importFileRepository.save(any(ImportFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(rulesetService.getActiveRuleset("default")).thenReturn(Optional.of(ruleset));
			when(rulesetService.parseRuleset(ruleset.getContentYaml())).thenReturn(definition);
			when(rulesetService.validateDefinition(definition)).thenReturn(List.of("bad"));

			assertThatThrownBy(() -> importService.importDepotStatement(file, "deka", false, false, true, "tester"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Active ruleset invalid");
	}

	@Test
	void importDepotStatementRejectsUnsupportedDepot() {
		MockMultipartFile file = new MockMultipartFile("file", "file.csv", "text/csv", "a".getBytes());

		assertThatThrownBy(() -> importService.importDepotStatement(file, "xx", false, true, false, "tester"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unsupported depot_code");
	}

	@Test
	void importDepotStatementRejectsWrongSuffix() {
		MockMultipartFile file = new MockMultipartFile("file", "file.pdf", "application/pdf", "a".getBytes());

		assertThatThrownBy(() -> importService.importDepotStatement(file, "deka", false, true, false, "tester"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Expected .csv");
	}

	@Test
	void importDepotStatementRejectsEmptyFile() {
		MockMultipartFile file = new MockMultipartFile("file", "file.csv", "text/csv", new byte[0]);

		assertThatThrownBy(() -> importService.importDepotStatement(file, "deka", false, true, false, "tester"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("File is empty");
	}

	@Test
	void importDepotStatementRejectsEmptyParserResult() {
		String csv = "Wertpapier;St_Nom;Wert;ISIN\n"
				+ "Alpha Fonds;1.000,00;2.000,00;INVALID\n";
		MockMultipartFile file = new MockMultipartFile("file", "deka.csv", "text/csv", csv.getBytes());

		assertThatThrownBy(() -> importService.importDepotStatement(file, "deka", false, true, false, "tester"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Parser returned 0 positions");
	}

	@Test
	void importDepotStatementDoesNotPruneWhenDisabled() {
		String csv = "Wertpapier;St_Nom;Wert;ISIN\n"
				+ "Alpha Fonds;1.000,00;2.000,00;DE0000000001\n";
		MockMultipartFile file = new MockMultipartFile("file", "deka.csv", "text/csv", csv.getBytes());

		Depot depot = new Depot();
		depot.setDepotId(1L);
		depot.setDepotCode("deka");
		depot.setName("Deka Depot");
		depot.setProvider("Deka");

		when(depotRepository.findByDepotCode("deka")).thenReturn(Optional.of(depot));
		when(importFileRepository.findByDepotCodeAndFileHashAndStatus(eq("deka"), any(), eq("imported")))
				.thenReturn(Optional.empty());
		when(instrumentRepository.findByIsinIn(anyList())).thenReturn(List.of());
		when(snapshotRepository.findByDepotIdAndAsOfDateAndSourceAndFileHash(eq(1L), any(LocalDate.class), any(), any()))
				.thenReturn(Optional.empty());
		when(snapshotRepository.save(any(Snapshot.class))).thenAnswer(invocation -> {
			Snapshot snapshot = invocation.getArgument(0);
			snapshot.setSnapshotId(12L);
			return snapshot;
		});
			when(snapshotPositionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
			when(importFileRepository.findByDepotCodeAndFileHash(eq("deka"), any())).thenReturn(Optional.empty());
			when(importFileRepository.save(any(ImportFile.class))).thenAnswer(invocation -> invocation.getArgument(0));

			importService.importDepotStatement(file, "deka", false, false, false, "tester");

		org.mockito.Mockito.verify(instrumentRepository, org.mockito.Mockito.never())
				.markDeletedForDepot(any(), anyList());
	}

	@Test
	void importDepotStatementSetsNullForZeroValues() {
		String csv = "Wertpapier;St_Nom;Wert;ISIN\n"
				+ "Alpha Fonds;;;DE0000000001\n";
		MockMultipartFile file = new MockMultipartFile("file", "deka.csv", "text/csv", csv.getBytes());

		Depot depot = new Depot();
		depot.setDepotId(1L);
		depot.setDepotCode("deka");
		depot.setName("Deka Depot");
		depot.setProvider("Deka");

		when(depotRepository.findByDepotCode("deka")).thenReturn(Optional.of(depot));
		when(importFileRepository.findByDepotCodeAndFileHashAndStatus(eq("deka"), any(), eq("imported")))
				.thenReturn(Optional.empty());
		when(instrumentRepository.findByIsinIn(anyList())).thenReturn(List.of());
		when(snapshotRepository.findByDepotIdAndAsOfDateAndSourceAndFileHash(eq(1L), any(LocalDate.class), any(), any()))
				.thenReturn(Optional.empty());
		when(snapshotRepository.save(any(Snapshot.class))).thenAnswer(invocation -> {
			Snapshot snapshot = invocation.getArgument(0);
			if (snapshot.getSnapshotId() == null) {
				snapshot.setSnapshotId(20L);
			}
			return snapshot;
		});
		when(snapshotPositionRepository.saveAll(any())).thenAnswer(invocation -> {
			List<?> saved = invocation.getArgument(0);
			assertThat(saved).hasSize(1);
			var sp = (my.portfoliomanager.app.domain.SnapshotPosition) saved.get(0);
			assertThat(sp.getShares()).isNull();
			assertThat(sp.getValueEur()).isNull();
			return saved;
			});
			when(importFileRepository.findByDepotCodeAndFileHash(eq("deka"), any())).thenReturn(Optional.empty());
			when(importFileRepository.save(any(ImportFile.class))).thenAnswer(invocation -> invocation.getArgument(0));

			importService.importDepotStatement(file, "deka", false, false, false, "tester");
		}
	}
