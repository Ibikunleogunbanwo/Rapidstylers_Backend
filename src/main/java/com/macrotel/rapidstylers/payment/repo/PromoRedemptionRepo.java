package com.macrotel.rapidstylers.payment.repo;

import com.macrotel.rapidstylers.payment.entity.PromoRedemptionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromoRedemptionRepo extends JpaRepository<PromoRedemptionEntity, Long> {
    long countByPromoIdAndStatus(Long promoId, String status);

    long countByPromoIdAndUserIdAndStatus(Long promoId, String userId, String status);

    Optional<PromoRedemptionEntity> findByAppointmentId(String appointmentId);

    Page<PromoRedemptionEntity> findByPromoIdOrderByIdDesc(Long promoId, Pageable pageable);

    List<PromoRedemptionEntity> findByPromoIdAndStatus(Long promoId, String status);
}
