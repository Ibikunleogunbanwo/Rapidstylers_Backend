package com.macrotel.rapidstylers.styler.repo;

import com.macrotel.rapidstylers.styler.entity.AvailabilityEntity;
import java.util.List;
import javax.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AvailabilityRepo extends JpaRepository<AvailabilityEntity, Long> {
    List<AvailabilityEntity> findByStylerId(String stylerId);

    @Transactional
    void deleteByStylerId(String stylerId);
}
