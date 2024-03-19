package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepo extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByEmailAddress(String emailAddress);
    @Query(value = "SELECT u FROM UserEntity u WHERE u.emailAddress =:emailAddress AND u.password =:password AND u.status='0'")
    Optional<UserEntity> userAuthenticate(@Param("emailAddress") String emailAddress, @Param("password") String password);
}
