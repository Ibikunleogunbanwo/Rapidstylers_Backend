package com.macrotel.rapidstylers.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxKafkaPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxEventRepo outboxEventRepo;

    @Value("${app.kafka.outbox.publish-timeout-seconds:10}")
    private Long publishTimeoutSeconds = 10L;

    @Value("${app.kafka.outbox.max-attempts:3}")
    private int maxAttempts = 3;

    @Value("${app.kafka.outbox.retry-delay-seconds:30}")
    private long retryDelaySeconds = 30L;

    public OutboxKafkaPublisher(KafkaTemplate<String, String> kafkaTemplate, OutboxEventRepo outboxEventRepo) {
        this.kafkaTemplate = kafkaTemplate;
        this.outboxEventRepo = outboxEventRepo;
    }

    public void publish(OutboxEventEntity event) {
        try {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(event.getTopic(), event.getAggregateId(), event.getPayload());
            addHeader(record, "outbox-id", event.getId());
            addHeader(record, "outbox-event-id", event.getEventId());
            addHeader(record, "outbox-event-type", event.getEventType().name());

            kafkaTemplate.send(record).get(publishTimeoutSeconds, TimeUnit.SECONDS);

            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(LocalDateTime.now());
            event.setLastError(null);
        } catch (Exception ex) {
            int attempts = event.getAttempts() + 1;
            event.setAttempts(attempts);
            event.setStatus(attempts >= maxAttempts ? OutboxStatus.FAILED : OutboxStatus.PENDING);
            event.setLastError(ex.getMessage());
            event.setNextAttemptAt(LocalDateTime.now().plusSeconds(retryDelaySeconds * attempts));
        }
        outboxEventRepo.save(event);
    }

    private void addHeader(ProducerRecord<String, String> record, String key, Object value) {
        if (value == null) {
            return;
        }
        record.headers().add(key, String.valueOf(value).getBytes(StandardCharsets.UTF_8));
    }
}
