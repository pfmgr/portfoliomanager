package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.LlmConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmConfigRepository extends JpaRepository<LlmConfig, Integer> {
}
