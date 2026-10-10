package com.macrotel.rapidstylers.payment.repo;

import com.macrotel.rapidstylers.payment.entity.RefundEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepo extends JpaRepository<RefundEntity, Long> {
    Optional<RefundEntity> findByRefundId(String refundId);

    List<RefundEntity> findByAppointmentId(String appointmentId);

    List<RefundEntity> findByStatus(String status);

    /** The refunds of one payment in a given state; completed ones add up to what was returned. */
    List<RefundEntity> findByPaymentIntentIdAndStatus(String paymentIntentId, String status);
}
