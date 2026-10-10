package com.macrotel.rapidstylers.payment.repo;

import com.macrotel.rapidstylers.payment.entity.CardDetailsEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardDetailsRepo extends JpaRepository<CardDetailsEntity, Long> {
    Optional<CardDetailsEntity> findByUserId(String userId);

    java.util.List<CardDetailsEntity> findAll();
}
