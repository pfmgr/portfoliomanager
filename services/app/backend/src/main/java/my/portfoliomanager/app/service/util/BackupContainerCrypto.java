package my.portfoliomanager.app.service.util;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

public final class BackupContainerCrypto {

	private static final byte[] MAGIC = new byte[] {'P', 'M', 'B', 'K', '1'};
	private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
	private static final String KDF_ALGO = "PBKDF2WithHmacSHA256";
	private static final int SALT_LENGTH = 16;
	private static final int IV_LENGTH = 12;
	private static final int KEY_LENGTH_BITS = 256;
	private static final int KDF_ITERATIONS = 65_536;
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private BackupContainerCrypto() {
	}

	public static boolean isEncrypted(byte[] payload) {
		if (payload == null || payload.length < MAGIC.length) {
			return false;
		}
		for (int i = 0; i < MAGIC.length; i++) {
			if (payload[i] != MAGIC[i]) {
				return false;
			}
		}
		return true;
	}

	public static int headerLength() {
		return MAGIC.length;
	}

	public static byte[] encrypt(byte[] plaintext, String password) {
		requirePassword(password);
		if (plaintext == null) {
			throw new IllegalArgumentException("Plaintext is required.");
		}
		try {
			byte[] salt = new byte[SALT_LENGTH];
			SECURE_RANDOM.nextBytes(salt);
			byte[] iv = new byte[IV_LENGTH];
			SECURE_RANDOM.nextBytes(iv);

			Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
			cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, iv));
			byte[] ciphertext = cipher.doFinal(plaintext);

			byte[] payload = new byte[MAGIC.length + salt.length + iv.length + ciphertext.length];
			int offset = 0;
			System.arraycopy(MAGIC, 0, payload, offset, MAGIC.length);
			offset += MAGIC.length;
			System.arraycopy(salt, 0, payload, offset, salt.length);
			offset += salt.length;
			System.arraycopy(iv, 0, payload, offset, iv.length);
			offset += iv.length;
			System.arraycopy(ciphertext, 0, payload, offset, ciphertext.length);
			return payload;
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Failed to encrypt backup container.", ex);
		}
	}

	public static byte[] decrypt(byte[] payload, String password) {
		requirePassword(password);
		if (!isEncrypted(payload) || payload.length <= MAGIC.length + SALT_LENGTH + IV_LENGTH) {
			throw new IllegalArgumentException("Unable to decrypt backup container.");
		}
		try {
			int offset = MAGIC.length;
			byte[] salt = Arrays.copyOfRange(payload, offset, offset + SALT_LENGTH);
			offset += SALT_LENGTH;
			byte[] iv = Arrays.copyOfRange(payload, offset, offset + IV_LENGTH);
			offset += IV_LENGTH;
			byte[] ciphertext = Arrays.copyOfRange(payload, offset, payload.length);

			Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
			cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, iv));
			return cipher.doFinal(ciphertext);
		} catch (GeneralSecurityException ex) {
			throw new IllegalArgumentException("Unable to decrypt backup container.", ex);
		}
	}

	public static InputStream decrypt(InputStream payload, String password) throws IOException {
		requirePassword(password);
		try {
			byte[] salt = payload.readNBytes(SALT_LENGTH);
			byte[] iv = payload.readNBytes(IV_LENGTH);
			if (salt.length != SALT_LENGTH || iv.length != IV_LENGTH) {
				throw new IllegalArgumentException("Unable to decrypt backup container.");
			}
			Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
			cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, iv));
			return new CipherInputStream(payload, cipher);
		} catch (IllegalArgumentException ex) {
			throw ex;
		} catch (IOException | GeneralSecurityException ex) {
			throw new IllegalArgumentException("Unable to decrypt backup container.", ex);
		}
	}

	private static void requirePassword(String password) {
		if (password == null || password.isBlank()) {
			throw new IllegalStateException("Backup password is required.");
		}
	}

	private static SecretKey deriveKey(String password, byte[] salt) throws GeneralSecurityException {
		PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, KDF_ITERATIONS, KEY_LENGTH_BITS);
		SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGO);
		byte[] encoded = factory.generateSecret(spec).getEncoded();
		return new SecretKeySpec(encoded, "AES");
	}
}
