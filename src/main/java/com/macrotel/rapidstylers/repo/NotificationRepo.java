package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepo extends JpaRepository<NotificationEntity, Long> {
    List<NotificationEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<NotificationEntity> findByStylerId(String stylerId);

    long countByUserIdAndReadFalse(String userId);

    Optional<NotificationEntity> findByIdAndUserId(Long id, String userId);
}
