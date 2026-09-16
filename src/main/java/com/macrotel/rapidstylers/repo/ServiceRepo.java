package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.ServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ServiceRepo extends JpaRepository<ServiceEntity,Long> {
    Optional<ServiceEntity> findByServiceName(String serviceName);

    /** How many services hold this exact image URL (used before destroying an asset). */
    long countByImageUrl(String imageUrl);
}
