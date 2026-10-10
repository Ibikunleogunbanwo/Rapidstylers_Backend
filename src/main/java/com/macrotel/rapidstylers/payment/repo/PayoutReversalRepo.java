package com.macrotel.rapidstylers.payment.repo;

import com.macrotel.rapidstylers.payment.entity.PayoutReversalEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutReversalRepo extends JpaRepository<PayoutReversalEntity, Long> {
    Optional<PayoutReversalEntity> findByReversalId(String reversalId);

    List<PayoutReversalEntity> findByTransferId(String transferId);

    /** Retryable reversals whose next attempt is due, oldest first. */
    List<PayoutReversalEntity> findTop20ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            List<String> statuses, LocalDateTime nextAttemptAt);
}
