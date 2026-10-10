package com.macrotel.rapidstylers.engagement.repo;

import com.macrotel.rapidstylers.engagement.entity.ReviewEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepo extends JpaRepository<ReviewEntity,Long> {
    List<ReviewEntity> findByStylerId(String stylerId);

    List<ReviewEntity> findByStylerIdAndModerationStatus(String stylerId, String moderationStatus);

    List<ReviewEntity> findByModerationStatusOrderByCreatedAtDesc(String moderationStatus);

    Optional<ReviewEntity> findByBookingId(String bookingId);
}
