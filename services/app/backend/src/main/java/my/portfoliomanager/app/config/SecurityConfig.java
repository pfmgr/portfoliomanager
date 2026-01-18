package my.portfoliomanager.app.config;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

@Configuration
public class SecurityConfig {
	private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);
	private static final int MIN_SECRET_BYTES = 32;
	private final AppProperties properties;

	public SecurityConfig(AppProperties properties) {
		this.properties = properties;
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
		var user = User.withUsername(properties.security().adminUser())
				.password(passwordEncoder.encode(properties.security().adminPass()))
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
		byte[] secret = resolveJwtSecret();
		return new SecretKeySpec(secret, "HmacSHA256");
	}

	@Bean
	public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
		byte[] secret = jwtSecretKey.getEncoded();
		OctetSequenceKey key = new OctetSequenceKey.Builder(secret)
				.algorithm(JWSAlgorithm.HS256)
				.keyID("app-jwt")
				.build();
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
	}

	@Bean
	public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
		var decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()));
		return decoder;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.csrf(csrf -> csrf.disable())
			.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/auth/**", "/auth/**").permitAll()
				.requestMatchers("/", "/index.html", "/assets/**").permitAll()
				.requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/api/**").authenticated()
				.anyRequest().denyAll()
			)
			.httpBasic(Customizer.withDefaults())
			.oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));

		return http.build();
	}

	private byte[] resolveJwtSecret() {
		String configured = properties.jwt().secret();
		if (configured == null || configured.isBlank()) {
			logger.warn("JWT secret not configured. Generating a runtime secret.");
			return generateSecret();
		}
		byte[] secret = configured.getBytes(StandardCharsets.UTF_8);
		if (secret.length < MIN_SECRET_BYTES) {
			logger.warn("Configured JWT secret is too short. Generating a runtime secret.");
			return generateSecret();
		}
		return secret;
	}

	private byte[] generateSecret() {
		byte[] secret = new byte[MIN_SECRET_BYTES];
		new SecureRandom().nextBytes(secret);
		return secret;
	}
}
