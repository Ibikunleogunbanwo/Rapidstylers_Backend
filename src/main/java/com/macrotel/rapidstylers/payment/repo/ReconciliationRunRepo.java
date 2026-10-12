package com.macrotel.rapidstylers.payment.repo;

import com.macrotel.rapidstylers.payment.entity.ReconciliationRunEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepo extends JpaRepository<ReconciliationRunEntity, Long> {
    Optional<ReconciliationRunEntity> findFirstByOrderByRanAtDescIdDesc();
}
