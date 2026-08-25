package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.SupportTicketEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupportTicketRepo extends JpaRepository<SupportTicketEntity, Long> {
    List<SupportTicketEntity> findByUserIdOrderByUpdatedAtDesc(String userId);
    Optional<SupportTicketEntity> findByIdAndUserId(Long id, String userId);
    long countByStatus(String status);
}
