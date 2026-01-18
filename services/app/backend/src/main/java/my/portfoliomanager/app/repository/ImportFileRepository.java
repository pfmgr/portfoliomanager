package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.ImportFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ImportFileRepository extends JpaRepository<ImportFile, Long> {
	Optional<ImportFile> findByDepotCodeAndFileHash(String depotCode, String fileHash);

	Optional<ImportFile> findByDepotCodeAndFileHashAndStatus(String depotCode, String fileHash, String status);
}
