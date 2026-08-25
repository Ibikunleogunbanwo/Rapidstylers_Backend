package com.macrotel.rapidstylers.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OutboxEventService {

    private final OutboxEventRepo outboxEventRepo;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.topics.domain-events:rapidstylers.domain-events}")
    private String domainEventsTopic;

    public OutboxEventService(OutboxEventRepo outboxEventRepo, ObjectMapper objectMapper) {
        this.outboxEventRepo = outboxEventRepo;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appointmentNotification(BookAppointmentEntity appointment, String eventLabel,
                                        String customerHeadline, String customerDetail,
                                        String stylerHeadline, String stylerDetail) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventType(eventTypeForAppointment(eventLabel));
        event.setTopic(domainEventsTopic);
        event.setAggregateType("APPOINTMENT");
        event.setAggregateId(nullSafe(appointment.getAppointmentId()));
        event.setPayload(toJson(appointmentPayload(appointment, eventLabel, customerHeadline,
                customerDetail, stylerHeadline, stylerDetail)));
        outboxEventRepo.save(event);
    }

    /** Emits a PAYMENT_SUCCEEDED event so both parties receive a payment receipt by email. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentSucceeded(BookAppointmentEntity appointment) {
        Map<String, Object> payload = appointmentPayload(appointment, "Paid",
                "Payment received", "Your payment has been received. Thank you!",
                "Payment received", "The client's payment has been received.");
        payload.put("paymentAmount", nullSafe(appointment.getPaymentAmount()));
        payload.put("paymentStatus", nullSafe(appointment.getPaymentStatus()));
        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventType(OutboxEventType.PAYMENT_SUCCEEDED);
        event.setTopic(domainEventsTopic);
        event.setAggregateType("APPOINTMENT");
        event.setAggregateId(nullSafe(appointment.getAppointmentId()));
        event.setPayload(toJson(payload));
        outboxEventRepo.save(event);
    }

    private Map<String, Object> appointmentPayload(BookAppointmentEntity appointment, String eventLabel,
                                                   String customerHeadline, String customerDetail,
                                                   String stylerHeadline, String stylerDetail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appointmentId", nullSafe(appointment.getAppointmentId()));
        payload.put("eventLabel", nullSafe(eventLabel));
        payload.put("customerId", nullSafe(appointment.getUserId()));
        payload.put("stylerId", nullSafe(appointment.getStylerId()));
        payload.put("subServiceId", nullSafe(appointment.getSubServiceId()));
        payload.put("appointmentDate", nullSafe(appointment.getAppointmentDate()));
        payload.put("arrivalTime", nullSafe(appointment.getArrivalTime()));
        payload.put("servicePrice", nullSafe(appointment.getServicePrice()));
        payload.put("travelFee", nullSafe(appointment.getTravelFee()));
        payload.put("totalPrice", nullSafe(appointment.getPrice()));
        payload.put("customerHeadline", nullSafe(customerHeadline));
        payload.put("customerDetail", nullSafe(customerDetail));
        payload.put("stylerHeadline", nullSafe(stylerHeadline));
        payload.put("stylerDetail", nullSafe(stylerDetail));
        return payload;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize outbox event payload", ex);
        }
    }

    private OutboxEventType eventTypeForAppointment(String eventLabel) {
        if ("Confirmed".equalsIgnoreCase(eventLabel)) return OutboxEventType.BOOKING_CONFIRMED;
        if ("Declined".equalsIgnoreCase(eventLabel)) return OutboxEventType.BOOKING_DECLINED;
        if ("Completed".equalsIgnoreCase(eventLabel)) return OutboxEventType.BOOKING_COMPLETED;
        if ("Cancelled".equalsIgnoreCase(eventLabel)) return OutboxEventType.BOOKING_CANCELLED;
        return OutboxEventType.BOOKING_REQUESTED;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
