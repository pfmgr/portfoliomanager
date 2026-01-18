package my.portfoliomanager.app.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import my.portfoliomanager.app.domain.DossierExtractionStatus;
import my.portfoliomanager.app.domain.DossierOrigin;
import my.portfoliomanager.app.domain.DossierStatus;
import my.portfoliomanager.app.domain.InstrumentDossierExtraction;
import my.portfoliomanager.app.domain.KnowledgeBaseExtraction;
import my.portfoliomanager.app.domain.KnowledgeBaseExtractionStatus;
import my.portfoliomanager.app.dto.InstrumentDossierCreateRequest;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmOutputException;
import my.portfoliomanager.app.repository.InstrumentDossierExtractionRepository;
import my.portfoliomanager.app.repository.KnowledgeBaseExtractionRepository;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
@Import(KnowledgeBaseExtractionInvalidOutputTest.TestConfig.class)
class KnowledgeBaseExtractionInvalidOutputTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private KnowledgeBaseService knowledgeBaseService;

	@Autowired
	private KnowledgeBaseExtractionRepository kbExtractionRepository;

	@Autowired
	private InstrumentDossierExtractionRepository extractionRepository;

	@Autowired
	private ObjectMapper objectMapper;

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
		registry.add("app.kb.enabled", () -> "true");
		registry.add("app.kb.llm-enabled", () -> "false");
	}

	@BeforeEach
	void setup() {
		jdbcTemplate.update("delete from instrument_dossier_extractions");
		jdbcTemplate.update("delete from instrument_dossiers");
		jdbcTemplate.update("delete from knowledge_base_extractions");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void invalidExtraction_doesNotOverwriteApprovedExtraction() throws Exception {
		InstrumentDossierCreateRequest request = new InstrumentDossierCreateRequest(
				"DE0000000001",
				null,
				"Name: Seed",
				DossierOrigin.USER,
				DossierStatus.DRAFT,
				objectMapper.createArrayNode()
		);
		var dossier = knowledgeBaseService.createDossier(request, "tester");

		InstrumentDossierExtraction extraction = new InstrumentDossierExtraction();
		extraction.setDossierId(dossier.dossierId());
		extraction.setModel("seed");
		JsonNode extracted = objectMapper.createObjectNode().put("isin", "DE0000000001");
		extraction.setExtractedJson(extracted);
		extraction.setMissingFieldsJson(objectMapper.createArrayNode());
		extraction.setWarningsJson(objectMapper.createArrayNode());
		extraction.setStatus(DossierExtractionStatus.PENDING_REVIEW);
		extraction.setCreatedAt(LocalDateTime.now());
		extractionRepository.save(extraction);

		knowledgeBaseService.approveExtraction(extraction.getExtractionId(), "tester");
		KnowledgeBaseExtraction seeded = kbExtractionRepository.findById("DE0000000001").orElseThrow();
		assertThat(seeded.getStatus()).isEqualTo(KnowledgeBaseExtractionStatus.COMPLETE);

		var failed = knowledgeBaseService.runExtraction(dossier.dossierId());
		assertThat(failed.status()).isEqualTo(DossierExtractionStatus.FAILED);
		assertThat(failed.error()).isEqualTo("invalid_output");

		KnowledgeBaseExtraction after = kbExtractionRepository.findById("DE0000000001").orElseThrow();
		assertThat(after.getStatus()).isEqualTo(KnowledgeBaseExtractionStatus.COMPLETE);
		assertThat(after.getExtractedJson()).isEqualTo(seeded.getExtractedJson());
	}

	@Configuration
	static class TestConfig {
		@Bean
		@org.springframework.context.annotation.Primary
		ExtractorService extractorService() {
			return dossier -> {
				throw new KnowledgeBaseLlmOutputException("invalid", "invalid_output");
			};
		}
	}
}
