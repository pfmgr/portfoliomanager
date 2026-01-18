package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.KnowledgeBaseConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBaseConfigRepository extends JpaRepository<KnowledgeBaseConfig, Integer> {
}
