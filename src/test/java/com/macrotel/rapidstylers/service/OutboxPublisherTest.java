package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.outbox.OutboxEventEntity;
import com.macrotel.rapidstylers.outbox.OutboxEventRepo;
import com.macrotel.rapidstylers.outbox.OutboxEventType;
import com.macrotel.rapidstylers.outbox.OutboxKafkaPublisher;
import com.macrotel.rapidstylers.outbox.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.concurrent.SettableListenableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxEventRepo outboxEventRepo;
    private OutboxKafkaPublisher publisher;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        outboxEventRepo = mock(OutboxEventRepo.class);
        publisher = new OutboxKafkaPublisher(kafkaTemplate, outboxEventRepo);
        ReflectionTestUtils.setField(publisher, "publishTimeoutSeconds", 1L);
    }

    @Test
    void publishMarksEventPublishedWhenKafkaAcceptsRecord() {
        SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
        future.set(mock(SendResult.class));
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class))).thenReturn(future);
        OutboxEventEntity event = event();

        publisher.publish(event);

        assertEquals(OutboxStatus.PUBLISHED, event.getStatus());
        assertNotNull(event.getPublishedAt());
        verify(outboxEventRepo).save(event);
    }

    @Test
    void publishFailureKeepsEventPendingForRetry() {
        SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
        future.setException(new RuntimeException("kafka unavailable"));
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class))).thenReturn(future);
        OutboxEventEntity event = event();

        publisher.publish(event);

        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(1, event.getAttempts());
        assertNotNull(event.getLastError());
        assertNotNull(event.getNextAttemptAt());
        verify(outboxEventRepo).save(event);
    }

    private OutboxEventEntity event() {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(7L);
        event.setEventType(OutboxEventType.BOOKING_REQUESTED);
        event.setTopic("rapidstylers.domain-events");
        event.setAggregateType("APPOINTMENT");
        event.setAggregateId("APPT1");
        event.setPayload("{\"appointmentId\":\"APPT1\"}");
        return event;
    }
}
