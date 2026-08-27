package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.PayoutReversalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PayoutReversalRepo extends JpaRepository<PayoutReversalEntity, Long> {
    Optional<PayoutReversalEntity> findByReversalId(String reversalId);

    List<PayoutReversalEntity> findByTransferId(String transferId);

    /** Retryable reversals whose next attempt is due, oldest first. */
    List<PayoutReversalEntity> findTop20ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            List<String> statuses, LocalDateTime nextAttemptAt);
}
