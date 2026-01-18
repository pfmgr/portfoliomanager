package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.InstrumentEdit;
import my.portfoliomanager.app.repository.InstrumentEditRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuditService {
	private final InstrumentEditRepository repository;

	public AuditService(InstrumentEditRepository repository) {
		this.repository = repository;
	}

	public void recordEdit(String isin, String field, String oldValue, String newValue, String editedBy, String source) {
		InstrumentEdit edit = new InstrumentEdit();
		edit.setIsin(isin);
		edit.setField(field);
		edit.setOldValue(oldValue);
		edit.setNewValue(newValue);
		edit.setEditedAt(LocalDateTime.now());
		edit.setEditedBy(editedBy);
		edit.setSource(source);
		repository.save(edit);
	}
}
