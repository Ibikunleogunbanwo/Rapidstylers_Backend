package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.LoyaltyAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoyaltyAccountRepo extends JpaRepository<LoyaltyAccountEntity, Long> {
    Optional<LoyaltyAccountEntity> findByUserId(String userId);
    Optional<LoyaltyAccountEntity> findByReferralCode(String referralCode);
}
