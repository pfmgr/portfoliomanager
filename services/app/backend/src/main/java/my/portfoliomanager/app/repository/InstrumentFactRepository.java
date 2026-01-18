package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.InstrumentFact;
import my.portfoliomanager.app.domain.InstrumentFactId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentFactRepository extends JpaRepository<InstrumentFact, InstrumentFactId> {
}
