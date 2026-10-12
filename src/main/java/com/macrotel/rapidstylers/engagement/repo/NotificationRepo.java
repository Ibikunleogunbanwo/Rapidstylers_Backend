package com.macrotel.rapidstylers.engagement.repo;

import com.macrotel.rapidstylers.engagement.entity.NotificationEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepo extends JpaRepository<NotificationEntity, Long> {
    List<NotificationEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<NotificationEntity> findByStylerId(String stylerId);

    long countByUserIdAndReadFalse(String userId);

    Optional<NotificationEntity> findByIdAndUserId(Long id, String userId);

    // A stylist's own inbox: rows whose recipientRole is STYLIST.
    List<NotificationEntity> findByStylerIdAndRecipientRoleOrderByCreatedAtDesc(String stylerId, String recipientRole);

    long countByStylerIdAndRecipientRoleAndReadFalse(String stylerId, String recipientRole);

    Optional<NotificationEntity> findByIdAndStylerIdAndRecipientRole(Long id, String stylerId, String recipientRole);
}
