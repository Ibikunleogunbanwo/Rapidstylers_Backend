package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.AdminAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminAccountRepo extends JpaRepository<AdminAccountEntity, Long> {
    Optional<AdminAccountEntity> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
}