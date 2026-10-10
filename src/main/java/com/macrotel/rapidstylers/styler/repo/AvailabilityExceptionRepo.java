package com.macrotel.rapidstylers.styler.repo;

import com.macrotel.rapidstylers.styler.entity.AvailabilityExceptionEntity;
import java.util.List;
import java.util.Optional;
import javax.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AvailabilityExceptionRepo extends JpaRepository<AvailabilityExceptionEntity, Long> {
    List<AvailabilityExceptionEntity> findByStylerId(String stylerId);

    Optional<AvailabilityExceptionEntity> findByStylerIdAndBlockedDate(String stylerId, String blockedDate);

    @Transactional
    void deleteByStylerIdAndId(String stylerId, Long id);
}
