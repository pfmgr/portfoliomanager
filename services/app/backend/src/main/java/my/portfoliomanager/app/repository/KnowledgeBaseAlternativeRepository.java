package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.KnowledgeBaseAlternative;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseAlternativeRepository extends JpaRepository<KnowledgeBaseAlternative, Long> {
	List<KnowledgeBaseAlternative> findByBaseIsinOrderByCreatedAtDesc(String baseIsin);

	Optional<KnowledgeBaseAlternative> findByBaseIsinAndAltIsin(String baseIsin, String altIsin);
}
