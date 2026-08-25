package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookAppointmentRepo extends JpaRepository<BookAppointmentEntity, Long> {
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

    Optional<BookAppointmentEntity> findByPaymentIntentId(String paymentIntentId);

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
