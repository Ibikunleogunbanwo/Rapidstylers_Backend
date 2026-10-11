package com.macrotel.rapidstylers.booking.repo;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import javax.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
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
}
