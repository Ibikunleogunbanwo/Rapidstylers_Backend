package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.ReferralEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReferralRepo extends JpaRepository<ReferralEntity, Long> {
    Optional<ReferralEntity> findByReferredUserId(String referredUserId);
    List<ReferralEntity> findByReferrerUserIdOrderByCreatedAtDesc(String referrerUserId);
}
