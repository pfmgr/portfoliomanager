package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.Instrument;
import my.portfoliomanager.app.domain.InstrumentOverride;
import my.portfoliomanager.app.dto.InstrumentOverrideRequest;
import my.portfoliomanager.app.dto.OverridesImportResultDto;
import my.portfoliomanager.app.repository.InstrumentRepository;
import my.portfoliomanager.app.repository.InstrumentOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverridesServiceTest {
	@Mock
	private InstrumentRepository instrumentRepository;

	@Mock
	private InstrumentOverrideRepository overrideRepository;

	@Mock
	private AuditService auditService;

	@InjectMocks
	private OverridesService overridesService;

	private Instrument instrument;

	@BeforeEach
	void setup() {
		instrument = new Instrument();
		instrument.setIsin("DE0000000001");
		instrument.setName("Old Name");
		instrument.setInstrumentType("ETF");
	}

	@Test
	void importCsvUpdatesInstrumentAndAudits() {
		String csv = "isin,name,instrument_type,asset_class,sub_class,layer,layer_last_changed,layer_notes\n"
				+ "DE0000000001,New Name,Stock,Equity,,2,2024-01-01,Note\n";
		MockMultipartFile file = new MockMultipartFile("file", "overrides.csv", "text/csv", csv.getBytes());

		when(instrumentRepository.findAll()).thenReturn(List.of(instrument));
		when(overrideRepository.findById("DE0000000001")).thenReturn(Optional.empty());
		when(overrideRepository.save(any(InstrumentOverride.class))).thenAnswer(invocation -> invocation.getArgument(0));

		OverridesImportResultDto result = overridesService.importCsv(file, "tester");

		assertThat(result.imported()).isEqualTo(1);
		ArgumentCaptor<String> fieldCaptor = ArgumentCaptor.forClass(String.class);
		verify(auditService, org.mockito.Mockito.atLeastOnce())
				.recordEdit(org.mockito.Mockito.eq("DE0000000001"), fieldCaptor.capture(),
						org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.eq("tester"),
						org.mockito.Mockito.eq("override_import"));
		assertThat(fieldCaptor.getAllValues()).contains("name");
	}

	@Test
	void importCsvRejectsMissingHeader() {
		String csv = "name,layer\n"
				+ "Example,2\n";
		MockMultipartFile file = new MockMultipartFile("file", "overrides.csv", "text/csv", csv.getBytes());

		assertThatThrownBy(() -> overridesService.importCsv(file, "tester"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("header must include 'isin'");
	}

	@Test
	void importCsvRejectsInvalidLayer() {
		String csv = "isin,layer\n"
				+ "DE0000000001,7\n";
		MockMultipartFile file = new MockMultipartFile("file", "overrides.csv", "text/csv", csv.getBytes());

		when(instrumentRepository.findAll()).thenReturn(List.of(instrument));

		assertThatThrownBy(() -> overridesService.importCsv(file, "tester"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("layer must be between");
	}

	@Test
	void importCsvSkipsEmptyRow() {
		String csv = "isin,name\n"
				+ "DE0000000001,\n";
		MockMultipartFile file = new MockMultipartFile("file", "overrides.csv", "text/csv", csv.getBytes());

		when(instrumentRepository.findAll()).thenReturn(List.of(instrument));

		OverridesImportResultDto result = overridesService.importCsv(file, "tester");

		assertThat(result.imported()).isZero();
		assertThat(result.skippedEmpty()).isEqualTo(1);
	}

	@Test
	void importCsvSkipsMissingIsin() {
		String csv = "isin,name\n"
				+ "DE0000000002,Name\n";
		MockMultipartFile file = new MockMultipartFile("file", "overrides.csv", "text/csv", csv.getBytes());

		when(instrumentRepository.findAll()).thenReturn(List.of(instrument));

		OverridesImportResultDto result = overridesService.importCsv(file, "tester");

		assertThat(result.imported()).isZero();
		assertThat(result.skippedMissing()).isEqualTo(1);
	}

	@Test
	void importCsvIgnoresUnchangedValues() {
		String csv = "isin,name\n"
				+ "DE0000000001,Old Name\n";
		MockMultipartFile file = new MockMultipartFile("file", "overrides.csv", "text/csv", csv.getBytes());

		InstrumentOverride existing = new InstrumentOverride();
		existing.setIsin("DE0000000001");
		existing.setName("Old Name");

		when(instrumentRepository.findAll()).thenReturn(List.of(instrument));
		when(overrideRepository.findById("DE0000000001")).thenReturn(Optional.of(existing));
		when(overrideRepository.save(any(InstrumentOverride.class))).thenAnswer(invocation -> invocation.getArgument(0));

		OverridesImportResultDto result = overridesService.importCsv(file, "tester");

		assertThat(result.imported()).isEqualTo(1);
		verify(auditService, org.mockito.Mockito.never())
				.recordEdit(any(), any(), any(), any(), any(), any());
	}

	@Test
	void importCsvRejectsInvalidDate() {
		String csv = "isin,layer_last_changed\n"
				+ "DE0000000001,2024-13-01\n";
		MockMultipartFile file = new MockMultipartFile("file", "overrides.csv", "text/csv", csv.getBytes());

		when(instrumentRepository.findAll()).thenReturn(List.of(instrument));

		assertThatThrownBy(() -> overridesService.importCsv(file, "tester"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("layer_last_changed");
	}

	@Test
	void importCsvRejectsInvalidIsin() {
		String csv = "isin,name\n"
				+ "INVALID,Name\n";
		MockMultipartFile file = new MockMultipartFile("file", "overrides.csv", "text/csv", csv.getBytes());

		when(instrumentRepository.findAll()).thenReturn(List.of(instrument));

		assertThatThrownBy(() -> overridesService.importCsv(file, "tester"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Invalid ISIN");
	}

	@Test
	void importCsvFallsBackToLatin1WhenUtf8Invalid() {
		String csv = "isin,name\nDE0000000001,Ä\n";
		byte[] payload = csv.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		MockMultipartFile file = new MockMultipartFile("file", "overrides.csv", "text/csv", payload);

		when(instrumentRepository.findAll()).thenReturn(List.of(instrument));
		when(overrideRepository.findById("DE0000000001")).thenReturn(Optional.empty());
		when(overrideRepository.save(any(InstrumentOverride.class))).thenAnswer(invocation -> invocation.getArgument(0));

		OverridesImportResultDto result = overridesService.importCsv(file, "tester");

		assertThat(result.imported()).isEqualTo(1);
		verify(auditService).recordEdit(eq("DE0000000001"), eq("name"), eq(null), eq("Ä"), eq("tester"),
				eq("override_import"));
	}

	@Test
	void exportCsvEscapesCommasAndQuotes() {
		InstrumentOverride override = new InstrumentOverride();
		override.setIsin("DE0000000001");
		override.setName("A, \"B\"");

		when(overrideRepository.findAll()).thenReturn(List.of(override));

		String csv = overridesService.exportCsv();

		assertThat(csv).contains("\"A, \"\"B\"\"\"");
	}

	@Test
	void importCsvRejectsEmptyFile() {
		MockMultipartFile file = new MockMultipartFile("file", "overrides.csv", "text/csv", new byte[0]);

		assertThatThrownBy(() -> overridesService.importCsv(file, "tester"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("File is empty");
	}

	@Test
	void upsertOverrideCreatesEntryAndAudits() {
		InstrumentOverrideRequest request = new InstrumentOverrideRequest(
				"New Name", "Stock", "Equity", "Global", 2, null, "Manual override"
		);
		when(instrumentRepository.findById("DE0000000001")).thenReturn(Optional.of(instrument));
		when(overrideRepository.findById("DE0000000001")).thenReturn(Optional.empty());
		when(overrideRepository.save(any(InstrumentOverride.class))).thenAnswer(invocation -> invocation.getArgument(0));

		overridesService.upsertOverride("DE0000000001", request, "tester");

		verify(overrideRepository).save(any(InstrumentOverride.class));
		verify(auditService).recordEdit(eq("DE0000000001"), eq("name"), eq(null), eq("New Name"), eq("tester"),
				eq("override_ui"));
	}

	@Test
	void deleteOverrideRecordsAudit() {
		InstrumentOverride override = new InstrumentOverride();
		override.setIsin("DE0000000001");
		override.setName("Name");
		override.setInstrumentType("ETF");
		override.setLayer(3);

		when(overrideRepository.findById("DE0000000001")).thenReturn(Optional.of(override));

		overridesService.deleteOverride("DE0000000001", "tester");

		verify(overrideRepository).deleteById("DE0000000001");
		verify(auditService, org.mockito.Mockito.atLeastOnce())
				.recordEdit(eq("DE0000000001"), anyString(), any(), any(), eq("tester"), eq("override_delete"));
	}

	@Test
	void updateFieldIgnoresNulls() {
		Consumer<String> updater = value -> {
			throw new IllegalStateException("should not be called");
		};

		ReflectionTestUtils.invokeMethod(overridesService, "updateField",
				null, null, "name", updater, "DE0000000001", "tester", "override_test");

		verify(auditService, never()).recordEdit(any(), any(), any(), any(), any(), any());
	}

	@Test
	void trimHelpersHandleNulls() {
		String trimmed = ReflectionTestUtils.invokeMethod(overridesService, "trim", (String) null);
		String trimmedOrNull = ReflectionTestUtils.invokeMethod(overridesService, "trimOrNull", "   ");

		assertThat(trimmed).isEqualTo("");
		assertThat(trimmedOrNull).isNull();
	}

	@Test
	void recordDeleteSkipsBlankValues() {
		ReflectionTestUtils.invokeMethod(overridesService, "recordDelete", " ", "name", "DE0000000001", "tester");

		verify(auditService, never()).recordEdit(any(), any(), any(), any(), any(), any());
	}
}
