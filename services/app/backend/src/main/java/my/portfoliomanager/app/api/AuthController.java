package my.portfoliomanager.app.api;

import jakarta.validation.Valid;
import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.dto.AuthRequest;
import my.portfoliomanager.app.dto.AuthResponse;
import my.portfoliomanager.app.service.AuthTokenService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.util.stream.Collectors;

@RestController
@RequestMapping({"/api/auth", "/auth"})
public class AuthController {
	private final AuthenticationManager authenticationManager;
	private final JwtEncoder jwtEncoder;
	private final AuthTokenService authTokenService;
	private final AppProperties properties;
	private final Clock clock;

	public AuthController(AuthenticationManager authenticationManager,
						 JwtEncoder jwtEncoder,
						 AuthTokenService authTokenService,
						 AppProperties properties,
						 Clock clock) {
		this.authenticationManager = authenticationManager;
		this.jwtEncoder = jwtEncoder;
		this.authTokenService = authTokenService;
		this.properties = properties;
		this.clock = clock;
	}

	@PostMapping("/token")
	public AuthResponse token(@Valid @RequestBody AuthRequest request) {
		Authentication authentication;
		try {
			authentication = authenticationManager.authenticate(
					new UsernamePasswordAuthenticationToken(request.username(), request.password())
			);
		} catch (AuthenticationException ex) {
			throw new IllegalArgumentException("Invalid credentials", ex);
		}
		String roles = authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.filter(authority -> authority.startsWith("ROLE_"))
				.map(authority -> authority.substring("ROLE_".length()))
				.collect(Collectors.joining(" "));
		Instant now = clock.instant();
		long expiresIn = resolveExpiresInSeconds();
		String jti = UUID.randomUUID().toString();
		JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
				.issuer(properties.jwt().issuer())
				.subject(request.username())
				.id(jti)
				.issuedAt(now)
				.expiresAt(now.plusSeconds(expiresIn));
		if (!roles.isBlank()) {
			claimsBuilder.claim("roles", roles);
		}
		JwtClaimsSet claims = claimsBuilder.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		authTokenService.storeToken(jti, request.username(), now, now.plusSeconds(expiresIn));
		return new AuthResponse(token, "Bearer", expiresIn);
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
		if (jwt == null || jwt.getId() == null || jwt.getId().isBlank()) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		authTokenService.revokeToken(jwt.getId());
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/health")
	public ResponseEntity<Map<String, String>> health() {
		return ResponseEntity.ok(Map.of("status", "ok"));
	}

	private long resolveExpiresInSeconds() {
		Long configured = properties.jwt().expiresInSeconds();
		if (configured == null || configured <= 0) {
			return AuthTokenService.DEFAULT_EXPIRES_IN_SECONDS;
		}
		return configured;
	}

}
