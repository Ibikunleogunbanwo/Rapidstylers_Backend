package com.macrotel.rapidstylers.booking.repo;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import javax.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookAppointmentRepo extends JpaRepository<BookAppointmentEntity, Long>,
        JpaSpecificationExecutor<BookAppointmentEntity> {
    List<BookAppointmentEntity> findByUserId(String userId);

    @Query(value = "SELECT b FROM BookAppointmentEntity b WHERE b.userId =:userId AND b.status ='1'")
    List<BookAppointmentEntity> userPendingAppointment(@Param("userId") String userId);

    List<BookAppointmentEntity> findByStylerId(String stylerId);

    List<BookAppointmentEntity> findByStylerIdAndAppointmentDate(String stylerId, String appointmentDate);

    List<BookAppointmentEntity> findByStylerIdAndAppointmentDateValue(String stylerId, java.time.LocalDate appointmentDate);

    List<BookAppointmentEntity> findByUserIdAndStylerIdAndAppointmentDateValueAndAppointmentStartTimeAndStatusIn(
            String userId, String stylerId, java.time.LocalDate appointmentDate,
            java.time.LocalTime appointmentStartTime, List<String> statuses);

    Optional<BookAppointmentEntity> findByAppointmentId(String appointmentId);

    /** Completed, charged bookings whose stylist payout is due and not yet sent. */
    List<BookAppointmentEntity> findTop50ByStatusAndPaymentStatusAndStripeTransferIdIsNullAndPayoutDueAtLessThanEqual(
            String status, String paymentStatus, LocalDateTime dueBy);

    List<BookAppointmentEntity> findByServiceDisputeStatus(String serviceDisputeStatus);

    /** A stylist's payouts held for one reason, such as no payout account yet. */
    List<BookAppointmentEntity> findByStylerIdAndPayoutHoldReason(String stylerId, String payoutHoldReason);

    boolean existsByStylerIdAndPayoutHoldReason(String stylerId, String payoutHoldReason);

    /** Completed, charged bookings whose stylist was never paid and no payout is scheduled. */
    List<BookAppointmentEntity> findByStatusAndPaymentStatusAndStripeTransferIdIsNullAndPayoutDueAtIsNull(
            String status, String paymentStatus);

    Optional<BookAppointmentEntity> findByIdempotencyKey(String idempotencyKey);

    /**
     * Pessimistic write lock on the appointment row. Status transitions (and
     * the refunds they trigger) are serialized per appointment, so concurrent
     * cancels can never refund the same captured payment twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BookAppointmentEntity b WHERE b.appointmentId = :appointmentId")
    Optional<BookAppointmentEntity> findByAppointmentIdForUpdate(@Param("appointmentId") String appointmentId);

    Optional<BookAppointmentEntity> findByPaymentIntentId(String paymentIntentId);

    List<BookAppointmentEntity> findByPaymentStatusAndPaymentAuthorizationDueAtBefore(
            String paymentStatus, java.time.LocalDateTime dueAt);

    /** Active bookings (pending or accepted) for a styler at a given date/time — used to reject double-booking. */
    @Query("SELECT b FROM BookAppointmentEntity b WHERE b.stylerId =:stylerId AND b.appointmentDate =:appointmentDate AND b.arrivalTime =:arrivalTime AND b.status IN ('1','3')")
    List<BookAppointmentEntity> findConflictingBooking(@Param("stylerId") String stylerId,
                                                       @Param("appointmentDate") String appointmentDate,
                                                       @Param("arrivalTime") String arrivalTime);

    /** Same customer + styler + slot already requested — guards double-clicks and duplicate submissions. */
    @Query("SELECT b FROM BookAppointmentEntity b WHERE b.userId =:userId AND b.stylerId =:stylerId AND b.appointmentDate =:appointmentDate AND b.arrivalTime =:arrivalTime AND b.status IN ('1','3')")
    List<BookAppointmentEntity> findDuplicateBooking(@Param("userId") String userId,
                                                    @Param("stylerId") String stylerId,
                                                    @Param("appointmentDate") String appointmentDate,
                                                    @Param("arrivalTime") String arrivalTime);

    /** Per customer: [userId, number of bookings, latest appointment date]. */
    @Query("SELECT b.userId, COUNT(b), MAX(b.appointmentDateValue) FROM BookAppointmentEntity b "
            + "WHERE b.userId IN :userIds GROUP BY b.userId")
    List<Object[]> bookingStatsForUsers(@Param("userIds") java.util.Collection<String> userIds);

    /** How many bookings a customer has ever made (the sign-up funnel's "first booking"). */
    long countByUserId(String userId);

    /**
     * Accepted bookings with no reminder yet whose date falls in [from, to],
     * soonest first. The caller works out, per stylist zone, which are due.
     */
    @Query("SELECT b FROM BookAppointmentEntity b WHERE b.status = '3' AND b.reminderSentAt IS NULL "
            + "AND b.appointmentDateValue BETWEEN :from AND :to ORDER BY b.appointmentDateValue, b.appointmentStartTime")
    List<BookAppointmentEntity> findReminderCandidates(@Param("from") java.time.LocalDate from,
                                                       @Param("to") java.time.LocalDate to,
                                                       org.springframework.data.domain.Pageable page);

    /**
     * Claims the reminder: sets reminder_sent_at only while it is still empty
     * and the booking is still accepted and not under a payment dispute.
     * Returns 1 for the one caller that won, 0 for everyone else.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE BookAppointmentEntity b SET b.reminderSentAt = :sentAt WHERE b.id = :id "
            + "AND b.reminderSentAt IS NULL AND b.status = '3' "
            + "AND (b.paymentStatus IS NULL OR b.paymentStatus NOT LIKE 'DISPUTE%')")
    int claimReminder(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);
}
