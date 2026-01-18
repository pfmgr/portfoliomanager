package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.InstrumentEdit;
import my.portfoliomanager.app.repository.InstrumentEditRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {
	@Mock
	private InstrumentEditRepository repository;

	@InjectMocks
	private AuditService auditService;

	@Test
	void recordEditPersistsAuditEntry() {
		auditService.recordEdit("ISIN1", "layer", "5", "2", "tester", "apply");

		ArgumentCaptor<InstrumentEdit> captor = ArgumentCaptor.forClass(InstrumentEdit.class);
		verify(repository).save(captor.capture());
		InstrumentEdit edit = captor.getValue();

		assertThat(edit.getIsin()).isEqualTo("ISIN1");
		assertThat(edit.getField()).isEqualTo("layer");
		assertThat(edit.getOldValue()).isEqualTo("5");
		assertThat(edit.getNewValue()).isEqualTo("2");
		assertThat(edit.getEditedBy()).isEqualTo("tester");
		assertThat(edit.getSource()).isEqualTo("apply");
		assertThat(edit.getEditedAt()).isNotNull();
	}
}
