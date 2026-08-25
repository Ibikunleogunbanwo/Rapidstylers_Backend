package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.BlogPostEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BlogPostRepo extends JpaRepository<BlogPostEntity, Long> {
    Optional<BlogPostEntity> findByTitle(String title);
}
