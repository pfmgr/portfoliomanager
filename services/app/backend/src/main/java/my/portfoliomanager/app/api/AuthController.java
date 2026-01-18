package my.portfoliomanager.app.api;

import jakarta.validation.Valid;
import my.portfoliomanager.app.config.AppProperties;
import my.portfoliomanager.app.dto.AuthRequest;
import my.portfoliomanager.app.dto.AuthResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping({"/api/auth", "/auth"})
public class AuthController {
	private static final String LOGOUT_REALM = "Portfolio Admin (Logged out)";
	private final AuthenticationManager authenticationManager;
	private final JwtEncoder jwtEncoder;
	private final AppProperties properties;

	public AuthController(AuthenticationManager authenticationManager, JwtEncoder jwtEncoder, AppProperties properties) {
		this.authenticationManager = authenticationManager;
		this.jwtEncoder = jwtEncoder;
		this.properties = properties;
	}

	@PostMapping("/token")
	public AuthResponse token(@Valid @RequestBody AuthRequest request) {
		try {
			authenticationManager.authenticate(
					new UsernamePasswordAuthenticationToken(request.username(), request.password())
			);
		} catch (AuthenticationException ex) {
			throw new IllegalArgumentException("Invalid credentials", ex);
		}
		Instant now = Instant.now();
		long expiresIn = 3600;
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.jwt().issuer())
				.subject(request.username())
				.issuedAt(now)
				.expiresAt(now.plusSeconds(expiresIn))
				.claim("roles", "ADMIN")
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new AuthResponse(token, "Bearer", expiresIn);
	}

	@RequestMapping(value = "/logout", method = {RequestMethod.GET, RequestMethod.POST})
	public ResponseEntity<Void> logout() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + LOGOUT_REALM + "\"");
		headers.add(HttpHeaders.CACHE_CONTROL, "no-store");
		headers.add(HttpHeaders.PRAGMA, "no-cache");
		return new ResponseEntity<>(headers, HttpStatus.UNAUTHORIZED);
	}
}
