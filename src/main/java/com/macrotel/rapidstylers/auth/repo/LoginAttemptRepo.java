package com.macrotel.rapidstylers.auth.repo;

import com.macrotel.rapidstylers.auth.entity.LoginAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginAttemptRepo extends JpaRepository<LoginAttemptEntity, Long> {
}
