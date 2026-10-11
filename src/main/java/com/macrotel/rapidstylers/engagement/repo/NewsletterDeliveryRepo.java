package com.macrotel.rapidstylers.engagement.repo;

import com.macrotel.rapidstylers.engagement.entity.NewsletterDeliveryEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsletterDeliveryRepo extends JpaRepository<NewsletterDeliveryEntity, Long> {
    List<NewsletterDeliveryEntity> findByCampaignIdAndStatusOrderById(Long campaignId, String status, Pageable pageable);

    List<NewsletterDeliveryEntity> findByCampaignIdAndStatus(Long campaignId, String status);

    long countByCampaignIdAndStatus(Long campaignId, String status);

    long countByCampaignId(Long campaignId);

    long countByQueuedAtAfter(LocalDateTime time);
}
