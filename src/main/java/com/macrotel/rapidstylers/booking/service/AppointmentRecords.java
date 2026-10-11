package com.macrotel.rapidstylers.booking.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The booking domain's front door for other domains (payment today).
 *
 * <p>Picture the booking table behind a counter: other domains ask this
 * class for appointments and hand changed ones back here, and only booking
 * code reaches past the counter to {@link BookAppointmentRepo}. Calls join
 * the caller's transaction, so the row lock taken by
 * {@link #findByAppointmentIdForUpdate} holds for the caller's whole unit of work.
 */
@Service
public class AppointmentRecords {

    @Autowired
    BookAppointmentRepo bookAppointmentRepo;

    public AppointmentRecords() {
    }

    public AppointmentRecords(BookAppointmentRepo bookAppointmentRepo) {
        this.bookAppointmentRepo = bookAppointmentRepo;
    }

    public BookAppointmentEntity save(BookAppointmentEntity appointment) {
        return bookAppointmentRepo.save(appointment);
    }

    public List<BookAppointmentEntity> findAll() {
        return bookAppointmentRepo.findAll();
    }

    public Optional<BookAppointmentEntity> findByAppointmentId(String appointmentId) {
        return bookAppointmentRepo.findByAppointmentId(appointmentId);
    }

    /** Locks the appointment row until the caller's transaction ends. */
    public Optional<BookAppointmentEntity> findByAppointmentIdForUpdate(String appointmentId) {
        return bookAppointmentRepo.findByAppointmentIdForUpdate(appointmentId);
    }

    public Optional<BookAppointmentEntity> findByPaymentIntentId(String paymentIntentId) {
        return bookAppointmentRepo.findByPaymentIntentId(paymentIntentId);
    }

    public List<BookAppointmentEntity> findByStylerIdAndAppointmentDate(String stylerId, String appointmentDate) {
        return bookAppointmentRepo.findByStylerIdAndAppointmentDate(stylerId, appointmentDate);
    }

    public List<BookAppointmentEntity> findByStylerIdAndAppointmentDateValue(String stylerId, java.time.LocalDate appointmentDate) {
        return bookAppointmentRepo.findByStylerIdAndAppointmentDateValue(stylerId, appointmentDate);
    }

    public List<BookAppointmentEntity> findByStylerId(String stylerId) {
        return bookAppointmentRepo.findByStylerId(stylerId);
    }

    public List<BookAppointmentEntity> findByServiceDisputeStatus(String serviceDisputeStatus) {
        return bookAppointmentRepo.findByServiceDisputeStatus(serviceDisputeStatus);
    }

    public List<BookAppointmentEntity> findByStylerIdAndPayoutHoldReason(String stylerId, String payoutHoldReason) {
        return bookAppointmentRepo.findByStylerIdAndPayoutHoldReason(stylerId, payoutHoldReason);
    }

    public boolean existsByStylerIdAndPayoutHoldReason(String stylerId, String payoutHoldReason) {
        return bookAppointmentRepo.existsByStylerIdAndPayoutHoldReason(stylerId, payoutHoldReason);
    }

    /** Up to 50 completed, charged bookings whose stylist payout is due by {@code dueBy} and not yet sent. */
    public List<BookAppointmentEntity> payoutsDueBy(LocalDateTime dueBy) {
        return bookAppointmentRepo.findTop50ByStatusAndPaymentStatusAndStripeTransferIdIsNullAndPayoutDueAtLessThanEqual(
                "0", "CAPTURED", dueBy);
    }

    /** Completed, charged bookings whose stylist was never paid and no payout is scheduled. */
    public List<BookAppointmentEntity> paidWithNoPayoutScheduled() {
        return bookAppointmentRepo.findByStatusAndPaymentStatusAndStripeTransferIdIsNullAndPayoutDueAtIsNull("0", "CAPTURED");
    }
}
