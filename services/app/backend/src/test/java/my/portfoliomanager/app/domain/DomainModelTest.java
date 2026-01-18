package my.portfoliomanager.app.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DomainModelTest {
	@Test
	void depotGettersSetters() {
		Depot depot = new Depot();
		depot.setDepotId(1L);
		depot.setDepotCode("tr");
		depot.setName("Trade Republic");
		depot.setProvider("TR");
		depot.setActiveSnapshotId(10L);

		assertThat(depot.getDepotId()).isEqualTo(1L);
		assertThat(depot.getDepotCode()).isEqualTo("tr");
		assertThat(depot.getName()).isEqualTo("Trade Republic");
		assertThat(depot.getProvider()).isEqualTo("TR");
		assertThat(depot.getActiveSnapshotId()).isEqualTo(10L);
	}

	@Test
	void instrumentGettersSetters() {
		Instrument instrument = new Instrument();
		instrument.setIsin("ISIN");
		instrument.setName("Test");
		instrument.setDepotCode("tr");
		instrument.setInstrumentType("ETF");
		instrument.setAssetClass("Equity");
		instrument.setSubClass("Global");
		instrument.setLayer(3);
		instrument.setLayerLastChanged(LocalDate.now());
		instrument.setLayerNotes("note");
		instrument.setDeleted(true);

		assertThat(instrument.getIsin()).isEqualTo("ISIN");
		assertThat(instrument.getName()).isEqualTo("Test");
		assertThat(instrument.getDepotCode()).isEqualTo("tr");
		assertThat(instrument.getInstrumentType()).isEqualTo("ETF");
		assertThat(instrument.getAssetClass()).isEqualTo("Equity");
		assertThat(instrument.getSubClass()).isEqualTo("Global");
		assertThat(instrument.getLayer()).isEqualTo(3);
		assertThat(instrument.getLayerLastChanged()).isNotNull();
		assertThat(instrument.getLayerNotes()).isEqualTo("note");
		assertThat(instrument.isDeleted()).isTrue();
	}

	@Test
	void savingPlanGettersSetters() {
		SavingPlan savingPlan = new SavingPlan();
		savingPlan.setSavingPlanId(2L);
		savingPlan.setDepotId(1L);
		savingPlan.setIsin("ISIN");
		savingPlan.setName("Plan");
		savingPlan.setAmountEur(BigDecimal.TEN);
		savingPlan.setFrequency("monthly");
		savingPlan.setDayOfMonth(5);
		savingPlan.setActive(true);
		savingPlan.setLastChanged(LocalDate.now());

		assertThat(savingPlan.getSavingPlanId()).isEqualTo(2L);
		assertThat(savingPlan.getDepotId()).isEqualTo(1L);
		assertThat(savingPlan.getIsin()).isEqualTo("ISIN");
		assertThat(savingPlan.getName()).isEqualTo("Plan");
		assertThat(savingPlan.getAmountEur()).isEqualTo(BigDecimal.TEN);
		assertThat(savingPlan.getFrequency()).isEqualTo("monthly");
		assertThat(savingPlan.getDayOfMonth()).isEqualTo(5);
		assertThat(savingPlan.isActive()).isTrue();
		assertThat(savingPlan.getLastChanged()).isNotNull();
	}

	@Test
	void snapshotGettersSetters() {
		Snapshot snapshot = new Snapshot();
		snapshot.setSnapshotId(10L);
		snapshot.setDepotId(1L);
		snapshot.setAsOfDate(LocalDate.now());
		snapshot.setSource("TR_PDF");
		snapshot.setFileHash("hash");
		snapshot.setImportedAt(LocalDateTime.now());

		assertThat(snapshot.getSnapshotId()).isEqualTo(10L);
		assertThat(snapshot.getDepotId()).isEqualTo(1L);
		assertThat(snapshot.getAsOfDate()).isNotNull();
		assertThat(snapshot.getSource()).isEqualTo("TR_PDF");
		assertThat(snapshot.getFileHash()).isEqualTo("hash");
		assertThat(snapshot.getImportedAt()).isNotNull();
	}

	@Test
	void snapshotPositionGettersSetters() {
		SnapshotPositionId id = new SnapshotPositionId(10L, "ISIN");
		SnapshotPosition position = new SnapshotPosition();
		position.setId(id);
		position.setName("Name");
		position.setShares(BigDecimal.ONE);
		position.setValueEur(BigDecimal.TEN);
		position.setCurrency("EUR");

		assertThat(position.getId()).isEqualTo(id);
		assertThat(position.getName()).isEqualTo("Name");
		assertThat(position.getShares()).isEqualTo(BigDecimal.ONE);
		assertThat(position.getValueEur()).isEqualTo(BigDecimal.TEN);
		assertThat(position.getCurrency()).isEqualTo("EUR");
	}

	@Test
	void snapshotPositionIdEquality() {
		SnapshotPositionId id = new SnapshotPositionId(1L, "ISIN");
		SnapshotPositionId same = new SnapshotPositionId(1L, "ISIN");
		SnapshotPositionId other = new SnapshotPositionId(2L, "OTHER");
		SnapshotPositionId partial = new SnapshotPositionId(1L, "OTHER");

		assertThat(id).isEqualTo(id);
		assertThat(id).isEqualTo(same);
		assertThat(id).isNotEqualTo(other);
		assertThat(id).isNotEqualTo(partial);
		assertThat(id).isNotEqualTo(null);
		assertThat(id).isNotEqualTo("ISIN");
		assertThat(id.hashCode()).isEqualTo(same.hashCode());
	}

	@Test
	void snapshotPositionIdGettersSetters() {
		SnapshotPositionId id = new SnapshotPositionId();
		id.setSnapshotId(5L);
		id.setIsin("ISIN5");

		assertThat(id.getSnapshotId()).isEqualTo(5L);
		assertThat(id.getIsin()).isEqualTo("ISIN5");
	}

	@Test
	void importFileGettersSetters() {
		ImportFile file = new ImportFile();
		file.setFileId(1L);
		file.setDepotCode("tr");
		file.setSource("TR_PDF");
		file.setFilename("file.pdf");
		file.setFileHash("hash");
		file.setAsOfDate(LocalDate.now());
		file.setImportedAt(LocalDateTime.now());
		file.setStatus("imported");
		file.setError("none");

		assertThat(file.getFileId()).isEqualTo(1L);
		assertThat(file.getDepotCode()).isEqualTo("tr");
		assertThat(file.getSource()).isEqualTo("TR_PDF");
		assertThat(file.getFilename()).isEqualTo("file.pdf");
		assertThat(file.getFileHash()).isEqualTo("hash");
		assertThat(file.getAsOfDate()).isNotNull();
		assertThat(file.getImportedAt()).isNotNull();
		assertThat(file.getStatus()).isEqualTo("imported");
		assertThat(file.getError()).isEqualTo("none");
	}

	@Test
	void rulesetGettersSetters() {
		Ruleset ruleset = new Ruleset();
		ruleset.setId(1L);
		ruleset.setName("default");
		ruleset.setVersion(1);
		ruleset.setContentYaml("schema_version: 1");
		ruleset.setActive(true);
		ruleset.setCreatedAt(LocalDateTime.now());
		ruleset.setUpdatedAt(LocalDateTime.now());

		assertThat(ruleset.getId()).isEqualTo(1L);
		assertThat(ruleset.getName()).isEqualTo("default");
		assertThat(ruleset.getVersion()).isEqualTo(1);
		assertThat(ruleset.getContentYaml()).contains("schema_version");
		assertThat(ruleset.isActive()).isTrue();
		assertThat(ruleset.getCreatedAt()).isNotNull();
		assertThat(ruleset.getUpdatedAt()).isNotNull();
	}

	@Test
	void instrumentEditGettersSetters() {
		InstrumentEdit edit = new InstrumentEdit();
		edit.setId(1L);
		edit.setIsin("ISIN");
		edit.setField("layer");
		edit.setOldValue("5");
		edit.setNewValue("2");
		edit.setEditedAt(LocalDateTime.now());
		edit.setEditedBy("admin");
		edit.setSource("ruleset_apply");

		assertThat(edit.getId()).isEqualTo(1L);
		assertThat(edit.getIsin()).isEqualTo("ISIN");
		assertThat(edit.getField()).isEqualTo("layer");
		assertThat(edit.getOldValue()).isEqualTo("5");
		assertThat(edit.getNewValue()).isEqualTo("2");
		assertThat(edit.getEditedAt()).isNotNull();
		assertThat(edit.getEditedBy()).isEqualTo("admin");
		assertThat(edit.getSource()).isEqualTo("ruleset_apply");
	}
}
