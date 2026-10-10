package com.macrotel.rapidstylers.catalog.repo;

import com.macrotel.rapidstylers.catalog.entity.IdentificationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentificationRepo extends JpaRepository<IdentificationEntity, Long> {
    Optional<IdentificationEntity> findByIdentificationName(String idName);

}
