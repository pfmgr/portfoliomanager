package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.Depot;
import my.portfoliomanager.app.domain.Instrument;
import my.portfoliomanager.app.domain.SavingPlan;
import my.portfoliomanager.app.dto.SavingPlanDto;
import my.portfoliomanager.app.dto.SavingPlanImportResultDto;
import my.portfoliomanager.app.dto.SavingPlanUpsertRequest;
import my.portfoliomanager.app.repository.DepotRepository;
import my.portfoliomanager.app.repository.InstrumentRepository;
import my.portfoliomanager.app.repository.SavingPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavingPlanServiceTest {
	@Mock
	private SavingPlanRepository savingPlanRepository;

	@Mock
	private DepotRepository depotRepository;

	@Mock
	private InstrumentRepository instrumentRepository;

	@InjectMocks
	private SavingPlanService savingPlanService;

	private Depot depot;
	private Instrument instrument;

	@BeforeEach
	void setup() {
		depot = new Depot();
		depot.setDepotId(1L);
		depot.setDepotCode("tr");
		depot.setName("Trade Republic");
		depot.setProvider("TR");

		instrument = new Instrument();
		instrument.setIsin("DE0000000001");
		instrument.setName("Sample");
	}

	@Test
	void importCsvCreatesSavingPlan() {
		String csv = "depot_code,isin,amount_eur,frequency,day_of_month,active,last_changed\n"
				+ "tr,DE0000000001,25.00,monthly,15,true,2024-02-01\n";
		MockMultipartFile file = new MockMultipartFile("file", "savingPlans.csv", "text/csv", csv.getBytes());

		when(depotRepository.findAll()).thenReturn(List.of(depot));
		when(instrumentRepository.findByIsinIn(List.of("DE0000000001"))).thenReturn(List.of(instrument));
		when(savingPlanRepository.findByDepotIdAndIsin(1L, "DE0000000001")).thenReturn(Optional.empty());
		when(savingPlanRepository.save(any(SavingPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

		SavingPlanImportResultDto result = savingPlanService.importCsv(file);

		assertThat(result.created()).isEqualTo(1);
		assertThat(result.updated()).isZero();
		assertThat(result.skippedMissing()).isZero();
		assertThat(result.skippedUnchanged()).isZero();
		assertThat(result.skippedEmpty()).isZero();
	}

	@Test
	void createUsesDefaultsAndInstrumentName() {
		when(depotRepository.findById(1L)).thenReturn(Optional.of(depot));
		when(instrumentRepository.findById("DE0000000001")).thenReturn(Optional.of(instrument));
		when(savingPlanRepository.findByDepotIdAndIsin(1L, "DE0000000001")).thenReturn(Optional.empty());
		when(savingPlanRepository.save(any(SavingPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

		SavingPlanUpsertRequest request = new SavingPlanUpsertRequest(
				1L, "DE0000000001", null, BigDecimal.valueOf(25), null, null, null, null);

		SavingPlanDto result = savingPlanService.create(request);

		assertThat(result.frequency()).isEqualTo("monthly");
		assertThat(result.active()).isTrue();
		assertThat(result.name()).isEqualTo("Sample");
		assertThat(result.lastChanged()).isNotNull();
	}

	@Test
	void createThrowsWhenExisting() {
		when(depotRepository.findById(1L)).thenReturn(Optional.of(depot));
		when(instrumentRepository.findById("DE0000000001")).thenReturn(Optional.of(instrument));
		when(savingPlanRepository.findByDepotIdAndIsin(1L, "DE0000000001")).thenReturn(Optional.of(new SavingPlan()));

		SavingPlanUpsertRequest request = new SavingPlanUpsertRequest(
				1L, "DE0000000001", null, BigDecimal.valueOf(25), null, null, null, null);

		assertThatThrownBy(() -> savingPlanService.create(request))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("SavingPlan already exists");
	}

	@Test
	void updateChangesFields() {
		SavingPlan existing = new SavingPlan();
		existing.setSavingPlanId(5L);
		existing.setDepotId(1L);
		existing.setIsin("DE0000000001");
		existing.setName("Old");
		existing.setAmountEur(BigDecimal.valueOf(10));
		existing.setFrequency("monthly");
		existing.setDayOfMonth(1);
		existing.setActive(true);
		existing.setLastChanged(LocalDate.of(2024, 1, 1));

		when(savingPlanRepository.findById(5L)).thenReturn(Optional.of(existing));
		when(depotRepository.findById(1L)).thenReturn(Optional.of(depot));
		when(instrumentRepository.findById("DE0000000001")).thenReturn(Optional.of(instrument));
		when(savingPlanRepository.save(any(SavingPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

		SavingPlanUpsertRequest request = new SavingPlanUpsertRequest(
				1L, "DE0000000001", "New", BigDecimal.valueOf(20), "weekly", 15, false, null);

		SavingPlanDto result = savingPlanService.update(5L, request);

		assertThat(result.name()).isEqualTo("New");
		assertThat(result.amountEur()).isEqualByComparingTo("20");
		assertThat(result.frequency()).isEqualTo("weekly");
		assertThat(result.dayOfMonth()).isEqualTo(15);
		assertThat(result.active()).isFalse();
	}

	@Test
	void updateOnlyLastChanged() {
		SavingPlan existing = new SavingPlan();
		existing.setSavingPlanId(6L);
		existing.setDepotId(1L);
		existing.setIsin("DE0000000001");
		existing.setName("Name");
		existing.setAmountEur(BigDecimal.valueOf(10));
		existing.setFrequency("monthly");
		existing.setDayOfMonth(1);
		existing.setActive(true);
		existing.setLastChanged(LocalDate.of(2024, 1, 1));

		when(savingPlanRepository.findById(6L)).thenReturn(Optional.of(existing));
		when(depotRepository.findById(1L)).thenReturn(Optional.of(depot));
		when(instrumentRepository.findById("DE0000000001")).thenReturn(Optional.of(instrument));
		when(savingPlanRepository.save(any(SavingPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

		SavingPlanUpsertRequest request = new SavingPlanUpsertRequest(
				1L, "DE0000000001", "Name", BigDecimal.valueOf(10), "monthly", 1, true,
				LocalDate.of(2024, 2, 1));

		SavingPlanDto result = savingPlanService.update(6L, request);

		assertThat(result.lastChanged()).isEqualTo(LocalDate.of(2024, 2, 1));
	}

	@Test
	void deleteThrowsWhenMissing() {
		when(savingPlanRepository.existsById(99L)).thenReturn(false);

		assertThatThrownBy(() -> savingPlanService.delete(99L))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("SavingPlan not found");
	}

	@Test
	void importCsvUpdatesExistingAndSkipsMissing() {
		String csv = "depot_code,isin,amount_eur,frequency,day_of_month,active,last_changed\n"
				+ "tr,DE0000000001,30.00,,10,false,2024-03-01\n"
				+ "tr,DE0000000002,15.00,monthly,5,true,2024-03-01\n";
		MockMultipartFile file = new MockMultipartFile("file", "savingPlans.csv", "text/csv", csv.getBytes());

		SavingPlan existing = new SavingPlan();
		existing.setDepotId(1L);
		existing.setIsin("DE0000000001");
		existing.setName("Old");
		existing.setAmountEur(BigDecimal.valueOf(10));
		existing.setFrequency("weekly");
		existing.setDayOfMonth(1);
		existing.setActive(true);
		existing.setLastChanged(LocalDate.of(2024, 2, 1));

		when(depotRepository.findAll()).thenReturn(List.of(depot));
		when(instrumentRepository.findByIsinIn(List.of("DE0000000001", "DE0000000002"))).thenReturn(List.of(instrument));
		when(savingPlanRepository.findByDepotIdAndIsin(1L, "DE0000000001")).thenReturn(Optional.of(existing));
		when(savingPlanRepository.save(any(SavingPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

		SavingPlanImportResultDto result = savingPlanService.importCsv(file);

		assertThat(result.updated()).isEqualTo(1);
		assertThat(result.skippedMissing()).isEqualTo(1);
	}

	@Test
	void importCsvThrowsOnUnknownDepot() {
		String csv = "depot_code,isin,amount_eur\n"
				+ "xx,DE0000000001,25.00\n";
		MockMultipartFile file = new MockMultipartFile("file", "savingPlans.csv", "text/csv", csv.getBytes());

		when(depotRepository.findAll()).thenReturn(List.of(depot));

		assertThatThrownBy(() -> savingPlanService.importCsv(file))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unknown depot_code");
	}

	@Test
	void importCsvThrowsOnInvalidDay() {
		String csv = "depot_code,isin,amount_eur,day_of_month\n"
				+ "tr,DE0000000001,25.00,40\n";
		MockMultipartFile file = new MockMultipartFile("file", "savingPlans.csv", "text/csv", csv.getBytes());

		assertThatThrownBy(() -> savingPlanService.importCsv(file))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("day_of_month");
	}

	@Test
	void importCsvRejectsMissingRequiredHeader() {
		String csv = "depot_code,isin\n"
				+ "tr,DE0000000001\n";
		MockMultipartFile file = new MockMultipartFile("file", "savingPlans.csv", "text/csv", csv.getBytes());

		assertThatThrownBy(() -> savingPlanService.importCsv(file))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("amount_eur");
	}

	@Test
	void importCsvRejectsInvalidAmount() {
		String csv = "depot_code,isin,amount_eur\n"
				+ "tr,DE0000000001,abc\n";
		MockMultipartFile file = new MockMultipartFile("file", "savingPlans.csv", "text/csv", csv.getBytes());

		assertThatThrownBy(() -> savingPlanService.importCsv(file))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("amount_eur must be numeric");
	}

	@Test
	void importCsvRejectsInvalidActiveFlag() {
		String csv = "depot_code,isin,amount_eur,active\n"
				+ "tr,DE0000000001,25.00,maybe\n";
		MockMultipartFile file = new MockMultipartFile("file", "savingPlans.csv", "text/csv", csv.getBytes());

		assertThatThrownBy(() -> savingPlanService.importCsv(file))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("active must be true/false");
	}

	@Test
	void importCsvRejectsInvalidIsin() {
		String csv = "depot_code,isin,amount_eur\n"
				+ "tr,INVALID,25.00\n";
		MockMultipartFile file = new MockMultipartFile("file", "savingPlans.csv", "text/csv", csv.getBytes());

		assertThatThrownBy(() -> savingPlanService.importCsv(file))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Invalid ISIN");
	}

	@Test
	void importCsvRejectsEmptyData() {
		String csv = "depot_code,isin,amount_eur\n";
		MockMultipartFile file = new MockMultipartFile("file", "savingPlans.csv", "text/csv", csv.getBytes());

		assertThatThrownBy(() -> savingPlanService.importCsv(file))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("no savingPlans");
	}

	@Test
	void importCsvDefaultsActiveWhenColumnMissing() {
		String csv = "depot_code,isin,amount_eur,frequency\n"
				+ "tr,DE0000000001,25.00,\n";
		MockMultipartFile file = new MockMultipartFile("file", "savingPlans.csv", "text/csv", csv.getBytes());

		when(depotRepository.findAll()).thenReturn(List.of(depot));
		when(instrumentRepository.findByIsinIn(List.of("DE0000000001"))).thenReturn(List.of(instrument));
		when(savingPlanRepository.findByDepotIdAndIsin(1L, "DE0000000001")).thenReturn(Optional.empty());
		when(savingPlanRepository.save(any(SavingPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

		SavingPlanImportResultDto result = savingPlanService.importCsv(file);

		assertThat(result.created()).isEqualTo(1);
	}

	@Test
	void importCsvParsesExplicitActiveFalse() {
		String csv = "depot_code,isin,amount_eur,active,day_of_month\n"
				+ "tr,DE0000000001,25.00,0,\n";
		MockMultipartFile file = new MockMultipartFile("file", "savingPlans.csv", "text/csv", csv.getBytes());

		when(depotRepository.findAll()).thenReturn(List.of(depot));
		when(instrumentRepository.findByIsinIn(List.of("DE0000000001"))).thenReturn(List.of(instrument));
		when(savingPlanRepository.findByDepotIdAndIsin(1L, "DE0000000001")).thenReturn(Optional.empty());
		when(savingPlanRepository.save(any(SavingPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

		SavingPlanImportResultDto result = savingPlanService.importCsv(file);

		assertThat(result.created()).isEqualTo(1);
	}

	@Test
	void importCsvAcceptsBlankActiveValue() {
		String csv = "depot_code,isin,amount_eur,active\n"
				+ "tr,DE0000000001,25.00,\n";
		MockMultipartFile file = new MockMultipartFile("file", "savingPlans.csv", "text/csv", csv.getBytes());

		when(depotRepository.findAll()).thenReturn(List.of(depot));
		when(instrumentRepository.findByIsinIn(List.of("DE0000000001"))).thenReturn(List.of(instrument));
		when(savingPlanRepository.findByDepotIdAndIsin(1L, "DE0000000001")).thenReturn(Optional.empty());
		when(savingPlanRepository.save(any(SavingPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

		SavingPlanImportResultDto result = savingPlanService.importCsv(file);

		assertThat(result.created()).isEqualTo(1);
	}

	@Test
	void importCsvSkipsUnchangedRows() {
		String csv = "depot_code,isin,amount_eur,frequency,day_of_month,active,last_changed\n"
				+ "tr,DE0000000001,10.00,monthly,1,true,2024-02-01\n";
		MockMultipartFile file = new MockMultipartFile("file", "savingPlans.csv", "text/csv", csv.getBytes());

		SavingPlan existing = new SavingPlan();
		existing.setDepotId(1L);
		existing.setIsin("DE0000000001");
		existing.setName(null);
		existing.setAmountEur(new BigDecimal("10.00"));
		existing.setFrequency("monthly");
		existing.setDayOfMonth(1);
		existing.setActive(true);
		existing.setLastChanged(LocalDate.of(2024, 2, 1));

		when(depotRepository.findAll()).thenReturn(List.of(depot));
		when(instrumentRepository.findByIsinIn(List.of("DE0000000001"))).thenReturn(List.of(instrument));
		when(savingPlanRepository.findByDepotIdAndIsin(1L, "DE0000000001")).thenReturn(Optional.of(existing));

		SavingPlanImportResultDto result = savingPlanService.importCsv(file);

		assertThat(result.updated()).isZero();
		assertThat(result.skippedUnchanged()).isEqualTo(1);
	}

	@Test
	void deleteRemovesExisting() {
		when(savingPlanRepository.existsById(5L)).thenReturn(true);

		savingPlanService.delete(5L);

		org.mockito.Mockito.verify(savingPlanRepository).deleteById(5L);
	}

	@Test
	void createRejectsMissingInstrument() {
		when(depotRepository.findById(1L)).thenReturn(Optional.of(depot));
		when(instrumentRepository.findById("DE0000000001")).thenReturn(Optional.empty());

		SavingPlanUpsertRequest request = new SavingPlanUpsertRequest(
				1L, "DE0000000001", null, BigDecimal.valueOf(25), null, null, null, null);

		assertThatThrownBy(() -> savingPlanService.create(request))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ISIN not found");
	}

	@Test
	void importCsvRejectsBlankDepotCode() {
		String csv = "depot_code,isin,amount_eur\n"
				+ ",DE0000000001,25.00\n";
		MockMultipartFile file = new MockMultipartFile("file", "savingPlans.csv", "text/csv", csv.getBytes());

		assertThatThrownBy(() -> savingPlanService.importCsv(file))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("depot_code is required");
	}

	@Test
	void exportCsvEscapesCommaValues() {
		SavingPlanService spy = org.mockito.Mockito.spy(savingPlanService);
		SavingPlanDto row = new SavingPlanDto(1L, 1L, "tr", "Trade Republic", "DE0000000001",
				"A, \"B\"", BigDecimal.valueOf(10), "monthly", 1, true, LocalDate.of(2024, 1, 1), 3);
		org.mockito.Mockito.doReturn(List.of(row)).when(spy).list();

		String csv = spy.exportCsv();

		assertThat(csv).contains("\"A, \"\"B\"\"\"");
	}

	@Test
	void listReturnsRows() {
		var projection = new my.portfoliomanager.app.repository.projection.SavingPlanListProjection() {
			public Long getSavingPlanId() { return 7L; }
			public Long getDepotId() { return 1L; }
			public String getDepotCode() { return "tr"; }
			public String getDepotName() { return "Trade Republic"; }
			public String getIsin() { return "DE0000000001"; }
			public String getName() { return "Sample"; }
			public BigDecimal getAmountEur() { return BigDecimal.valueOf(15); }
			public String getFrequency() { return "monthly"; }
			public Integer getDayOfMonth() { return 1; }
			public Boolean getActive() { return true; }
			public LocalDate getLastChanged() { return LocalDate.of(2024, 1, 1); }
			public Integer getLayer() { return 3; }
		};
		when(savingPlanRepository.findAllWithDetails()).thenReturn(List.of(projection));

		List<SavingPlanDto> rows = savingPlanService.list();

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).isin()).isEqualTo("DE0000000001");
	}
}
