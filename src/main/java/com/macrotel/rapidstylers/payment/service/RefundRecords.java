package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.payment.entity.RefundEntity;
import com.macrotel.rapidstylers.payment.repo.RefundRepo;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The payment domain's front door for refunds, used to show a booking's
 * refund. Only payment code reaches {@link RefundRepo} directly.
 */
@Service
public class RefundRecords {

    @Autowired
    RefundRepo refundRepo;

    public RefundRecords() {
    }

    public RefundRecords(RefundRepo refundRepo) {
        this.refundRepo = refundRepo;
    }

    public List<RefundEntity> findByAppointmentId(String appointmentId) {
        return refundRepo.findByAppointmentId(appointmentId);
    }

    /** Every refund that went through (status COMPLETED), for revenue reports. */
    public List<RefundEntity> findCompleted() {
        return refundRepo.findByStatus("COMPLETED");
    }
}
