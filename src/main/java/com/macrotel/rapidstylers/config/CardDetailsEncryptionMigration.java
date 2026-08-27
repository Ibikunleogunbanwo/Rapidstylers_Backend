package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.entity.CardDetailsEntity;
import com.macrotel.rapidstylers.repo.CardDetailsRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;

/**
 * One-time migration that converts legacy AES/ECB-encrypted user IDs in the
 * {@code card_details} table to plain-text user IDs.
 *
 * <p>Why plain text? The booking flow, card setup, and card update endpoints
 * all look up {@code card_details} by the plain user ID. Only the signup flow
 * stored the encrypted value, creating an inconsistency where cards saved
 * during signup were invisible to the booking API until the user updated their
 * card (which stored the plain ID).
 *
 * <p>Detection: old AES/ECB ciphertext is a base64 string of exactly 24
 * characters (encoding 16 bytes). A plain user ID is a 6-character
 * alphanumeric string (e.g. {@code A1234B}). The migration decrypts each
 * legacy value and replaces it with the plaintext.
 *
 * <p>After migration, new signups store the plain user ID directly
 * (see {@link com.macrotel.rapidstylers.service.AppService#userSignUp}).
 */
@Component
public class CardDetailsEncryptionMigration implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CardDetailsEncryptionMigration.class);

    private final CardDetailsRepo cardDetailsRepo;
    private final EncryptionConfig encryptionConfig;

    public CardDetailsEncryptionMigration(CardDetailsRepo cardDetailsRepo, EncryptionConfig encryptionConfig) {
        this.cardDetailsRepo = cardDetailsRepo;
        this.encryptionConfig = encryptionConfig;
    }

    @Override
    public void run(String... args) {
        migrateLegacyUserIds();
    }

    private void migrateLegacyUserIds() {
        List<CardDetailsEntity> allCards = cardDetailsRepo.findAll();
        int migrated = 0;
        int skipped = 0;
        int errors = 0;

        for (CardDetailsEntity card : allCards) {
            String storedUserId = card.getUserId();
            if (storedUserId == null || storedUserId.isBlank()) {
                skipped++;
                continue;
            }

            // Plain user IDs are 6-char alphanumeric (e.g. A1234B). Skip them.
            if (storedUserId.matches("^[A-Za-z0-9]{6}$")) {
                skipped++;
                continue;
            }

            // Legacy AES/ECB ciphertext decodes to exactly 16 bytes (24 base64 chars).
            if (!isLegacyCiphertext(storedUserId)) {
                skipped++;
                continue;
            }

            try {
                String plainUserId = encryptionConfig.decryptLegacy(storedUserId);
                card.setUserId(plainUserId);
                cardDetailsRepo.save(card);
                migrated++;
                LOG.info("[CardEncryptionMigration] Migrated card id={} from ECB to plain userId", card.getId());
            } catch (Exception ex) {
                errors++;
                LOG.warn("[CardEncryptionMigration] Failed to migrate card id={}: {}", card.getId(), ex.getMessage());
            }
        }

        if (migrated > 0 || errors > 0) {
            LOG.info("[CardEncryptionMigration] Complete: {} migrated, {} skipped, {} errors",
                    migrated, skipped, errors);
        } else {
            LOG.debug("[CardEncryptionMigration] No legacy records to migrate ({} total cards checked)", allCards.size());
        }
    }

    /**
     * Detects legacy AES/ECB ciphertext: base64 of a 16-byte block = 24 chars.
     * Plain user IDs are 6-char alphanumeric, so they never match.
     */
    private boolean isLegacyCiphertext(String value) {
        if (!value.matches("^[A-Za-z0-9+/]+=*$")) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length == 16; // AES/ECB with 128-bit key = 16 bytes
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
