package com.macrotel.rapidstylers.common.repo;

import com.macrotel.rapidstylers.common.entity.AuditLogEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepo extends JpaRepository<AuditLogEntity, Long> {
    List<AuditLogEntity> findTop100ByOrderByCreatedAtDesc();
}
