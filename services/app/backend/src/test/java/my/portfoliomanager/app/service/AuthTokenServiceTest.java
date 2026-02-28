package my.portfoliomanager.app.service;

import my.portfoliomanager.app.repository.AuthTokenRepository;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
class AuthTokenServiceTest {
	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private AuthTokenService authTokenService;

	@Autowired
	private AuthTokenRepository authTokenRepository;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@Test
	void cleanupExpiredRemovesOnlyExpiredTokens() {
		Instant now = Instant.now();
		authTokenService.storeToken(UUID.randomUUID().toString(), "admin", now.minusSeconds(7200), now.minusSeconds(3600));
		authTokenService.storeToken(UUID.randomUUID().toString(), "admin", now, now.plusSeconds(3600));

		int deleted = authTokenService.cleanupExpired(now, 100);

		assertThat(deleted).isEqualTo(1);
		assertThat(authTokenRepository.count()).isEqualTo(1);
	}

	@Test
	void revokeTokenMarksTokenInactive() {
		Instant now = Instant.now();
		String jti = UUID.randomUUID().toString();
		authTokenService.storeToken(jti, "admin", now, now.plusSeconds(600));

		assertThat(authTokenService.isTokenActive(jti, now)).isTrue();

		authTokenService.revokeToken(jti);

		assertThat(authTokenService.isTokenActive(jti, Instant.now())).isFalse();
	}

	@Test
	void storeTokenRejectsBlankJti() {
		Instant now = Instant.now();
		assertThatThrownBy(() -> authTokenService.storeToken(" ", "admin", now, now.plusSeconds(60)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void storeTokenRejectsBlankSubject() {
		Instant now = Instant.now();
		assertThatThrownBy(() -> authTokenService.storeToken(UUID.randomUUID().toString(), " ", now, now.plusSeconds(60)))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
