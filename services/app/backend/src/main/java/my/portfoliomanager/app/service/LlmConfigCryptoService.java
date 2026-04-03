package my.portfoliomanager.app.service;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Service
public class LlmConfigCryptoService {
	private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
	private static final String KDF_ALGO = "PBKDF2WithHmacSHA256";
	private static final int SALT_LENGTH = 16;
	private static final int IV_LENGTH = 12;
	private static final int KEY_LENGTH_BITS = 256;
	private static final int KDF_ITERATIONS = 65_536;

	private final SecureRandom secureRandom = new SecureRandom();
	private final String encryptionPassword;

	public LlmConfigCryptoService(my.portfoliomanager.app.config.AppProperties properties) {
		String configured = properties == null ? null : properties.llmConfigEncryptionPassword();
		this.encryptionPassword = configured == null ? "" : configured;
	}

	public boolean isPasswordSet() {
		return encryptionPassword != null && !encryptionPassword.isBlank();
	}

	public String encrypt(String plaintext) {
		if (plaintext == null || plaintext.isBlank()) {
			return null;
		}
		if (!isPasswordSet()) {
			throw new IllegalStateException("LLM config encryption password is not configured");
		}
		try {
			byte[] salt = new byte[SALT_LENGTH];
			secureRandom.nextBytes(salt);
			byte[] iv = new byte[IV_LENGTH];
			secureRandom.nextBytes(iv);

			Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
			cipher.init(Cipher.ENCRYPT_MODE, deriveKey(salt), new GCMParameterSpec(128, iv));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

			byte[] payload = new byte[salt.length + iv.length + ciphertext.length];
			System.arraycopy(salt, 0, payload, 0, salt.length);
			System.arraycopy(iv, 0, payload, salt.length, iv.length);
			System.arraycopy(ciphertext, 0, payload, salt.length + iv.length, ciphertext.length);
			return Base64.getEncoder().encodeToString(payload);
		} catch (Exception ex) {
			throw new IllegalStateException("Failed to encrypt LLM API key", ex);
		}
	}

	public String decrypt(String encryptedPayload) {
		if (encryptedPayload == null || encryptedPayload.isBlank() || !isPasswordSet()) {
			return null;
		}
		try {
			byte[] payload = Base64.getDecoder().decode(encryptedPayload);
			if (payload.length <= SALT_LENGTH + IV_LENGTH) {
				return null;
			}
			byte[] salt = Arrays.copyOfRange(payload, 0, SALT_LENGTH);
			byte[] iv = Arrays.copyOfRange(payload, SALT_LENGTH, SALT_LENGTH + IV_LENGTH);
			byte[] ciphertext = Arrays.copyOfRange(payload, SALT_LENGTH + IV_LENGTH, payload.length);

			Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
			cipher.init(Cipher.DECRYPT_MODE, deriveKey(salt), new GCMParameterSpec(128, iv));
			byte[] plaintext = cipher.doFinal(ciphertext);
			String value = new String(plaintext, StandardCharsets.UTF_8);
			return value.isBlank() ? null : value;
		} catch (Exception ex) {
			return null;
		}
	}

	private SecretKey deriveKey(byte[] salt) throws Exception {
		PBEKeySpec spec = new PBEKeySpec(encryptionPassword.toCharArray(), salt, KDF_ITERATIONS, KEY_LENGTH_BITS);
		SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGO);
		byte[] encoded = factory.generateSecret(spec).getEncoded();
		return new SecretKeySpec(encoded, "AES");
	}
}
