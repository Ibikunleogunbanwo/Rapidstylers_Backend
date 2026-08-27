package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.dto.AppointmentDTO;
import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.entity.RefundEntity;
import com.macrotel.rapidstylers.repo.RefundRepo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DTOServiceRefundTest {

    private DTOService dtoService() {
        DTOService dtoService = new DTOService();
        // Other repos stay null — appointmentDTO tolerates that and skips lookups.
        return dtoService;
    }

    private BookAppointmentEntity appointment(String appointmentId, String status) {
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setAppointmentId(appointmentId);
        appointment.setStatus(status);
        return appointment;
    }

    @Test
    void completedRefundIsAttachedToAppointmentDto() {
        DTOService dtoService = dtoService();
        RefundRepo refundRepo = mock(RefundRepo.class);
        ReflectionTestUtils.setField(dtoService, "refundRepo", refundRepo);
        RefundEntity refund = new RefundEntity();
        refund.setRefundId("RFND-1");
        refund.setStatus("COMPLETED");
        refund.setAmount("125.00");
        refund.setCompletedAt("2026-08-27 10:00:00");
        when(refundRepo.findByAppointmentId("APPT1")).thenReturn(List.of(refund));

        AppointmentDTO dto = dtoService.appointmentDTO(appointment("APPT1", "4"));

        assertEquals("RFND-1", dto.getRefundId());
        assertEquals("COMPLETED", dto.getRefundStatus());
        assertEquals("125.00", dto.getRefundAmount());
        assertEquals("2026-08-27 10:00:00", dto.getRefundCompletedAt());
    }

    @Test
    void noRefundLeavesRefundFieldsNull() {
        DTOService dtoService = dtoService();
        RefundRepo refundRepo = mock(RefundRepo.class);
        ReflectionTestUtils.setField(dtoService, "refundRepo", refundRepo);
        when(refundRepo.findByAppointmentId("APPT1")).thenReturn(List.of());

        AppointmentDTO dto = dtoService.appointmentDTO(appointment("APPT1", "3"));

        assertNull(dto.getRefundStatus());
        assertNull(dto.getRefundAmount());
    }

    @Test
    void failedRefundAttemptIsNotExposedAsRefunded() {
        DTOService dtoService = dtoService();
        RefundRepo refundRepo = mock(RefundRepo.class);
        ReflectionTestUtils.setField(dtoService, "refundRepo", refundRepo);
        RefundEntity refund = new RefundEntity();
        refund.setRefundId("RFND-2");
        refund.setStatus("FAILED");
        when(refundRepo.findByAppointmentId("APPT1")).thenReturn(List.of(refund));

        AppointmentDTO dto = dtoService.appointmentDTO(appointment("APPT1", "4"));

        assertNull(dto.getRefundStatus());
    }
}
