package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.CardDetailsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CardDetailsRepo extends JpaRepository<CardDetailsEntity, Long> {
    Optional<CardDetailsEntity> findByUserId(String userId);

    java.util.List<CardDetailsEntity> findAll();
}
