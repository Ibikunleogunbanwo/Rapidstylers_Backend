package com.macrotel.rapidstylers.engagement.repo;

import com.macrotel.rapidstylers.engagement.entity.EmailSuppressionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailSuppressionRepo extends JpaRepository<EmailSuppressionEntity, Long> {
    Optional<EmailSuppressionEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    List<EmailSuppressionEntity> findTop500ByOrderByCreatedAtDesc();
}
