package com.macrotel.rapidstylers.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.SubServiceEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public class NotificationEventConsumer {

    private final EmailConfig emailConfig;
    private final UserRepo userRepo;
    private final StylerRepo stylerRepo;
    private final SubServiceRepo subServiceRepo;
    private final ObjectMapper objectMapper;

    public NotificationEventConsumer(EmailConfig emailConfig, UserRepo userRepo, StylerRepo stylerRepo,
                                     SubServiceRepo subServiceRepo) {
        this.emailConfig = emailConfig;
        this.userRepo = userRepo;
        this.stylerRepo = stylerRepo;
        this.subServiceRepo = subServiceRepo;
        this.objectMapper = new ObjectMapper();
    }

    @KafkaListener(
            topics = "${app.kafka.topics.domain-events:rapidstylers.domain-events}",
            groupId = "${app.kafka.consumers.notifications.group-id:rapidstylers-notification-service}",
            autoStartup = "${app.kafka.consumers.notifications.enabled:true}"
    )
    public void handleBookingNotification(String payload,
                                          @Header(name = "outbox-id", required = false) String outboxId,
                                          @Header(name = "outbox-event-id", required = false) String eventId,
                                          @Header(name = "outbox-event-type", required = false) String eventType,
                                          Acknowledgment acknowledgment) {
        if (eventType != null && eventType.startsWith("PAYMENT_")) {
            Map<String, String> event = readPayload(payload);
            Optional<SubServiceEntity> service = service(event);
            sendToBoth(event, service, "RapidStylers - Payment receipt", this::paymentBody);
            acknowledge(acknowledgment);
            return;
        }
        if (eventType != null && !eventType.startsWith("BOOKING_")) {
            acknowledge(acknowledgment);
            return;
        }

        Map<String, String> event = readPayload(payload);
        Optional<SubServiceEntity> service = service(event);
        String subject = "RapidStylers - Appointment " + event.getOrDefault("eventLabel", "Update");
        sendToBoth(event, service, subject, this::bookingBody);

        acknowledge(acknowledgment);
    }

    private Map<String, String> readPayload(String payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<Map<String, String>>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to parse notification event payload", ex);
        }
    }

    private Optional<SubServiceEntity> service(Map<String, String> event) {
        try {
            String serviceId = event.get("subServiceId");
            if (serviceId == null || serviceId.isBlank()) {
                return Optional.empty();
            }
            return subServiceRepo.isServiceExistById(event.getOrDefault("stylerId", ""), Long.parseLong(serviceId));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private void sendToBoth(Map<String, String> event, Optional<SubServiceEntity> service, String subject,
                            BodyBuilder bodyBuilder) {
        Optional<UserEntity> user = userRepo.findByUserId(event.getOrDefault("customerId", ""));
        Optional<StylerEntity> styler = stylerRepo.findByStylerId(event.getOrDefault("stylerId", ""));

        user.map(UserEntity::getEmailAddress)
                .filter(email -> !email.isBlank())
                .ifPresent(email -> emailConfig.sendSimpleMail(email, subject, bodyBuilder.build(
                        event.get("customerHeadline"), event.get("customerDetail"), service, event)));

        styler.map(StylerEntity::getEmailAddress)
                .filter(email -> !email.isBlank())
                .ifPresent(email -> emailConfig.sendSimpleMail(email, subject, bodyBuilder.build(
                        event.get("stylerHeadline"), event.get("stylerDetail"), service, event)));
    }

    private String bookingBody(String headline, String detail, Optional<SubServiceEntity> service,
                               Map<String, String> event) {
        String serviceName = service.map(SubServiceEntity::getName).orElse("Appointment");
        return "<p>" + value(headline, "Appointment update") + "</p>"
                + "<p>" + value(detail, "Your booking has been updated.") + "</p>"
                + "<p><strong>Service:</strong> " + serviceName + "</p>"
                + "<p><strong>Date:</strong> " + value(event.get("appointmentDate"), "TBC") + "</p>"
                + "<p><strong>Arrival time:</strong> " + displayArrivalTime(event.get("arrivalTime")) + "</p>"
                + "<p><strong>Service price:</strong> $" + value(event.get("servicePrice"), "0.00") + "</p>"
                + "<p><strong>Travel fee:</strong> $" + value(event.get("travelFee"), "0.00") + "</p>"
                + "<p><strong>Total:</strong> $" + value(event.get("totalPrice"), "0.00") + "</p>";
    }

    private String paymentBody(String headline, String detail, Optional<SubServiceEntity> service,
                               Map<String, String> event) {
        String serviceName = service.map(SubServiceEntity::getName).orElse("Appointment");
        String paid = value(event.get("paymentAmount"), value(event.get("totalPrice"), "0.00"));
        return "<p>" + value(headline, "Payment received") + "</p>"
                + "<p>" + value(detail, "Your payment has been received.") + "</p>"
                + "<p><strong>Service:</strong> " + serviceName + "</p>"
                + "<p><strong>Date:</strong> " + value(event.get("appointmentDate"), "TBC") + "</p>"
                + "<p><strong>Arrival time:</strong> " + displayArrivalTime(event.get("arrivalTime")) + "</p>"
                + "<p><strong>Service price:</strong> $" + value(event.get("servicePrice"), "0.00") + "</p>"
                + "<p><strong>Travel fee:</strong> $" + value(event.get("travelFee"), "0.00") + "</p>"
                + "<p><strong>Total paid: $" + paid + "</strong></p>";
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * The wire format for arrival times is canonical 24-hour HH:mm (aligned
     * with the availability API); emails render it as a friendly 12-hour clock
     * time. Values already in 12-hour form (legacy rows) pass through.
     */
    private String displayArrivalTime(String raw) {
        if (raw == null || raw.isBlank()) return "TBC";
        String value = raw.trim();
        java.time.LocalTime time;
        try {
            time = java.time.LocalTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ENGLISH));
        } catch (java.time.format.DateTimeParseException ex) {
            try {
                time = java.time.LocalTime.parse(value.toUpperCase(), java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH));
                return time.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH));
            } catch (java.time.format.DateTimeParseException legacy) {
                return value;
            }
        }
        return time.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH));
    }

    @FunctionalInterface
    private interface BodyBuilder {
        String build(String headline, String detail, Optional<SubServiceEntity> service, Map<String, String> event);
    }

    private void acknowledge(Acknowledgment acknowledgment) {
        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
    }
}
