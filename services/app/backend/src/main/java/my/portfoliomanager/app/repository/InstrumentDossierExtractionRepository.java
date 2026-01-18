package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.InstrumentDossierExtraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InstrumentDossierExtractionRepository extends JpaRepository<InstrumentDossierExtraction, Long> {
	List<InstrumentDossierExtraction> findByDossierIdOrderByCreatedAtDesc(Long dossierId);

	@Modifying
	@Query("delete from InstrumentDossierExtraction e where e.dossierId in :dossierIds")
	int deleteByDossierIdIn(@Param("dossierIds") List<Long> dossierIds);
}
