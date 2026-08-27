package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepo extends JpaRepository<RefreshTokenEntity, Long> {
    Optional<RefreshTokenEntity> findByTokenHashAndRevokedFalse(String tokenHash);
    List<RefreshTokenEntity> findByFamilyId(String familyId);
    void deleteByAccountIdAndRole(String accountId, String role);
}
