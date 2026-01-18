package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.KnowledgeBaseExtraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KnowledgeBaseExtractionRepository extends JpaRepository<KnowledgeBaseExtraction, String> {
	@Modifying
	@Query("delete from KnowledgeBaseExtraction k where k.isin in :isins")
	int deleteByIsinIn(@Param("isins") List<String> isins);
}
