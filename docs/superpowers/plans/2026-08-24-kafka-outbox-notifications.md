# Kafka Outbox Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Kafka-backed asynchronous notification delivery through a database outbox while keeping bookings/payments database-owned.

**Architecture:** Booking methods write outbox events inside the same transaction as business state. A scheduled publisher emits pending events to Kafka. A Kafka listener handles booking notification events and sends email through the existing Resend client.

**Tech Stack:** Spring Boot 2.7, Spring Data JPA, Spring Kafka, MySQL, Redis, Apache Kafka KRaft, Docker Compose, JUnit/Mockito.

**Spec:** `docs/superpowers/specs/2026-08-24-kafka-outbox-notifications-design.md`

## Global Constraints

Use RapidStylers ports `9095` app, `6380` Redis, `9094` Kafka host, `19094` Kafka internal, `8089` Kafka UI.
Do not make Kafka the source of truth for bookings or payments.
Do not fail or roll back booking/status changes because email/SMS/Kafka delivery is unavailable.
Do not implement payment provider behavior in this phase.
Keep SMS behind event payload/interface readiness; no SMS vendor integration in this phase.

---

### Task 1: Kafka and Outbox Infrastructure

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Create: `src/main/java/com/macrotel/rapidstylers/outbox/OutboxStatus.java`
- Create: `src/main/java/com/macrotel/rapidstylers/outbox/OutboxEventType.java`
- Create: `src/main/java/com/macrotel/rapidstylers/outbox/OutboxEventEntity.java`
- Create: `src/main/java/com/macrotel/rapidstylers/outbox/OutboxEventRepo.java`
- Create: `src/main/java/com/macrotel/rapidstylers/outbox/OutboxEventService.java`
- Test: `src/test/java/com/macrotel/rapidstylers/service/OutboxEventServiceTest.java`

**Interfaces:**
- Produces: `OutboxEventService.appointmentNotification(BookAppointmentEntity appointment, String eventLabel, String customerHeadline, String customerDetail, String stylerHeadline, String stylerDetail)`
- Produces: `OutboxEventRepo.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(...)`

- [ ] Write failing tests for outbox event creation and payload content.
- [ ] Add Spring Kafka dependency.
- [ ] Add outbox entity, status/type enums, repository, and event service.
- [ ] Verify focused outbox tests pass.

### Task 2: Booking Flow Integration

**Files:**
- Modify: `src/main/java/com/macrotel/rapidstylers/service/AppService.java`
- Modify: existing booking workflow tests

**Interfaces:**
- Consumes: `OutboxEventService.appointmentNotification(...)`
- Produces: booking flows no longer call Resend directly for appointment lifecycle messages.

- [ ] Write failing tests proving booking/status changes enqueue outbox events and do not call email directly.
- [ ] Inject `OutboxEventService` into `AppService`.
- [ ] Replace appointment lifecycle email calls with outbox event writes.
- [ ] Keep legacy direct email for OTP flows.
- [ ] Verify booking tests pass.

### Task 3: Kafka Publisher and Consumer

**Files:**
- Create: `src/main/java/com/macrotel/rapidstylers/config/KafkaConfig.java`
- Create: `src/main/java/com/macrotel/rapidstylers/outbox/OutboxKafkaPublisher.java`
- Create: `src/main/java/com/macrotel/rapidstylers/outbox/OutboxPublisherJob.java`
- Create: `src/main/java/com/macrotel/rapidstylers/outbox/NotificationEventConsumer.java`
- Test: `src/test/java/com/macrotel/rapidstylers/service/OutboxPublisherTest.java`
- Test: `src/test/java/com/macrotel/rapidstylers/service/NotificationEventConsumerTest.java`

**Interfaces:**
- Consumes: pending `OutboxEventEntity` rows.
- Produces: Kafka records with headers `outbox-id`, `outbox-event-id`, `outbox-event-type`.
- Produces: email sends from booking notification events.

- [ ] Write failing publisher tests for publish success and publish failure retry state.
- [ ] Write failing consumer test for email delivery from event payload.
- [ ] Implement publisher and scheduled job.
- [ ] Implement notification consumer.
- [ ] Verify focused tests pass.

### Task 4: Container Runtime

**Files:**
- Modify: `docker-compose.yml`
- Modify: `.env.example`
- Modify: `README.md` if present

**Interfaces:**
- Produces: local Kafka at `localhost:9094`, internal Docker Kafka at `kafka:19094`, Kafka UI at `localhost:8089`, Redis at `localhost:6380`.

- [ ] Add Kafka service in KRaft mode.
- [ ] Add Kafka init service for domain, retry, and DLQ topics.
- [ ] Add Kafka UI service on `8089`.
- [ ] Preserve Redis host port `6380`.
- [ ] Document required Kafka env vars.

### Task 5: Verification

**Files:**
- No new files.

**Interfaces:**
- Confirms build and tests.

- [ ] Run targeted tests for outbox, publisher, consumer, and booking workflows.
- [ ] Run full `./mvnw test`.
- [ ] Report any existing unrelated dirty files separately from this change.
