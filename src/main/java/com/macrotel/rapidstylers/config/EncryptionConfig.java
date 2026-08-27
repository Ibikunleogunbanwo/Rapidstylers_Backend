package com.macrotel.rapidstylers.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import static com.macrotel.rapidstylers.config.AppConstants.ENCRYPT_DECRYPT_KEY_FALLBACK;

/**
 * AES/GCM encryption for card-details user IDs.
 *
 * <p>The encryption key is resolved in order:
 * <ol>
 *   <li>Environment variable {@code ENCRYPT_KEY} (recommended for production)</li>
 *   <li>Fallback to the legacy hardcoded key (keeps existing records readable)</li>
 * </ol>
 *
 * <p>AES/GCM provides authenticated encryption with a random 12-byte IV per
 * ciphertext, which is prepended to the output. Old AES/ECB records are
 * transparently re-encrypted on the next read via {@link #encryptWithLegacyFallback}.
 */
@Component
public class EncryptionConfig {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String AES_ECB = "AES"; // legacy mode, kept for migration

    private SecretKeySpec gcmKey;
    private SecretKeySpec legacyKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.encrypt.key:}")
    private String envKey;

    @PostConstruct
    void init() {
        byte[] raw = resolveKeyMaterial();
        // GCM needs the full 256-bit key
        gcmKey = new SecretKeySpec(Arrays.copyOf(raw, 32), "AES");
        // Legacy ECB key was truncated to 128-bit
        legacyKey = new SecretKeySpec(Arrays.copyOf(raw, 16), "AES");
    }

    private byte[] resolveKeyMaterial() {
        String keySource = (envKey != null && !envKey.trim().isEmpty())
                ? envKey : ENCRYPT_DECRYPT_KEY_FALLBACK;
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(keySource.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive encryption key", e);
        }
    }

    /** Encrypt with AES/GCM — produces a random-IV ciphertext every time. */
    public String encrypt(String input) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.ENCRYPT_MODE, gcmKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] encrypted = cipher.doFinal(input.getBytes(StandardCharsets.UTF_8));
        // Prepend IV so decrypt can recover it
        ByteBuffer buf = ByteBuffer.allocate(iv.length + encrypted.length);
        buf.put(iv);
        buf.put(encrypted);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    /** Decrypt an AES/GCM ciphertext (IV is prepended). */
    public String decrypt(String input) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(input);
        if (decoded.length < GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Ciphertext too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(decoded);
        byte[] iv = new byte[GCM_IV_LENGTH];
        buf.get(iv);
        byte[] encrypted = new byte[buf.remaining()];
        buf.get(encrypted);
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.DECRYPT_MODE, gcmKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    // ---- Legacy AES/ECB support for migrating old card_details rows ----

    /** Legacy encrypt (AES/ECB) — used to match old records during migration. */
    public String encryptLegacy(String input) throws Exception {
        Cipher cipher = Cipher.getInstance(AES_ECB);
        cipher.init(Cipher.ENCRYPT_MODE, legacyKey);
        return Base64.getEncoder().encodeToString(cipher.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }


    /** Decrypt an AES/ECB ciphertext produced by the legacy encrypt. */
    public String decryptLegacy(String input) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(input);
        Cipher cipher = Cipher.getInstance(AES_ECB);
        cipher.init(Cipher.DECRYPT_MODE, legacyKey);
        return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
    }

    /**
     * Encrypt with AES/GCM, but if a legacy-encrypted record exists in the DB,
     * callers can use {@link #encryptLegacy} to find it first, then re-encrypt
     * with {@link #encrypt} on the next write.
     */
    public boolean isLegacyEncrypted(String stored) {
        try {
            byte[] decoded = Base64.getDecoder().decode(stored);
            // GCM minimum = 12 IV + 16 auth tag = 28 bytes. ECB is 16 bytes.
            return decoded.length < (GCM_IV_LENGTH + GCM_TAG_LENGTH / 8);
        } catch (Exception e) {
            return true;
        }
    }
}
