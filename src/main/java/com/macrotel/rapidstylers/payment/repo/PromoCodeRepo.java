package com.macrotel.rapidstylers.payment.repo;

import com.macrotel.rapidstylers.payment.entity.PromoCodeEntity;
import java.util.Optional;
import javax.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromoCodeRepo extends JpaRepository<PromoCodeEntity, Long> {
    Optional<PromoCodeEntity> findByCode(String code);

    /** Locks the code's row so two bookings cannot both take its last use. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PromoCodeEntity p WHERE p.code = :code")
    Optional<PromoCodeEntity> findByCodeForUpdate(@Param("code") String code);
}
