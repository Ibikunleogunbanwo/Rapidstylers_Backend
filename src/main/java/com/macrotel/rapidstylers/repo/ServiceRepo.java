package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.ServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ServiceRepo extends JpaRepository<ServiceEntity,Long> {
    Optional<ServiceEntity> findByServiceName(String serviceName);

    /**
     * How many services hold this exact image URL (used before destroying an asset).
     *
     * Named for the field it counts. A service stores its picture in
     * `serviceImageUrl` rather than `imageUrl`, and Spring Data resolves the
     * property from the method name at startup: get it wrong and the repository
     * fails to build, which takes the whole application down rather than quietly
     * returning zero.
     */
    long countByServiceImageUrl(String serviceImageUrl);
}
