package com.macrotel.rapidstylers.engagement.repo;

import com.macrotel.rapidstylers.engagement.entity.LoyaltyAccountEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoyaltyAccountRepo extends JpaRepository<LoyaltyAccountEntity, Long> {
    Optional<LoyaltyAccountEntity> findByUserId(String userId);
    Optional<LoyaltyAccountEntity> findByReferralCode(String referralCode);
}
