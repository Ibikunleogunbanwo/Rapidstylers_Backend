package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.AvailabilityExceptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

@Repository
public interface AvailabilityExceptionRepo extends JpaRepository<AvailabilityExceptionEntity, Long> {
    List<AvailabilityExceptionEntity> findByStylerId(String stylerId);

    Optional<AvailabilityExceptionEntity> findByStylerIdAndBlockedDate(String stylerId, String blockedDate);

    @Transactional
    void deleteByStylerIdAndId(String stylerId, Long id);
}
