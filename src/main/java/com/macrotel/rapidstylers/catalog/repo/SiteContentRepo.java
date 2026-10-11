package com.macrotel.rapidstylers.catalog.repo;

import com.macrotel.rapidstylers.catalog.entity.SiteContentEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteContentRepo extends JpaRepository<SiteContentEntity, Long> {
    Optional<SiteContentEntity> findByContentKey(String contentKey);

    List<SiteContentEntity> findByKindOrderByContentKey(String kind);
}
