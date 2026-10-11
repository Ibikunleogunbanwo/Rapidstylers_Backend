package com.macrotel.rapidstylers.booking.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.pojo.BookAppointmentData;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A retried booking request must get the original booking back, never a second
 * booking, and a key may only ever stand for the one request it was first used with.
 */
class BookingRetriesTest {

    private static final String KEY = "6f1d2c3b-key";

    private BookingRetries retries;
    private BookingService bookingService;
    private BookAppointmentRepo repo;

    @BeforeEach
    void setUp() {
        retries = new BookingRetries();
        bookingService = mock(BookingService.class);
        repo = mock(BookAppointmentRepo.class);
        ReflectionTestUtils.setField(retries, "bookingService", bookingService);
        ReflectionTestUtils.setField(retries, "bookAppointmentRepo", repo);
        when(repo.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void aRequestWithoutAKeyIsBookedAsBefore() {
        BookAppointmentData data = request();
        data.setIdempotencyKey("  ");
        BaseResponse booked = BookingService.bookedResponse("APPT-1");
        when(bookingService.bookAppointment(data)).thenReturn(booked);

        assertSame(booked, retries.book(data));
        verify(repo, never()).findByIdempotencyKey(anyString());
    }

    @Test
    void theFirstRequestBooksAndRecordsItsFingerprint() {
        BookAppointmentData data = request();
        when(bookingService.bookAppointment(eq(data), anyString())).thenReturn(BookingService.bookedResponse("APPT-1"));

        BaseResponse response = retries.book(data);

        assertEquals("200", response.getStatusCode());
        verify(bookingService).bookAppointment(data, BookingRetries.fingerprint(data));
    }

    @Test
    void aLostAnswerIsReplayedWithoutBookingAgain() {
        BookAppointmentData data = request();
        when(repo.findByIdempotencyKey(KEY)).thenReturn(Optional.of(booked(data)));

        BaseResponse response = retries.book(data);

        assertEquals("200", response.getStatusCode());
        assertEquals("Appointment booked successfully", response.getMessage());
        assertEquals(java.util.Map.of("appointmentId", "APPT-1"), response.getData(),
                "the replay names the booking the first request made");
        verify(bookingService, never()).bookAppointment(any(), any());
        verify(bookingService, never()).bookAppointment(any());
    }

    @Test
    void aRetryWithAFreshCardStillReplays() {
        BookAppointmentData first = request();
        first.setPaymentMethodId("pm_first");
        BookAppointmentData retry = request();
        retry.setPaymentMethodId("pm_second");
        when(repo.findByIdempotencyKey(KEY)).thenReturn(Optional.of(booked(first)));

        assertEquals("200", retries.book(retry).getStatusCode());
        verify(bookingService, never()).bookAppointment(any(), any());
    }

    @Test
    void theSameKeyForADifferentBookingIsRefused() {
        BookAppointmentData first = request();
        when(repo.findByIdempotencyKey(KEY)).thenReturn(Optional.of(booked(first)));
        BookAppointmentData other = request();
        other.setArrivalTime("11:00");

        BaseResponse response = retries.book(other);

        assertEquals("422", response.getStatusCode());
        assertEquals(BookingRetries.KEY_REUSED, response.getMessage());
        verify(bookingService, never()).bookAppointment(any(), any());
    }

    @Test
    void anotherAccountCannotUseSomeoneElsesKey() {
        BookAppointmentData mine = request();
        when(repo.findByIdempotencyKey(KEY)).thenReturn(Optional.of(booked(mine)));
        BookAppointmentData theirs = request();
        theirs.setUserId("CUSTOMER2");

        BaseResponse response = retries.book(theirs);

        assertEquals("422", response.getStatusCode());
        verify(bookingService, never()).bookAppointment(any(), any());
    }

    @Test
    void aTwinThatCommittedFirstIsReplayedAfterTheUniqueKeyCollision() {
        BookAppointmentData data = request();
        when(repo.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(booked(data)));
        when(bookingService.bookAppointment(eq(data), anyString()))
                .thenThrow(new DataIntegrityViolationException("uk_appointments_idempotency_key"));

        assertEquals("200", retries.book(data).getStatusCode());
    }

    @Test
    void aTwinThatHeldTheStylistLockFirstIsReplayed() {
        BookAppointmentData data = request();
        when(repo.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(booked(data)));
        when(bookingService.bookAppointment(eq(data), anyString()))
                .thenReturn(Responses.errorResponse(new BaseResponse(true), BookingService.ALREADY_SUBMITTED));

        assertEquals("200", retries.book(data).getStatusCode());
    }

    @Test
    void aSlotConflictWithNoBookingForTheKeyStaysAConflict() {
        BookAppointmentData data = request();
        DataIntegrityViolationException conflict = new DataIntegrityViolationException("uk_slot");
        when(bookingService.bookAppointment(eq(data), anyString())).thenThrow(conflict);

        assertSame(conflict, assertThrows(DataIntegrityViolationException.class, () -> retries.book(data)));
    }

    @Test
    void aFailedRequestIsNotConfirmedAndTheKeyStaysUsable() {
        BookAppointmentData data = request();
        BaseResponse declined = Responses.errorResponse(new BaseResponse(true), "Your card was declined");
        when(bookingService.bookAppointment(eq(data), anyString()))
                .thenReturn(declined)
                .thenReturn(BookingService.bookedResponse("APPT-1"));

        assertSame(declined, retries.book(data));
        // Nothing was saved under the key, so the next try books for real.
        assertEquals("200", retries.book(data).getStatusCode());
    }

    @Test
    void anOverlongKeyIsRefused() {
        BookAppointmentData data = request();
        data.setIdempotencyKey("k".repeat(BookingRetries.MAX_KEY_LENGTH + 1));

        assertEquals("400", retries.book(data).getStatusCode());
        verify(bookingService, never()).bookAppointment(any(), any());
    }

    @Test
    void theFingerprintIgnoresSpacingButNotTheBooking() {
        BookAppointmentData a = request();
        BookAppointmentData spaced = request();
        spaced.setStylerId(" STYLER1 ");
        BookAppointmentData later = request();
        later.setArrivalTime("11:00");

        assertEquals(BookingRetries.fingerprint(a), BookingRetries.fingerprint(spaced));
        assertNotEquals(BookingRetries.fingerprint(a), BookingRetries.fingerprint(later));
    }

    private BookAppointmentData request() {
        BookAppointmentData data = new BookAppointmentData();
        data.setUserId("CUSTOMER1");
        data.setStylerId("STYLER1");
        data.setSubServiceId("1");
        data.setAppointmentDate("2030-08-24");
        data.setArrivalTime("09:30");
        data.setNoOfPeople("1");
        data.setServiceTime("visitBarber");
        data.setIdempotencyKey(KEY);
        return data;
    }

    private BookAppointmentEntity booked(BookAppointmentData data) {
        BookAppointmentEntity booking = new BookAppointmentEntity();
        booking.setAppointmentId("APPT-1");
        booking.setUserId(data.getUserId());
        booking.setIdempotencyKey(KEY);
        booking.setIdempotencyFingerprint(BookingRetries.fingerprint(data));
        return booking;
    }
}
