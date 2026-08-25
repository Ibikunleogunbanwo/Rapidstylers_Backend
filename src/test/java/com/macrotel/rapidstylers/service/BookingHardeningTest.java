package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.BookAppointmentData;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.BookingSlotLockRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingHardeningTest {

    private AppService appService;
    private BookAppointmentRepo appointmentRepo;
    private BookingSlotLockRepo slotLockRepo;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        appointmentRepo = mock(BookAppointmentRepo.class);
        slotLockRepo = mock(BookingSlotLockRepo.class);
        appService.bookAppointmentRepo = appointmentRepo;
        appService.bookingSlotLockRepo = slotLockRepo;
    }

    @Test
    void newAppointmentStoresCanonicalDateStartAndEndFields() {
        BookAppointmentData data = bookingData("2030-08-24", "09:30");

        BookAppointmentEntity appointment = new BookAppointmentEntity(data);

        assertEquals(LocalDate.of(2030, 8, 24), appointment.getAppointmentDateValue());
        assertEquals(LocalTime.of(9, 30), appointment.getAppointmentStartTime());
        assertEquals(LocalTime.of(10, 30), appointment.getAppointmentEndTime());
    }

    @Test
    void selectedServiceDurationIsSnapshotIntoAppointmentEndTime() {
        BookAppointmentData data = bookingData("2030-08-24", "09:30");

        BookAppointmentEntity appointment = new BookAppointmentEntity(data, 90);

        assertEquals(90, appointment.getDurationMinutes());
        assertEquals(LocalTime.of(11, 0), appointment.getAppointmentEndTime());
    }

    @Test
    void malformedTemporalInputIsRejectedByTheEntityNormalizer() {
        BookAppointmentData invalidDate = bookingData("2030-02-30", "09:00");
        BookAppointmentData invalidTime = bookingData("2030-08-24", "09:75");

        assertThrows(java.time.format.DateTimeParseException.class,
                () -> new BookAppointmentEntity(invalidDate));
        assertThrows(IllegalArgumentException.class,
                () -> new BookAppointmentEntity(invalidTime));
    }

    @Test
    void stylistCannotCompleteBeforeScheduledStart() {
        BookAppointmentEntity appointment = appointment("2030-08-24", "09:00", "3");
        when(appointmentRepo.findByAppointmentId("APPT1")).thenReturn(Optional.of(appointment));

        BaseResponse response = appService.completeAppointment("STYLER1", "APPT1");

        assertEquals("400", response.getStatusCode());
        assertTrue(response.getMessage().contains("after its scheduled start"));
        verify(appointmentRepo, never()).save(appointment);
    }

    @Test
    void transitionsEnforceTheCorrectOwnerSide() {
        BookAppointmentEntity appointment = appointment("2030-08-24", "09:00", "1");
        when(appointmentRepo.findByAppointmentId("APPT1")).thenReturn(Optional.of(appointment));

        BaseResponse stylistAttemptToCancel = appService.cancelAppointment("STYLER1", "APPT1");
        BaseResponse customerAttemptToAccept = appService.acceptAppointment("CUSTOMER1", "APPT1");

        assertEquals("400", stylistAttemptToCancel.getStatusCode());
        assertEquals("400", customerAttemptToAccept.getStatusCode());
        verify(appointmentRepo, never()).save(appointment);
    }

    private BookAppointmentData bookingData(String date, String time) {
        BookAppointmentData data = new BookAppointmentData();
        data.setUserId("CUSTOMER1");
        data.setStylerId("STYLER1");
        data.setSubServiceId("1");
        data.setAppointmentDate(date);
        data.setArrivalTime(time);
        data.setPrice("100");
        data.setNoOfPeople("1");
        return data;
    }

    @Test
    void legacyTwelveHourArrivalRowStillParsesToCanonicalStart() {
        // Rows written before the 24-hour alignment (h:mm am/pm) must keep
        // parsing so availability/conflict checks never hard-block on them.
        BookAppointmentData data = bookingData("2030-08-24", "9:30 am");

        BookAppointmentEntity appointment = new BookAppointmentEntity(data);

        assertEquals(LocalTime.of(9, 30), appointment.getAppointmentStartTime());
        assertEquals(LocalTime.of(10, 30), appointment.getAppointmentEndTime());
    }

    private BookAppointmentEntity appointment(String date, String time, String status) {
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setAppointmentId("APPT1");
        appointment.setUserId("CUSTOMER1");
        appointment.setStylerId("STYLER1");
        appointment.setAppointmentDate(date);
        appointment.setArrivalTime(time);
        appointment.setAppointmentDateValue(LocalDate.parse(date));
        appointment.setAppointmentStartTime(LocalTime.parse("09:00"));
        appointment.setAppointmentEndTime(LocalTime.parse("10:00"));
        appointment.setStatus(status);
        appointment.setSubServiceId("1");
        return appointment;
    }
}
