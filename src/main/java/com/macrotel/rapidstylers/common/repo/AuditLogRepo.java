package com.macrotel.rapidstylers.common.repo;

import com.macrotel.rapidstylers.common.entity.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepo extends JpaRepository<AuditLogEntity, Long> {
    List<AuditLogEntity> findTop100ByOrderByCreatedAtDesc();
}
