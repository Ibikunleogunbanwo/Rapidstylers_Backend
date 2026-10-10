package com.macrotel.rapidstylers.common.repo;

import com.macrotel.rapidstylers.common.entity.AuditLogEntity;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepo extends JpaRepository<AuditLogEntity, Long> {
    List<AuditLogEntity> findTop100ByOrderByCreatedAtDesc();

    /** Newest first, narrowed by whichever of action, actor and record are given. */
    @Query("select a from AuditLogEntity a where (:action is null or a.action = :action)"
            + " and (:actorId is null or a.actorId = :actorId)"
            + " and (:resourceId is null or a.resourceId = :resourceId)"
            + " order by a.createdAt desc, a.id desc")
    Page<AuditLogEntity> search(@Param("action") String action, @Param("actorId") String actorId,
                                @Param("resourceId") String resourceId, Pageable pageable);
}
