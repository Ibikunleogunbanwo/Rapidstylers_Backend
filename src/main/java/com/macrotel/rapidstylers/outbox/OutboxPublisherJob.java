package com.macrotel.rapidstylers.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class OutboxPublisherJob {

    private final OutboxEventRepo outboxEventRepo;
    private final OutboxKafkaPublisher outboxKafkaPublisher;

    @Value("${app.kafka.outbox.publisher-enabled:true}")
    private boolean publisherEnabled;

    public OutboxPublisherJob(OutboxEventRepo outboxEventRepo, OutboxKafkaPublisher outboxKafkaPublisher) {
        this.outboxEventRepo = outboxEventRepo;
        this.outboxKafkaPublisher = outboxKafkaPublisher;
    }

    @Scheduled(fixedDelayString = "${app.kafka.outbox.publisher-delay-ms:5000}")
    public void publishPendingEvents() {
        if (!publisherEnabled) {
            return;
        }

        List<OutboxEventEntity> events =
                outboxEventRepo.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        OutboxStatus.PENDING, LocalDateTime.now());
        for (OutboxEventEntity event : events) {
            outboxKafkaPublisher.publish(event);
        }
    }
}
