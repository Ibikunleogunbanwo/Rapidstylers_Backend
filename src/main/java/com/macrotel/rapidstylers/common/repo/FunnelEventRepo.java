package com.macrotel.rapidstylers.common.repo;

import com.macrotel.rapidstylers.common.entity.FunnelEventEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FunnelEventRepo extends JpaRepository<FunnelEventEntity, Long> {

    boolean existsByDedupeKey(String dedupeKey);

    /** (step, role, subject, dayKey) for every event on the days given (inclusive, yyyy-MM-dd sorts as text). */
    @Query("SELECT f.step, f.role, f.subject, f.dayKey FROM FunnelEventEntity f WHERE f.dayKey >= :fromDay AND f.dayKey <= :toDay")
    List<Object[]> eventsBetween(@Param("fromDay") String fromDay, @Param("toDay") String toDay);
}
