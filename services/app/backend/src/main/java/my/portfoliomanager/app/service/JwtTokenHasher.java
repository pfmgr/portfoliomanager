package my.portfoliomanager.app.service;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class JwtTokenHasher {
	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private final SecretKey secretKey;

	public JwtTokenHasher(@Qualifier("jwtJtiHashKey") SecretKey secretKey) {
		this.secretKey = secretKey;
	}

	public String hashJti(String jti) {
		if (jti == null || jti.isBlank()) {
			throw new IllegalArgumentException("JWT jti is required");
		}
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(secretKey);
			byte[] digest = mac.doFinal(jti.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("Unable to hash JWT jti", ex);
		}
	}
}
