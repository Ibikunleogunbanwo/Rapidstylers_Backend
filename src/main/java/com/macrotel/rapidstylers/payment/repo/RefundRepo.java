package com.macrotel.rapidstylers.payment.repo;

import com.macrotel.rapidstylers.payment.entity.RefundEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepo extends JpaRepository<RefundEntity, Long> {
    Optional<RefundEntity> findByRefundId(String refundId);

    List<RefundEntity> findByAppointmentId(String appointmentId);

    List<RefundEntity> findByStatus(String status);

    /** One completed refund per payment intent — the idempotency guard for double-refunds. */
    boolean existsByPaymentIntentIdAndStatus(String paymentIntentId, String status);
}
