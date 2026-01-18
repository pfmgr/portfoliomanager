package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.InstrumentEdit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentEditRepository extends JpaRepository<InstrumentEdit, Long> {
}
