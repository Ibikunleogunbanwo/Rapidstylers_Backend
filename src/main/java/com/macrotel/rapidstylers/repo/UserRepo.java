package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepo extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByEmailAddress(String emailAddress);
}
