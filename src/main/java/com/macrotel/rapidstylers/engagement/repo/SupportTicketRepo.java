package com.macrotel.rapidstylers.engagement.repo;

import com.macrotel.rapidstylers.engagement.entity.SupportTicketEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketRepo extends JpaRepository<SupportTicketEntity, Long> {
    List<SupportTicketEntity> findByUserIdOrderByUpdatedAtDesc(String userId);
    Optional<SupportTicketEntity> findByIdAndUserId(Long id, String userId);
    long countByStatus(String status);
}
