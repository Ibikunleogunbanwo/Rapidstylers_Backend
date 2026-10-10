package com.macrotel.rapidstylers.admin.repo;

import com.macrotel.rapidstylers.admin.entity.AdminAccountEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAccountRepo extends JpaRepository<AdminAccountEntity, Long> {
    Optional<AdminAccountEntity> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
}