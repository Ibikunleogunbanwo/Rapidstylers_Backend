package com.macrotel.rapidstylers.customer.repo;

import com.macrotel.rapidstylers.customer.entity.SavedStylistEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedStylistRepo extends JpaRepository<SavedStylistEntity, Long> {
    Optional<SavedStylistEntity> findByUserIdAndStylerId(String userId, String stylerId);

    List<SavedStylistEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    void deleteByUserIdAndStylerId(String userId, String stylerId);
}
