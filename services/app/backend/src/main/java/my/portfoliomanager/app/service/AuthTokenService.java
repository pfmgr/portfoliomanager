package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.AuthToken;
import my.portfoliomanager.app.repository.AuthTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class AuthTokenService {
	public static final long DEFAULT_EXPIRES_IN_SECONDS = 3600L;
	public static final long DEFAULT_CLEANUP_INTERVAL_SECONDS = 300L;
	public static final int DEFAULT_CLEANUP_BATCH_SIZE = 1000;

	private final AuthTokenRepository repository;
	private final JwtTokenHasher hasher;
	private final Clock clock;

	public AuthTokenService(AuthTokenRepository repository, JwtTokenHasher hasher, Clock clock) {
		this.repository = repository;
		this.hasher = hasher;
		this.clock = clock;
	}

	@Transactional
	public AuthToken storeToken(String jti, String subject, Instant issuedAt, Instant expiresAt) {
		if (jti == null || jti.isBlank()) {
			throw new IllegalArgumentException("JWT jti is required");
		}
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("JWT subject is required");
		}
		Instant now = clock.instant();
		Instant issued = issuedAt == null ? now : issuedAt;
		Instant expires = expiresAt == null ? issued.plusSeconds(DEFAULT_EXPIRES_IN_SECONDS) : expiresAt;
		if (!expires.isAfter(issued)) {
			throw new IllegalArgumentException("JWT expiry must be after issuedAt");
		}
		AuthToken token = new AuthToken();
		token.setJtiHash(hasher.hashJti(jti));
		token.setSubject(subject);
		token.setIssuedAt(issued);
		token.setExpiresAt(expires);
		token.setCreatedAt(now);
		return repository.save(token);
	}

	public boolean isTokenActive(String jti, Instant now) {
		if (jti == null || jti.isBlank() || now == null) {
			return false;
		}
		String hash = hasher.hashJti(jti);
		return repository.existsByJtiHashAndRevokedAtIsNullAndExpiresAtAfter(hash, now);
	}

	@Transactional
	public boolean revokeToken(String jti) {
		if (jti == null || jti.isBlank()) {
			return false;
		}
		String hash = hasher.hashJti(jti);
		Instant now = clock.instant();
		return repository.revokeByJtiHash(hash, now) > 0;
	}

	@Transactional
	public int cleanupExpired(Instant cutoff, int batchSize) {
		if (cutoff == null || batchSize <= 0) {
			return 0;
		}
		return repository.deleteExpiredBatch(cutoff, batchSize);
	}

	public Instant now() {
		return clock.instant();
	}
}
