package my.portfoliomanager.app.security;

import my.portfoliomanager.app.service.AuthTokenService;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Instant;

public class JwtDatabaseValidator implements OAuth2TokenValidator<Jwt> {
	private static final String INVALID_TOKEN_CODE = "invalid_token";
	private static final OAuth2Error MISSING_JTI = new OAuth2Error(INVALID_TOKEN_CODE, "Token is missing jti", null);
	private static final OAuth2Error EXPIRED = new OAuth2Error(INVALID_TOKEN_CODE, "Token has expired", null);
	private static final OAuth2Error NOT_ACTIVE = new OAuth2Error(INVALID_TOKEN_CODE, "Token is not active", null);
	private static final OAuth2Error VALIDATION_FAILED = new OAuth2Error(INVALID_TOKEN_CODE, "Token validation failed", null);

	private final AuthTokenService tokenService;
	private final Clock clock;

	public JwtDatabaseValidator(AuthTokenService tokenService, Clock clock) {
		this.tokenService = tokenService;
		this.clock = clock;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt token) {
		if (token == null) {
			return OAuth2TokenValidatorResult.failure(VALIDATION_FAILED);
		}
		Instant now = clock.instant();
		Instant expiresAt = token.getExpiresAt();
		if (expiresAt != null && !expiresAt.isAfter(now)) {
			return OAuth2TokenValidatorResult.failure(EXPIRED);
		}
		String jti = token.getId();
		if (jti == null || jti.isBlank()) {
			return OAuth2TokenValidatorResult.failure(MISSING_JTI);
		}
		try {
			boolean active = tokenService.isTokenActive(jti, now);
			return active ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(NOT_ACTIVE);
		} catch (Exception ex) {
			return OAuth2TokenValidatorResult.failure(VALIDATION_FAILED);
		}
	}
}
