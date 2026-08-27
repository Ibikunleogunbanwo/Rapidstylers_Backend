package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.RefundEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefundRepo extends JpaRepository<RefundEntity, Long> {
    Optional<RefundEntity> findByRefundId(String refundId);

    List<RefundEntity> findByAppointmentId(String appointmentId);

    List<RefundEntity> findByStatus(String status);

    /** One completed refund per payment intent — the idempotency guard for double-refunds. */
    boolean existsByPaymentIntentIdAndStatus(String paymentIntentId, String status);
}
