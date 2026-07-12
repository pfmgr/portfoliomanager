package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.KnowledgeBaseRunStep;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KnowledgeBaseRunStepRepository extends JpaRepository<KnowledgeBaseRunStep, Long> {
	List<KnowledgeBaseRunStep> findByRun_RunIdOrderBySequenceNoAsc(Long runId);
	long countByRun_RunId(Long runId);
}
