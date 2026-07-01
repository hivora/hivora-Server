package com.ahmadre.hinata.git;

import com.ahmadre.hinata.config.HinataProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for provider access tokens at rest. The key is derived
 * (SHA-256) from {@code hinata.git-integration.token-secret}. Ciphertext is
 * {@code base64(iv || ciphertext||tag)} so each value is self-describing.
 *
 * <p>Tokens are only ever stored encrypted and are {@code @JsonIgnore}'d on the
 * document, so a provider token never leaves the server.
 */
@Component
public class TokenCipher {

	private static final String TRANSFORM = "AES/GCM/NoPadding";
	private static final int IV_LENGTH = 12;
	private static final int TAG_BITS = 128;

	private final SecretKey key;
	private final SecureRandom random = new SecureRandom();

	public TokenCipher(HinataProperties properties) {
		String secret = properties.getGitIntegration().getTokenSecret();
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(secret.getBytes(StandardCharsets.UTF_8));
			this.key = new SecretKeySpec(digest, "AES");
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	/** Encrypts a token; returns {@code null} for a {@code null}/blank input. */
	public String encrypt(String plaintext) {
		if (plaintext == null || plaintext.isBlank()) {
			return null;
		}
		try {
			byte[] iv = new byte[IV_LENGTH];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance(TRANSFORM);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
			return Base64.getEncoder().encodeToString(out);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("token encryption failed", e);
		}
	}

	/** Decrypts a token produced by {@link #encrypt(String)}; {@code null}-safe. */
	public String decrypt(String ciphertext) {
		if (ciphertext == null || ciphertext.isBlank()) {
			return null;
		}
		try {
			byte[] all = Base64.getDecoder().decode(ciphertext);
			ByteBuffer buffer = ByteBuffer.wrap(all);
			byte[] iv = new byte[IV_LENGTH];
			buffer.get(iv);
			byte[] ct = new byte[buffer.remaining()];
			buffer.get(ct);
			Cipher cipher = Cipher.getInstance(TRANSFORM);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("token decryption failed", e);
		}
	}
}
