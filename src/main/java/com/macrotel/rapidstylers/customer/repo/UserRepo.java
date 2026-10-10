package com.macrotel.rapidstylers.customer.repo;

import com.macrotel.rapidstylers.customer.entity.UserEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepo extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByEmailAddress(String emailAddress);

    Optional<UserEntity> findByPhoneNumber(String phoneNumber);
    @Query(value = "SELECT u FROM UserEntity u WHERE u.emailAddress =:emailAddress AND u.password =:password AND u.status='0'")
    Optional<UserEntity> userAuthenticate(@Param("emailAddress") String emailAddress, @Param("password") String password);

    Optional<UserEntity> findByUserId (String userId);

    /** Ids of active customer accounts, for admin announcements. */
    @Query("SELECT u.userId FROM UserEntity u WHERE u.status = '0' AND u.userId IS NOT NULL")
    java.util.List<String> findActiveUserIds();
}
