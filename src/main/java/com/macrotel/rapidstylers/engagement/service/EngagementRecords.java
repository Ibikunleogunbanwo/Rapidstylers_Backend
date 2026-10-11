package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.engagement.entity.NotificationEntity;
import com.macrotel.rapidstylers.engagement.entity.ReviewEntity;
import com.macrotel.rapidstylers.engagement.repo.NotificationRepo;
import com.macrotel.rapidstylers.engagement.repo.ReviewRepo;
import com.macrotel.rapidstylers.engagement.repo.SupportTicketRepo;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The engagement domain's front door for other domains: in-app notifications,
 * reviews and support-ticket counts. Only engagement code reaches its repos
 * directly.
 */
@Service
public class EngagementRecords {

    @Autowired
    NotificationRepo notificationRepo;
    @Autowired
    ReviewRepo reviewRepo;
    @Autowired
    SupportTicketRepo supportTicketRepo;

    public EngagementRecords() {
    }

    public EngagementRecords(NotificationRepo notificationRepo, ReviewRepo reviewRepo, SupportTicketRepo supportTicketRepo) {
        this.notificationRepo = notificationRepo;
        this.reviewRepo = reviewRepo;
        this.supportTicketRepo = supportTicketRepo;
    }

    public NotificationEntity saveNotification(NotificationEntity notification) {
        return notificationRepo.save(notification);
    }

    public List<ReviewEntity> findByStylerIdAndModerationStatus(String stylerId, String moderationStatus) {
        return reviewRepo.findByStylerIdAndModerationStatus(stylerId, moderationStatus);
    }

    public long countReviews() {
        return reviewRepo.count();
    }

    public long countTicketsByStatus(String status) {
        return supportTicketRepo.countByStatus(status);
    }
}
