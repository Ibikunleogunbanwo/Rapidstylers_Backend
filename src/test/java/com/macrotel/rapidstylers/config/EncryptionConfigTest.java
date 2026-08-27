package com.macrotel.rapidstylers.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for {@link EncryptionConfig}: AES/GCM round-trip,
 * legacy AES/ECB fallback, key resolution, and edge cases.
 */
class EncryptionConfigTest {

    private EncryptionConfig config;

    @BeforeEach
    void setUp() throws Exception {
        config = new EncryptionConfig();
        // Use the legacy fallback key (same as production default)
        ReflectionTestUtils.setField(config, "envKey", "");
        config.init();
    }

    // ── AES/GCM round-trip ───────────────────────────────────────────

    @Test
    void gcmRoundTripPreservesPlaintext() throws Exception {
        String plain = "A1234B";
        assertEquals(plain, config.decrypt(config.encrypt(plain)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"X1", "AB9999Z", "A", "12345678901234567890"})
    void gcmRoundTripWorksForVariousLengths(String input) throws Exception {
        assertEquals(input, config.decrypt(config.encrypt(input)));
    }

    @Test
    void gcmRoundTripHandlesEmptyString() throws Exception {
        assertEquals("", config.decrypt(config.encrypt("")));
    }

    @Test
    void gcmRoundTripHandlesUnicode() throws Exception {
        String unicode = "用户ID-/café-émojis-🎉";
        assertEquals(unicode, config.decrypt(config.encrypt(unicode)));
    }

    @Test
    void gcmRoundTripHandlesLongString() throws Exception {
        String longInput = "A".repeat(10_000);
        assertEquals(longInput, config.decrypt(config.encrypt(longInput)));
    }

    @Test
    void gcmEncryptProducesDifferentCiphertextEachTime() throws Exception {
        String input = "TEST12";
        String first = config.encrypt(input);
        String second = config.encrypt(input);
        assertNotEquals(first, second,
                "AES/GCM uses a random IV, so identical plaintext must produce different ciphertext");
    }

    @Test
    void gcmCiphertextIsBase64() throws Exception {
        String ciphertext = config.encrypt("A1234B");
        assertDoesNotThrow(() -> java.util.Base64.getDecoder().decode(ciphertext),
                "GCM ciphertext should be valid Base64");
    }

    @Test
    void gcmDecryptRejectsTamperedCiphertext() throws Exception {
        String ciphertext = config.encrypt("A1234B");
        // Flip a character in the middle of the ciphertext
        String tampered = ciphertext.substring(0, ciphertext.length() / 2) + "A"
                + ciphertext.substring(ciphertext.length() / 2 + 1);
        assertThrows(Exception.class, () -> config.decrypt(tampered),
                "Tampered GCM ciphertext must fail authentication");
    }

    @Test
    void gcmDecryptRejectsShortInput() {
        assertThrows(Exception.class, () -> config.decrypt("AQID"),
                "Input shorter than IV length must be rejected");
    }

    @Test
    void gcmDecryptRejectsInvalidBase64() {
        assertThrows(Exception.class, () -> config.decrypt("!!!not-base64!!!"));
    }

    // ── Legacy AES/ECB ───────────────────────────────────────────────

    @Test
    void legacyEcbRoundTripPreservesPlaintext() throws Exception {
        String plain = "X9876Z";
        assertEquals(plain, config.decryptLegacy(config.encryptLegacy(plain)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"A1234B", "Z0000A", "M5555N", "123456"})
    void legacyEcbRoundTripWorksForVariousInputs(String input) throws Exception {
        assertEquals(input, config.decryptLegacy(config.encryptLegacy(input)));
    }

    @Test
    void legacyEcbProducesDeterministicOutput() throws Exception {
        String input = "TEST12";
        assertEquals(config.encryptLegacy(input), config.encryptLegacy(input),
                "AES/ECB is deterministic — same input must produce same ciphertext");
    }

    @Test
    void legacyEcbCiphertextIsShorterThanGcm() throws Exception {
        String input = "A1234B";
        String ecb = config.encryptLegacy(input);
        String gcm = config.encrypt(input);
        // ECB: 16 bytes → 24 base64 chars; GCM: 12 IV + 16 data + 16 tag → ~52 base64 chars
        assertTrue(ecb.length() < gcm.length(),
                "ECB ciphertext should be shorter than GCM (no IV, no auth tag)");
    }

    // ── Cross-mode isolation ──────────────────────────────────────────

    @Test
    void gcmOutputCannotBeDecryptedWithDecryptLegacy() throws Exception {
        String gcmCiphertext = config.encrypt("A1234B");
        assertThrows(Exception.class, () -> config.decryptLegacy(gcmCiphertext),
                "GCM ciphertext must not be decryptable with the legacy ECB method");
    }

    @Test
    void legacyEcbOutputCannotBeDecryptedWithGcmDecrypt() throws Exception {
        String ecbCiphertext = config.encryptLegacy("A1234B");
        assertThrows(Exception.class, () -> config.decrypt(ecbCiphertext),
                "Legacy ECB ciphertext must not be decryptable with the GCM method");
    }

    // ── isLegacyEncrypted detection ──────────────────────────────────

    @Test
    void isLegacyEncryptedReturnsTrueForEcb() throws Exception {
        String ecb = config.encryptLegacy("A1234B");
        assertTrue(config.isLegacyEncrypted(ecb),
                "Legacy ECB ciphertext should be detected as legacy");
    }

    @Test
    void isLegacyEncryptedReturnsFalseForGcm() throws Exception {
        String gcm = config.encrypt("A1234B");
        assertFalse(config.isLegacyEncrypted(gcm),
                "GCM ciphertext should not be detected as legacy");
    }

    @Test
    void isLegacyEncryptedReturnsTrueForGarbage() {
        assertTrue(config.isLegacyEncrypted("not-valid-base64!!!"),
                "Unrecognizable input should be treated as legacy (fail-safe)");
    }

    @Test
    void isLegacyEncryptedReturnsTrueForShortBase64() {
        // 4 bytes of base64 — shorter than GCM IV length
        assertTrue(config.isLegacyEncrypted("AQIDBA=="),
                "Short base64 should be detected as legacy");
    }

    // ── Key resolution ────────────────────────────────────────────────

    @Test
    void usesFallbackKeyWhenEnvKeyIsEmpty() throws Exception {
        EncryptionConfig withFallback = new EncryptionConfig();
        ReflectionTestUtils.setField(withFallback, "envKey", "");
        withFallback.init();

        // Should encrypt/decrypt successfully using the hardcoded fallback key
        String plain = "A1234B";
        assertEquals(plain, withFallback.decrypt(withFallback.encrypt(plain)));
    }

    @Test
    void usesEnvKeyWhenProvided() throws Exception {
        EncryptionConfig withEnvKey = new EncryptionConfig();
        ReflectionTestUtils.setField(withEnvKey, "envKey", "my-custom-production-key-2024");
        withEnvKey.init();

        String plain = "A1234B";
        assertEquals(plain, withEnvKey.decrypt(withEnvKey.encrypt(plain)));
    }

    @Test
    void differentKeysProduceDifferentCiphertext() throws Exception {
        EncryptionConfig configA = new EncryptionConfig();
        ReflectionTestUtils.setField(configA, "envKey", "key-alpha");
        configA.init();

        EncryptionConfig configB = new EncryptionConfig();
        ReflectionTestUtils.setField(configB, "envKey", "key-beta");
        configB.init();

        String plain = "A1234B";
        String encryptedA = configA.encrypt(plain);
        String encryptedB = configB.encrypt(plain);

        // Even though GCM is non-deterministic, with different keys the
        // decrypted outputs should differ (or at least one should fail)
        String decryptedA = configA.decrypt(encryptedA);
        String decryptedB = configB.decrypt(encryptedB);
        assertEquals(plain, decryptedA);
        assertEquals(plain, decryptedB);

        // Cross-decrypt must fail — key A can't decrypt key B's output
        assertThrows(Exception.class, () -> configA.decrypt(encryptedB));
        assertThrows(Exception.class, () -> configB.decrypt(encryptedA));
    }

    @Test
    void envKeyOverridesFallbackKey() throws Exception {
        // Encrypt with the fallback key
        String fallbackPlain = "FALLBACK";

        // Encrypt with a custom env key
        EncryptionConfig customConfig = new EncryptionConfig();
        ReflectionTestUtils.setField(customConfig, "envKey", "custom-env-key");
        customConfig.init();
        String customEncrypted = customConfig.encrypt(fallbackPlain);

        // The default config (fallback key) must NOT be able to decrypt it
        assertThrows(Exception.class, () -> config.decrypt(customEncrypted),
                "Custom env key output must not be decryptable with the fallback key");
    }

    // ── High-throughput smoke test ────────────────────────────────────

    @Test
    void encryptDecrypt1000TimesWithoutFailure() throws Exception {
        for (int i = 0; i < 1000; i++) {
            String plain = "USER" + i;
            String encrypted = config.encrypt(plain);
            String decrypted = config.decrypt(encrypted);
            assertEquals(plain, decrypted, "Round-trip failed at iteration " + i);
        }
    }
}
