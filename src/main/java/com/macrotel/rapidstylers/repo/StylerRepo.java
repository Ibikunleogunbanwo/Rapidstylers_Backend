package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.StylerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StylerRepo extends JpaRepository<StylerEntity, Long> {

    @Query(value = "SELECT s FROM ServiceEntity s WHERE s.emailAddress =:emilAddress AND s.status= '0'")
    Optional<StylerEntity> isEmailExist (@Param("emailAddress") String emailAddress);
}
