package com.macrotel.rapidstylers.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepo extends JpaRepository<OutboxEventEntity, Long> {
    List<OutboxEventEntity> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            OutboxStatus status, LocalDateTime nextAttemptAt);
}
