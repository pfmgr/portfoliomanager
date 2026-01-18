package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.InstrumentOverride;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentOverrideRepository extends JpaRepository<InstrumentOverride, String> {
}
