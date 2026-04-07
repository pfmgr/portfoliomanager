package my.portfoliomanager.app.api;

import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
class BackupApiIntegrationTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	private MockMvc mockMvc;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
		registry.add("app.llm-config-encryption-password", () -> "");
	}

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void backupEndpointsAreAdminOnly() throws Exception {
		mockMvc.perform(get("/api/backups/export"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/api/backups/export").with(userJwt()))
				.andExpect(status().isForbidden());

		mockMvc.perform(get("/api/backups/export").with(adminJwt()))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate, private"))
				.andExpect(header().string("Pragma", "no-cache"))
				.andExpect(header().string("Expires", "0"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Content-Disposition", "attachment; filename=backup.zip"));

		mockMvc.perform(post("/api/backups/export"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/api/backups/export").with(userJwt()))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/api/backups/export")
					.contentType(APPLICATION_JSON)
					.content("{\"password\":\"backup-test-password\"}")
					.with(adminJwt()))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate, private"))
				.andExpect(header().string("Pragma", "no-cache"))
				.andExpect(header().string("Expires", "0"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"));

		mockMvc.perform(multipart("/api/backups/import")
						.file(new MockMultipartFile("file", "backup.zip", "application/zip", new byte[0]))
						.with(userJwt()))
				.andExpect(status().isForbidden());
	}

	private RequestPostProcessor adminJwt() {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private RequestPostProcessor userJwt() {
		return jwt();
	}
}
