package com.buildassist.service;

import com.buildassist.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class TokenEncryptionService {

    private static final String CIPHER = "AES/GCM/NoPadding";

    private static final int IV_LENGTH = 12; // recommended GCM nonce length

    private static final int TAG_LENGTH_BITS = 128;

    private final AppProperties appProperties;

    private final SecureRandom secureRandom = new SecureRandom();

    public TokenEncryptionService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] keyBytes = appProperties.getEncryption().getAesKey().getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length < 32) {
                throw new IllegalStateException("AES key must be 32 bytes for AES-256-GCM");
            }
            byte[] key = new byte[32];
            System.arraycopy(keyBytes, 0, key, 0, 32);

            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherBytes, 0, out, iv.length, cipherBytes.length);

            return Base64.getEncoder().encodeToString(out);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to encrypt token", ex);
        }
    }

    public String decrypt(String ciphertext) {
        try {
            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            if (decoded.length < IV_LENGTH) {
                throw new IllegalArgumentException("Ciphertext too short");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(decoded, 0, iv, 0, IV_LENGTH);
            byte[] cipherBytes = new byte[decoded.length - IV_LENGTH];
            System.arraycopy(decoded, IV_LENGTH, cipherBytes, 0, cipherBytes.length);

            byte[] keyBytes = appProperties.getEncryption().getAesKey().getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length < 32) {
                throw new IllegalStateException("AES key must be 32 bytes for AES-256-GCM");
            }
            byte[] key = new byte[32];
            System.arraycopy(keyBytes, 0, key, 0, 32);

            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            byte[] plain = cipher.doFinal(cipherBytes);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to decrypt token", ex);
        }
    }
}
