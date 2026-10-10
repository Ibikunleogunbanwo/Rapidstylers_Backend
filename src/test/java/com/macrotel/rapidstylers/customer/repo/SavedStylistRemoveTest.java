package com.macrotel.rapidstylers.customer.repo;

import com.macrotel.rapidstylers.customer.entity.SavedStylistEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Removing a saved professional runs outside any service transaction, so the
 * delete itself must open one. Without it Spring refuses the delete ("No
 * EntityManager with actual transaction available") and the website shows
 * "Unable to remove this professional".
 */
@SpringBootTest
class SavedStylistRemoveTest {

    @Autowired
    SavedStylistRepo savedStylistRepo;

    private final String user = "SAVETEST" + System.nanoTime();

    @AfterEach
    void cleanUp() {
        savedStylistRepo.findByUserIdOrderByCreatedAtDesc(user).forEach(savedStylistRepo::delete);
    }

    @Test
    void aSavedProfessionalCanBeRemovedWithoutAnOuterTransaction() {
        SavedStylistEntity saved = new SavedStylistEntity();
        saved.setUserId(user);
        saved.setStylerId("CM0001");
        saved.setCreatedAt("2026-10-10 10:00:00");
        savedStylistRepo.save(saved);

        savedStylistRepo.deleteByUserIdAndStylerId(user, "CM0001");

        assertTrue(savedStylistRepo.findByUserIdAndStylerId(user, "CM0001").isEmpty());
    }
}
