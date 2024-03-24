package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.StylerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StylerRepo extends JpaRepository<StylerEntity, Long> {
    @Query(value = "SELECT s FROM StylerEntity s WHERE s.emailAddress =:emailAddress AND s.status= '0'")
    Optional<StylerEntity> isEmailExist (@Param("emailAddress") String emailAddress);
    @Query(value = "SELECT s FROM StylerEntity s WHERE s.emailAddress =:emailAddress AND s.password =:password AND s.status='0'")
    Optional<StylerEntity> stylerAuthenticate(@Param("emailAddress") String emailAddress, @Param("password") String password);

    Optional<StylerEntity> findByStylerId(String stylerId);
    @Query(value ="SELECT * FROM stylers WHERE business_name LIKE %:businessName%", nativeQuery = true)
    List<StylerEntity> searchStyler(@Param("businessName") String businessName);
}
