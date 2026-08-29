package com.macrotel.rapidstylers.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.service.NotificationDedupService;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.SubServiceEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

@Component
public class NotificationEventConsumer {

    private static final Logger LOG = Logger.getLogger(NotificationEventConsumer.class.getName());

    private final EmailConfig emailConfig;
    private final UserRepo userRepo;
    private final StylerRepo stylerRepo;
    private final SubServiceRepo subServiceRepo;
    private final NotificationDedupService dedupService;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.kafka.topics.domain-events-retry:rapidstylers.domain-events.retry}")
    private String retryTopic;

    @Value("${app.kafka.topics.domain-events-dlq:rapidstylers.domain-events.dlq}")
    private String dlqTopic;

    @Value("${app.kafka.consumers.notifications.max-retries:3}")
    private int maxRetries;

    public NotificationEventConsumer(EmailConfig emailConfig, UserRepo userRepo, StylerRepo stylerRepo,
                                     SubServiceRepo subServiceRepo, KafkaTemplate<String, String> kafkaTemplate,
                                     NotificationDedupService dedupService) {
        this.emailConfig = emailConfig;
        this.userRepo = userRepo;
        this.stylerRepo = stylerRepo;
        this.subServiceRepo = subServiceRepo;
        this.kafkaTemplate = kafkaTemplate;
        this.dedupService = dedupService;
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
                                          @Header(name = "retry-count", required = false) String retryCountHeader,
                                          Acknowledgment acknowledgment) {
        int retryCount = parseRetryCount(retryCountHeader);

        // Consumer idempotency: a redelivered event (crash before ack) must
        // not email the customer twice. The claim is released on failure so
        // the retry path can legitimately reprocess.
        if (!dedupService.tryClaim(eventId)) {
            LOG.info("Skipping already-delivered notification event " + eventId);
            acknowledge(acknowledgment);
            return;
        }

        try {
            if (eventType != null && eventType.startsWith("CUSTOMER_")) {
                Map<String, String> event = readPayload(payload);
                String email = event.getOrDefault("emailAddress", "");
                if (!email.isBlank()) {
                    String firstName = event.getOrDefault("firstname", "");
                    String greeting = firstName.isBlank() ? "Welcome to RapidStylers" : "Welcome to RapidStylers, " + firstName;
                    emailConfig.sendSimpleMail(email, "Welcome to RapidStylers",
                            "<p>" + greeting + "!</p>"
                            + "<p>Your account is ready. Complete your profile and book your first "
                            + "appointment with a top-rated beauty professional.</p>"
                            + "<p>– The RapidStylers Team</p>");
                }
                acknowledge(acknowledgment);
                return;
            }
            if (eventType != null && eventType.startsWith("PAYMENT_")) {
                Map<String, String> event = readPayload(payload);
                Optional<SubServiceEntity> service = service(event);
                sendToBoth(event, service, "RapidStylers - Payment receipt", this::paymentBody);
                acknowledge(acknowledgment);
                return;
            }
            if (eventType != null && eventType.startsWith("REFUND_")) {
                Map<String, String> event = readPayload(payload);
                Optional<SubServiceEntity> service = service(event);
                sendToBoth(event, service, "RapidStylers - Refund notice", this::refundBody);
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

        } catch (Exception ex) {
            LOG.warning("Notification consumer error (attempt " + (retryCount + 1) + "): " + ex.getMessage());
            if (acknowledgment != null) {
                acknowledgment.acknowledge(); // Ack so Kafka doesn't redeliver forever
            }
            // Free the claim so the retry path can reprocess this event.
            dedupService.release(eventId);
            if (retryCount < maxRetries) {
                // Route to retry topic with incremented retry count
                sendToRetryTopic(payload, outboxId, eventId, eventType, retryCount + 1);
            } else {
                // Exhausted retries — route to DLQ
                sendToDlq(payload, outboxId, eventId, eventType, ex.getMessage());
            }
        }
    }

    /**
     * Retried deliveries are reprocessed through the same handler; the
     * dedup claim was released when the first attempt failed, so the retry
     * actually re-sends instead of being skipped.
     */
    @KafkaListener(
            topics = "${app.kafka.topics.domain-events-retry:rapidstylers.domain-events.retry}",
            groupId = "${app.kafka.consumers.notifications.group-id:rapidstylers-notification-service}",
            autoStartup = "${app.kafka.consumers.notifications.enabled:true}"
    )
    public void handleRetriedNotification(String payload,
                                          @Header(name = "outbox-id", required = false) String outboxId,
                                          @Header(name = "outbox-event-id", required = false) String eventId,
                                          @Header(name = "outbox-event-type", required = false) String eventType,
                                          @Header(name = "retry-count", required = false) String retryCountHeader,
                                          Acknowledgment acknowledgment) {
        handleBookingNotification(payload, outboxId, eventId, eventType, retryCountHeader, acknowledgment);
    }

    private void sendToRetryTopic(String payload, String outboxId, String eventId, String eventType, int newRetryCount) {
        try {
            org.apache.kafka.clients.producer.ProducerRecord<String, String> record =
                    new org.apache.kafka.clients.producer.ProducerRecord<>(retryTopic, null, payload);
            if (outboxId != null) record.headers().add("outbox-id", outboxId.getBytes(StandardCharsets.UTF_8));
            if (eventId != null) record.headers().add("outbox-event-id", eventId.getBytes(StandardCharsets.UTF_8));
            if (eventType != null) record.headers().add("outbox-event-type", eventType.getBytes(StandardCharsets.UTF_8));
            record.headers().add("retry-count", String.valueOf(newRetryCount).getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record);
            LOG.info("Sent event " + eventId + " to retry topic (attempt " + newRetryCount + ")");
        } catch (Exception ex) {
            LOG.warning("Failed to send event to retry topic: " + ex.getMessage());
        }
    }

    private void sendToDlq(String payload, String outboxId, String eventId, String eventType, String error) {
        try {
            org.apache.kafka.clients.producer.ProducerRecord<String, String> record =
                    new org.apache.kafka.clients.producer.ProducerRecord<>(dlqTopic, null, payload);
            if (outboxId != null) record.headers().add("outbox-id", outboxId.getBytes(StandardCharsets.UTF_8));
            if (eventId != null) record.headers().add("outbox-event-id", eventId.getBytes(StandardCharsets.UTF_8));
            if (eventType != null) record.headers().add("outbox-event-type", eventType.getBytes(StandardCharsets.UTF_8));
            if (error != null) record.headers().add("error-message", error.getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record);
            LOG.warning("Sent event " + eventId + " to DLQ: " + error);
        } catch (Exception ex) {
            LOG.warning("Failed to send event to DLQ: " + ex.getMessage());
        }
    }

    private int parseRetryCount(String header) {
        if (header == null || header.isBlank()) return 0;
        try {
            return Integer.parseInt(header.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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

    private String refundBody(String headline, String detail, Optional<SubServiceEntity> service,
                               Map<String, String> event) {
        String serviceName = service.map(SubServiceEntity::getName).orElse("Appointment");
        return "<p>" + value(headline, "Refund notice") + "</p>"
                + "<p>" + value(detail, "A refund has been issued to your payment method.") + "</p>"
                + "<p><strong>Service:</strong> " + serviceName + "</p>"
                + "<p><strong>Date:</strong> " + value(event.get("appointmentDate"), "TBC") + "</p>"
                + "<p><strong>Refund amount:</strong> $" + value(event.get("refundAmount"), "0.00") + "</p>"
                + "<p><strong>Reason:</strong> " + value(event.get("refundReason"), "—") + "</p>"
                + "<p>Refunds typically appear on your statement within 5-10 business days.</p>";
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
