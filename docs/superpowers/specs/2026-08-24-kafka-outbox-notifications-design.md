# Kafka Outbox Notifications Design

## Goal

Add a scalable event pipeline for RapidStylers so booking, notification, email, SMS, and future payment side effects are decoupled from synchronous API requests.

## Architecture

RapidStylers will use a database outbox plus Kafka. Business actions save their core state and an outbox event in the same database transaction. A scheduled publisher sends pending outbox events to Kafka. Kafka consumers handle side effects such as email, in-app notification, SMS later, audit enrichment, and payment follow-up work.

Kafka is not the source of truth for bookings or payments. The database and payment provider remain authoritative.

## Runtime Ports

AfroChow already uses app `8081`, MySQL `3306`, Redis `6379`, Kafka host `9092`, Kafka internal `19092`, and Kafka UI `8088`.

RapidStylers will use:

```text
Spring Boot app: 9095
Redis host:      6380
Kafka host:      9094
Kafka internal:  19094
Kafka UI:        8089
```

## Topics

```text
rapidstylers.domain-events
rapidstylers.domain-events.retry
rapidstylers.domain-events.dlq
```

## Event Model

Outbox events store:

```text
eventId
eventType
aggregateType
aggregateId
topic
payload JSON
status: PENDING, PUBLISHED, FAILED
attempts
lastError
createdAt
publishedAt
nextAttemptAt
```

Initial event types:

```text
BOOKING_REQUESTED
BOOKING_CONFIRMED
BOOKING_DECLINED
BOOKING_COMPLETED
BOOKING_CANCELLED
PAYMENT_INTENT_CREATED
PAYMENT_SUCCEEDED
PAYMENT_FAILED
REFUND_REQUESTED
REFUND_COMPLETED
EMAIL_REQUESTED
SMS_REQUESTED
NOTIFICATION_REQUESTED
```

Payment events are reserved for the later payment implementation. This phase wires booking-related notification events only.

## Booking Notification Flow

```text
bookAppointment / updateAppointmentStatus
  -> persist booking/status change
  -> persist outbox event in same transaction
  -> return success
  -> scheduled outbox publisher emits to Kafka
  -> notification consumer sends email
```

If Kafka is down, the booking remains saved and the outbox event remains pending for retry. If Resend/email is down, the consumer logs the failure and Kafka retry/DLQ handling can reprocess without reverting the booking.

## SMS Boundary

SMS will use the same notification consumer interface later. This phase prepares the event payload with recipient role, message title, body, appointment id, service, date, and pricing fields so an SMS sender can be added without changing booking flows again.

## MVP Delivery

The first implementation keeps the publisher and consumer inside the existing Spring Boot app. Later, the same code can be split into separate `notification-worker` and payment worker containers using the same Kafka topics.
