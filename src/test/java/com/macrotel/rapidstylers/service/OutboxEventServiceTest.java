package com.macrotel.rapidstylers.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.outbox.OutboxEventEntity;
import com.macrotel.rapidstylers.outbox.OutboxEventRepo;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.outbox.OutboxEventType;
import com.macrotel.rapidstylers.outbox.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxEventServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxEventRepo outboxEventRepo;
    private OutboxEventService outboxEventService;

    @BeforeEach
    void setUp() {
        outboxEventRepo = mock(OutboxEventRepo.class);
        outboxEventService = new OutboxEventService(outboxEventRepo, objectMapper);
        ReflectionTestUtils.setField(outboxEventService, "domainEventsTopic", "rapidstylers.domain-events");
    }

    @Test
    void appointmentNotificationStoresPendingBookingEventWithRoleSpecificPayload() throws Exception {
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setAppointmentId("APPT1");
        appointment.setUserId("USER1");
        appointment.setStylerId("STYLER1");
        appointment.setAppointmentDate("2030-08-24");
        appointment.setArrivalTime("09:30");
        appointment.setPrice("125.00");
        appointment.setServicePrice("100.00");
        appointment.setTravelFee("25.00");

        outboxEventService.appointmentNotification(
                appointment,
                "Request",
                "Booking request received",
                "Your request is waiting for confirmation.",
                "New booking request",
                "A client requested an appointment.");

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepo).save(captor.capture());
        OutboxEventEntity saved = captor.getValue();

        assertNotNull(saved.getEventId());
        assertEquals(OutboxEventType.BOOKING_REQUESTED, saved.getEventType());
        assertEquals(OutboxStatus.PENDING, saved.getStatus());
        assertEquals("APPOINTMENT", saved.getAggregateType());
        assertEquals("APPT1", saved.getAggregateId());
        assertEquals("rapidstylers.domain-events", saved.getTopic());

        Map<String, Object> payload = objectMapper.readValue(saved.getPayload(), new TypeReference<Map<String, Object>>() {});
        assertEquals("APPT1", payload.get("appointmentId"));
        assertEquals("Request", payload.get("eventLabel"));
        assertEquals("USER1", payload.get("customerId"));
        assertEquals("STYLER1", payload.get("stylerId"));
        assertEquals("Booking request received", payload.get("customerHeadline"));
        assertEquals("New booking request", payload.get("stylerHeadline"));
        assertEquals("125.00", payload.get("totalPrice"));
        assertEquals("25.00", payload.get("travelFee"));
    }

    @Test
    void paymentSucceededStoresPendingPaymentEventWithAmount() throws Exception {
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setAppointmentId("APPT2");
        appointment.setUserId("USER1");
        appointment.setStylerId("STYLER1");
        appointment.setAppointmentDate("2030-08-24");
        appointment.setArrivalTime("09:30");
        appointment.setPrice("125.00");
        appointment.setServicePrice("100.00");
        appointment.setTravelFee("25.00");
        appointment.setPaymentAmount("125.00");
        appointment.setPaymentStatus("CAPTURED");

        outboxEventService.paymentSucceeded(appointment);

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepo).save(captor.capture());
        OutboxEventEntity saved = captor.getValue();

        assertEquals(OutboxEventType.PAYMENT_SUCCEEDED, saved.getEventType());
        assertEquals(OutboxStatus.PENDING, saved.getStatus());
        assertEquals("APPT2", saved.getAggregateId());

        Map<String, Object> payload = objectMapper.readValue(saved.getPayload(), new TypeReference<Map<String, Object>>() {});
        assertEquals("APPT2", payload.get("appointmentId"));
        assertEquals("125.00", payload.get("paymentAmount"));
        assertEquals("CAPTURED", payload.get("paymentStatus"));
        assertEquals("Payment received", payload.get("customerHeadline"));
    }
}
