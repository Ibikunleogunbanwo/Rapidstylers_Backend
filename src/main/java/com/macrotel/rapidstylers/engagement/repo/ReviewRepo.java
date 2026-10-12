package com.macrotel.rapidstylers.engagement.repo;

import com.macrotel.rapidstylers.engagement.entity.ReviewEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepo extends JpaRepository<ReviewEntity,Long> {
    List<ReviewEntity> findByStylerId(String stylerId);

    List<ReviewEntity> findByStylerIdAndModerationStatus(String stylerId, String moderationStatus);

    List<ReviewEntity> findByModerationStatusOrderByCreatedAtDesc(String moderationStatus);

    Optional<ReviewEntity> findByBookingId(String bookingId);

    /**
     * Admin review list: one moderation status, newest first (by id: createdAt is
     * stored as "MM dd, yyyy" text, which does not sort by date), optionally
     * narrowed to a lower-case %pattern% on the reviewer's name, the message or
     * the stylist's business name.
     */
    @Query("select r from ReviewEntity r where r.moderationStatus = :status and (:pattern is null"
            + " or lower(r.userName) like :pattern or lower(r.message) like :pattern"
            + " or r.stylerId in (select s.stylerId from StylerEntity s where lower(s.businessName) like :pattern))"
            + " order by r.id desc")
    Page<ReviewEntity> adminSearch(@Param("status") String status, @Param("pattern") String pattern, Pageable pageable);
}
