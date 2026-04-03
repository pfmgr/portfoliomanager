package my.portfoliomanager.app.config;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import my.portfoliomanager.app.security.JwtDatabaseValidator;
import my.portfoliomanager.app.service.AuthTokenService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

@Configuration
public class SecurityConfig {
	private static final int MIN_SECRET_BYTES = 32;
	private final AppProperties properties;

	public SecurityConfig(AppProperties properties) {
		this.properties = properties;
	}

	@Bean
	public Clock systemClock() {
		return Clock.systemUTC();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
		String adminUser = properties.security().adminUser();
		String adminPass = properties.security().adminPass();
		if (adminPass == null || adminPass.isBlank()) {
			throw new IllegalStateException("app.security.admin-pass must be set and non-empty");
		}
		var user = User.withUsername(adminUser)
				.password(passwordEncoder.encode(adminPass))
				.roles("ADMIN")
				.build();
		return new InMemoryUserDetailsManager(user);
	}

	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
		return configuration.getAuthenticationManager();
	}

	@Bean
	public SecretKey jwtSecretKey() {
		byte[] secret = resolveJwtSecret(properties.jwt().secret(), "app.jwt.secret");
		return new SecretKeySpec(secret, "HmacSHA256");
	}

	@Bean
	public SecretKey jwtJtiHashKey() {
		String signingSecret = properties.jwt().secret();
		String hashSecret = properties.jwt().jtiHashSecret();
		if (signingSecret != null && hashSecret != null && signingSecret.equals(hashSecret)) {
			throw new IllegalStateException("app.jwt.jti-hash-secret must differ from app.jwt.secret");
		}
		byte[] secret = resolveJwtSecret(hashSecret, "app.jwt.jti-hash-secret");
		return new SecretKeySpec(secret, "HmacSHA256");
	}

	@Bean
	public JwtEncoder jwtEncoder(@Qualifier("jwtSecretKey") SecretKey jwtSecretKey) {
		byte[] secret = jwtSecretKey.getEncoded();
		OctetSequenceKey key = new OctetSequenceKey.Builder(secret)
				.algorithm(JWSAlgorithm.HS256)
				.keyID("app-jwt")
				.build();
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
	}

	@Bean
	public JwtDecoder jwtDecoder(@Qualifier("jwtSecretKey") SecretKey jwtSecretKey, AuthTokenService tokenService, Clock clock) {
		var decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).build();
		var baseValidator = JwtValidators.createDefaultWithIssuer(properties.jwt().issuer());
		var dbValidator = new JwtDatabaseValidator(tokenService, clock);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(baseValidator, dbValidator));
		return decoder;
	}

	@Bean
	public JwtDecoder logoutJwtDecoder(@Qualifier("jwtSecretKey") SecretKey jwtSecretKey) {
		var decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()));
		return decoder;
	}

	@Bean
	@Order(1)
	@SuppressWarnings("java:S4502") // Acceptable: stateless JWT API, no server-side session, CSRF not applicable for token-authenticated requests.
	public SecurityFilterChain logoutSecurityFilterChain(HttpSecurity http,
									 JwtAuthenticationConverter jwtAuthenticationConverter,
									 @Qualifier("logoutJwtDecoder") JwtDecoder logoutJwtDecoder) throws Exception {
		http
			.securityMatcher("/auth/logout", "/api/auth/logout")
			.csrf(csrf -> csrf.disable())
			.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.anyRequest().authenticated()
			)
			.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
					.decoder(logoutJwtDecoder)
					.jwtAuthenticationConverter(jwtAuthenticationConverter)));

		return http.build();
	}

	@Bean
	@Order(2)
	@SuppressWarnings("java:S4502") // Acceptable: stateless JWT API, no server-side session, CSRF not applicable for token-authenticated requests.
	public SecurityFilterChain securityFilterChain(HttpSecurity http,
									 JwtAuthenticationConverter jwtAuthenticationConverter,
									 @Qualifier("jwtDecoder") JwtDecoder jwtDecoder) throws Exception {
		http
			.securityMatcher("/**")
			.csrf(csrf -> csrf.disable())
			.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/auth/token", "/auth/token", "/api/auth/health", "/auth/health").permitAll()
				.requestMatchers("/", "/index.html", "/assets/**").permitAll()
				.requestMatchers("/api/backups/**").hasRole("ADMIN")
				.requestMatchers("/api/llm/**").hasRole("ADMIN")
				.requestMatchers("/api/kb/**").hasRole("ADMIN")
				.requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/api/**").authenticated()
				.anyRequest().denyAll()
			)
			.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
					.decoder(jwtDecoder)
					.jwtAuthenticationConverter(jwtAuthenticationConverter)));

		return http.build();
	}

	@Bean
	public JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(jwt -> {
			List<GrantedAuthority> authorities = new ArrayList<>();
			Object roles = jwt.getClaim("roles");
			addRoles(authorities, roles);
			Object scope = jwt.getClaim("scope");
			addScopes(authorities, scope);
			return authorities;
		});
		return converter;
	}

	private void addRoles(List<GrantedAuthority> authorities, Object rolesClaim) {
		if (rolesClaim == null) {
			return;
		}
		if (rolesClaim instanceof String rolesString) {
			for (String role : rolesString.split("[,\\s]+")) {
				addRole(authorities, role);
			}
			return;
		}
		if (rolesClaim instanceof Collection<?> rolesCollection) {
			for (Object role : rolesCollection) {
				addRole(authorities, role == null ? null : role.toString());
			}
		}
	}

	private void addRole(List<GrantedAuthority> authorities, String role) {
		if (role == null || role.isBlank()) {
			return;
		}
		String normalized = role.trim().toUpperCase(Locale.ROOT);
		String prefixed = normalized.startsWith("ROLE_") ? normalized : "ROLE_" + normalized;
		authorities.add(new SimpleGrantedAuthority(prefixed));
	}

	private void addScopes(List<GrantedAuthority> authorities, Object scopeClaim) {
		if (scopeClaim == null) {
			return;
		}
		if (scopeClaim instanceof String scopeString) {
			for (String scope : scopeString.split("\\s+")) {
				addScope(authorities, scope);
			}
			return;
		}
		if (scopeClaim instanceof Collection<?> scopeCollection) {
			for (Object scope : scopeCollection) {
				addScope(authorities, scope == null ? null : scope.toString());
			}
		}
	}

	private void addScope(List<GrantedAuthority> authorities, String scope) {
		if (scope == null || scope.isBlank()) {
			return;
		}
		String normalized = scope.trim();
		authorities.add(new SimpleGrantedAuthority("SCOPE_" + normalized));
	}

	private byte[] resolveJwtSecret(String configured, String propertyName) {
		if (configured == null || configured.isBlank()) {
			throw new IllegalStateException(propertyName + " must be configured");
		}
		byte[] secret = configured.getBytes(StandardCharsets.UTF_8);
		if (secret.length < MIN_SECRET_BYTES) {
			throw new IllegalStateException(propertyName + " must be at least " + MIN_SECRET_BYTES + " bytes");
		}
		return secret;
	}
}
