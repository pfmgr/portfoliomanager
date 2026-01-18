package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.KnowledgeBaseRun;
import my.portfoliomanager.app.domain.KnowledgeBaseRunAction;
import my.portfoliomanager.app.domain.KnowledgeBaseRunStatus;
import my.portfoliomanager.app.repository.KnowledgeBaseRunRepository;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
class KnowledgeBaseRunServiceTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private KnowledgeBaseRunService runService;

	@Autowired
	private KnowledgeBaseRunRepository runRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
	}

	@BeforeEach
	void setup() {
		jdbcTemplate.update("delete from kb_runs");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void markTimedOutRuns_setsFailedTimeout() {
		KnowledgeBaseRun run = new KnowledgeBaseRun();
		run.setIsin("DE0000000001");
		run.setAction(KnowledgeBaseRunAction.REFRESH);
		run.setStatus(KnowledgeBaseRunStatus.IN_PROGRESS);
		run.setStartedAt(LocalDateTime.now().minusMinutes(45));
		run.setAttempts(1);
		KnowledgeBaseRun saved = runRepository.save(run);

		int marked = runService.markTimedOutRuns(Duration.ofMinutes(30));

		KnowledgeBaseRun updated = runRepository.findById(saved.getRunId()).orElseThrow();
		assertThat(marked).isEqualTo(1);
		assertThat(updated.getStatus()).isEqualTo(KnowledgeBaseRunStatus.FAILED_TIMEOUT);
		assertThat(updated.getFinishedAt()).isNotNull();
	}
}
