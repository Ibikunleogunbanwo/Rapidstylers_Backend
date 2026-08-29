package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepo extends JpaRepository<RefreshTokenEntity, Long> {
    Optional<RefreshTokenEntity> findByTokenHashAndRevokedFalse(String tokenHash);
    List<RefreshTokenEntity> findByFamilyId(String familyId);
    @Transactional
    void deleteByAccountIdAndRole(String accountId, String role);
}
