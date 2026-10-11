package com.macrotel.rapidstylers.catalog.repo;

import com.macrotel.rapidstylers.catalog.entity.SiteContentVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteContentVersionRepo extends JpaRepository<SiteContentVersionEntity, Long> {
    List<SiteContentVersionEntity> findByContentKeyOrderByVersionDesc(String contentKey);

    Optional<SiteContentVersionEntity> findByContentKeyAndVersion(String contentKey, int version);
}
