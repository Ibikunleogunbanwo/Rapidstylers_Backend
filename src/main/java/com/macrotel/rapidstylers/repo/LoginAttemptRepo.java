package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.LoginAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginAttemptRepo extends JpaRepository<LoginAttemptEntity, Long> {
}
