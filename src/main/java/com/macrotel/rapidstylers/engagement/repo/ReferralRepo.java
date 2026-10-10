package com.macrotel.rapidstylers.engagement.repo;

import com.macrotel.rapidstylers.engagement.entity.ReferralEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferralRepo extends JpaRepository<ReferralEntity, Long> {
    Optional<ReferralEntity> findByReferredUserId(String referredUserId);
    List<ReferralEntity> findByReferrerUserIdOrderByCreatedAtDesc(String referrerUserId);
}
