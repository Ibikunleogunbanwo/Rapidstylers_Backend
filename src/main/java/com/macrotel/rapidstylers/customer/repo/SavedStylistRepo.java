package com.macrotel.rapidstylers.customer.repo;

import com.macrotel.rapidstylers.customer.entity.SavedStylistEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface SavedStylistRepo extends JpaRepository<SavedStylistEntity, Long> {
    Optional<SavedStylistEntity> findByUserIdAndStylerId(String userId, String stylerId);

    List<SavedStylistEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    /** A derived delete needs its own transaction; the caller has none. */
    @Transactional
    void deleteByUserIdAndStylerId(String userId, String stylerId);
}
