package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.entity.CardDetailsEntity;
import com.macrotel.rapidstylers.repo.CardDetailsRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CardDetailsEncryptionMigrationTest {

    private CardDetailsRepo cardDetailsRepo;
    private EncryptionConfig encryptionConfig;
    private CardDetailsEncryptionMigration migration;

    @BeforeEach
    void setUp() throws Exception {
        cardDetailsRepo = mock(CardDetailsRepo.class);
        encryptionConfig = new EncryptionConfig();
        ReflectionTestUtils.setField(encryptionConfig, "envKey", "");
        encryptionConfig.init();
        migration = new CardDetailsEncryptionMigration(cardDetailsRepo, encryptionConfig);
    }

    @Test
    void plainUserIdRecordsAreSkipped() {
        CardDetailsEntity card = new CardDetailsEntity();
        card.setId(1L);
        card.setUserId("A1234B"); // 6-char alphanumeric = plain userId
        when(cardDetailsRepo.findAll()).thenReturn(Collections.singletonList(card));

        migration.run();

        verify(cardDetailsRepo, never()).save(any());
    }

    @Test
    void legacyEcbRecordIsDecryptedAndReplaced() throws Exception {
        // Encrypt a known userId with legacy ECB
        String originalUserId = "X9876Z";
        String encryptedUserId = encryptionConfig.encryptLegacy(originalUserId);

        CardDetailsEntity card = new CardDetailsEntity();
        card.setId(2L);
        card.setUserId(encryptedUserId);
        when(cardDetailsRepo.findAll()).thenReturn(Collections.singletonList(card));

        migration.run();

        verify(cardDetailsRepo).save(argThat(c -> originalUserId.equals(c.getUserId())));
    }

    @Test
    void mixedRecordsMigrateOnlyLegacyOnes() throws Exception {
        String encryptedUserId = encryptionConfig.encryptLegacy("M5555N");

        CardDetailsEntity plainCard = new CardDetailsEntity();
        plainCard.setId(10L);
        plainCard.setUserId("A1111B");

        CardDetailsEntity legacyCard = new CardDetailsEntity();
        legacyCard.setId(20L);
        legacyCard.setUserId(encryptedUserId);

        CardDetailsEntity nullCard = new CardDetailsEntity();
        nullCard.setId(30L);
        nullCard.setUserId(null);

        when(cardDetailsRepo.findAll()).thenReturn(Arrays.asList(plainCard, legacyCard, nullCard));

        migration.run();

        // Only the legacy card should be saved
        verify(cardDetailsRepo, times(1)).save(argThat(c -> c.getId() == 20L));
        verify(cardDetailsRepo).save(argThat(c -> "M5555N".equals(c.getUserId())));
    }

    @Test
    void emptyTableRunsWithoutErrors() {
        when(cardDetailsRepo.findAll()).thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> migration.run());
        verify(cardDetailsRepo, never()).save(any());
    }

    @Test
    void decryptionFailureIsLoggedButDoesNotStopMigration() throws Exception {
        // A value that looks like base64 but decrypts to garbage
        CardDetailsEntity badCard = new CardDetailsEntity();
        badCard.setId(40L);
        badCard.setUserId("AQIDBA=="); // 4 bytes — not a valid 16-byte ECB block

        CardDetailsEntity goodCard = new CardDetailsEntity();
        goodCard.setId(50L);
        goodCard.setUserId(encryptionConfig.encryptLegacy("K2222L"));

        when(cardDetailsRepo.findAll()).thenReturn(Arrays.asList(badCard, goodCard));

        // Should not throw — bad record is skipped, good one is migrated
        assertDoesNotThrow(() -> migration.run());
        verify(cardDetailsRepo).save(argThat(c -> "K2222L".equals(c.getUserId())));
    }

    @Test
    void encryptLegacyProducesDeterministicOutput() throws Exception {
        String input = "TEST12";
        String first = encryptionConfig.encryptLegacy(input);
        String second = encryptionConfig.encryptLegacy(input);
        assertEquals(first, second, "Legacy ECB encryption should be deterministic");
    }

    @Test
    void gcmEncryptProducesDifferentOutputEachTime() throws Exception {
        String input = "TEST12";
        String first = encryptionConfig.encrypt(input);
        String second = encryptionConfig.encrypt(input);
        assertNotEquals(first, second, "GCM encryption should produce different ciphertext each time");
    }

    @Test
    void gcmRoundTripSucceeds() throws Exception {
        String input = "A1234B";
        String encrypted = encryptionConfig.encrypt(input);
        String decrypted = encryptionConfig.decrypt(encrypted);
        assertEquals(input, decrypted, "GCM encrypt/decrypt round-trip should preserve plaintext");
    }
}
