package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.AvailabilityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import javax.transaction.Transactional;
import java.util.List;

@Repository
public interface AvailabilityRepo extends JpaRepository<AvailabilityEntity, Long> {
    List<AvailabilityEntity> findByStylerId(String stylerId);

    @Transactional
    void deleteByStylerId(String stylerId);
}
