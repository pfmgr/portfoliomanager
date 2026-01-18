package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.KnowledgeBaseRun;
import my.portfoliomanager.app.domain.KnowledgeBaseRunAction;
import my.portfoliomanager.app.domain.KnowledgeBaseRunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRunRepository extends JpaRepository<KnowledgeBaseRun, Long> {
	Optional<KnowledgeBaseRun> findFirstByIsinAndActionOrderByStartedAtDesc(String isin, KnowledgeBaseRunAction action);

	@Query("""
		select r from KnowledgeBaseRun r
		where (:isin is null or r.isin = :isin)
		  and (:status is null or r.status = :status)
		order by r.startedAt desc
		""")
	Page<KnowledgeBaseRun> search(@Param("isin") String isin,
								  @Param("status") KnowledgeBaseRunStatus status,
								  Pageable pageable);

	@Query("""
		select r from KnowledgeBaseRun r
		where r.status = :status and r.startedAt <= :cutoff
		""")
	List<KnowledgeBaseRun> findTimedOut(@Param("status") KnowledgeBaseRunStatus status,
										@Param("cutoff") LocalDateTime cutoff);
}
