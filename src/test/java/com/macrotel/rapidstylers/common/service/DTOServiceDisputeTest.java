package com.macrotel.rapidstylers.common.service;

import com.macrotel.rapidstylers.booking.dto.AppointmentDTO;
import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The booking shows the customer's dispute and the admin's decision on it. */
class DTOServiceDisputeTest {

    @Test
    void resolvedDisputeCarriesReasonAndDecision() {
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setAppointmentId("APPT1");
        appointment.setStatus("0");
        appointment.setServiceDisputeStatus("RESOLVED");
        appointment.setServiceDisputeReason("Stylist never arrived");
        appointment.setServiceDisputedAt(LocalDateTime.of(2026, 10, 9, 18, 0));
        appointment.setServiceDisputeResolutionNote("Full refund issued");
        appointment.setServiceDisputeResolvedAt(LocalDateTime.of(2026, 10, 10, 9, 30));

        AppointmentDTO dto = new DTOService().appointmentDTO(appointment);

        assertEquals("RESOLVED", dto.getServiceDisputeStatus());
        assertEquals("Stylist never arrived", dto.getServiceDisputeReason());
        assertEquals(LocalDateTime.of(2026, 10, 9, 18, 0), dto.getServiceDisputedAt());
        assertEquals("Full refund issued", dto.getServiceDisputeResolutionNote());
        assertEquals(LocalDateTime.of(2026, 10, 10, 9, 30), dto.getServiceDisputeResolvedAt());
    }

    @Test
    void undisputedBookingHasNoDisputeDetails() {
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setAppointmentId("APPT2");
        appointment.setStatus("0");

        AppointmentDTO dto = new DTOService().appointmentDTO(appointment);

        assertNull(dto.getServiceDisputeReason());
        assertNull(dto.getServiceDisputeResolutionNote());
        assertNull(dto.getServiceDisputeResolvedAt());
    }
}
