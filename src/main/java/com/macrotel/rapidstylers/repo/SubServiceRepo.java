package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.SubServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SubServiceRepo extends JpaRepository<SubServiceEntity,Long> {
    @Query("SELECT s FROM SubServiceEntity s WHERE s.stylerId =:stylerId AND s.name =:serviceName")
    Optional<SubServiceEntity> isServiceExist(@Param("stylerId") String stylerId, @Param("serviceName") String serviceName);

    List<SubServiceEntity> findByStylerId(String stylerId);
}
