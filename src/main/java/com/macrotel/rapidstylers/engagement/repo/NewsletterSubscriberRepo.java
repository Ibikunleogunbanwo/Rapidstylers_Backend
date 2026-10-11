package com.macrotel.rapidstylers.engagement.repo;

import com.macrotel.rapidstylers.engagement.entity.NewsletterSubscriberEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsletterSubscriberRepo extends JpaRepository<NewsletterSubscriberEntity, Long> {
    Optional<NewsletterSubscriberEntity> findByEmail(String email);

    Optional<NewsletterSubscriberEntity> findByConfirmToken(String confirmToken);

    Optional<NewsletterSubscriberEntity> findByUnsubscribeToken(String unsubscribeToken);

    List<NewsletterSubscriberEntity> findByStatus(String status);

    Page<NewsletterSubscriberEntity> findByStatus(String status, Pageable pageable);

    Page<NewsletterSubscriberEntity> findByEmailContaining(String text, Pageable pageable);

    long countByStatus(String status);
}
