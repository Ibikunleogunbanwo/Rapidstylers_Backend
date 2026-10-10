package com.macrotel.rapidstylers.catalog.repo;

import com.macrotel.rapidstylers.catalog.entity.BlogPostEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BlogPostRepo extends JpaRepository<BlogPostEntity, Long> {
    Optional<BlogPostEntity> findByTitle(String title);

    /** How many blog articles hold this exact cover URL (used before destroying an asset). */
    long countByImageUrlContaining(String fragment);
}
