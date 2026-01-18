package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.Snapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface SnapshotRepository extends JpaRepository<Snapshot, Long> {
	Optional<Snapshot> findByDepotIdAndAsOfDateAndSourceAndFileHash(Long depotId, LocalDate asOfDate,
																	String source, String fileHash);
}
