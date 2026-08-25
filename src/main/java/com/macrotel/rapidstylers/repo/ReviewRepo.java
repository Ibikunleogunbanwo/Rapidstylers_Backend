package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.ReviewEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepo extends JpaRepository<ReviewEntity,Long> {
    List<ReviewEntity> findByStylerId(String stylerId);

    List<ReviewEntity> findByStylerIdAndModerationStatus(String stylerId, String moderationStatus);

    List<ReviewEntity> findByModerationStatusOrderByCreatedAtDesc(String moderationStatus);

    Optional<ReviewEntity> findByBookingId(String bookingId);
}
