package com.macrotel.rapidstylers.engagement.repo;

import com.macrotel.rapidstylers.engagement.entity.NewsletterCampaignEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsletterCampaignRepo extends JpaRepository<NewsletterCampaignEntity, Long> {
    List<NewsletterCampaignEntity> findTop500ByOrderByCreatedAtDesc();

    List<NewsletterCampaignEntity> findByStatusAndScheduledAtLessThanEqual(String status, LocalDateTime time);

    List<NewsletterCampaignEntity> findByStatusOrderByStartedAt(String status);
}
