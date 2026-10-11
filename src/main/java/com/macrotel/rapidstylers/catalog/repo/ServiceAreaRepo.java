package com.macrotel.rapidstylers.catalog.repo;

import com.macrotel.rapidstylers.catalog.entity.ServiceAreaEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceAreaRepo extends JpaRepository<ServiceAreaEntity, Long> {
    List<ServiceAreaEntity> findByActiveTrue();

    Optional<ServiceAreaEntity> findByAreaKey(String areaKey);
}
