package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.ReviewEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewRepo extends JpaRepository<ReviewEntity,Long> {
    List<ReviewEntity> findByStylerId(String stylerId);
}
