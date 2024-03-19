package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.IdentificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IdentificationRepo extends JpaRepository<IdentificationEntity, Long> {
    Optional<IdentificationEntity> findByIdentificationName(String idName);

}
