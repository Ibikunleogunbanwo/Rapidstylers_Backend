package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepo extends JpaRepository<AuditLogEntity, Long> {
    List<AuditLogEntity> findTop100ByOrderByCreatedAtDesc();
}
