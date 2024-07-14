package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.FeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedBackRepo extends JpaRepository<FeedbackEntity, Long> {
}
