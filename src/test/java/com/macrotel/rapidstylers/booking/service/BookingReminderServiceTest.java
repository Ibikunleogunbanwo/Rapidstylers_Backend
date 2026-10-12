package com.macrotel.rapidstylers.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.engagement.entity.NotificationEntity;
import com.macrotel.rapidstylers.engagement.repo.NotificationRepo;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reminder job against the real database (Flyway-migrated, Hibernate
 * validating): which bookings it claims, what it queues, that a claim is
 * taken once even when two runs race, and that the read-only demo never runs.
 *
 * <p>Bookings are dated in 2041 so no other test's rows fall in the scan
 * window, and every assertion is about this test's own rows.
 */
@SpringBootTest
class BookingReminderServiceTest {

    @Autowired BookingReminderService service;
    @Autowired BookAppointmentRepo appointmentRepo;
    @Autowired StylerRepo stylerRepo;
    @Autowired UserRepo userRepo;
    @Autowired NotificationRepo notificationRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @MockBean EmailConfig emailConfig;

    private final String tag = "RM" + Long.toString(System.nanoTime(), 36).toUpperCase();
    private final String stylerId = tag + "ST";
    private final String customer = tag + "CU";
    private final String suspended = tag + "SU";
    /** 10:00 in Toronto (EDT, -04:00) is 14:00Z. */
    private static final LocalDate DAY = LocalDate.of(2041, 6, 13);
    private static final Instant DAY_BEFORE = Instant.parse("2041-06-12T15:00:00Z"); // 23h before

    @BeforeEach
    void seed() {
        StylerEntity s = new StylerEntity();
        s.setStylerId(stylerId);
        s.setProvince("Ontario");
        s.setTimeZone("America/Toronto");
        s.setBusinessName("Toronto Braids");
        s.setFirstname("Test");
        s.setLastname("Stylist");
        s.setEmailAddress(stylerId.toLowerCase() + "@rapidstylers.test");
        s.setBusinessAddress("200 Queen St W, Toronto, ON");
        s.setStatus("0");
        stylerRepo.save(s);
        userRepo.save(user(customer, "0"));
        userRepo.save(user(suspended, "SUSPENDED"));
    }

    @AfterEach
    void cleanUp() {
        ReflectionTestUtils.setField(service, "readOnly", false);
        appointmentRepo.findAll().stream().filter(b -> b.getAppointmentId() != null && b.getAppointmentId().startsWith(tag))
                .forEach(appointmentRepo::delete);
        jdbc.update("DELETE FROM outbox_events WHERE aggregate_id LIKE ?", tag + "%");
        for (String id : List.of(customer, suspended)) {
            notificationRepo.deleteAll(notificationRepo.findByUserIdOrderByCreatedAtDesc(id));
            userRepo.findByUserId(id).ifPresent(userRepo::delete);
        }
        stylerRepo.findByStylerId(stylerId).ifPresent(stylerRepo::delete);
    }

    @Test
    void remindsAnAcceptedBookingOnceWithEmailAndInboxEntry() throws Exception {
        BookAppointmentEntity b = appointmentRepo.save(booking(tag + "A", customer, "3", "salonService"));

        service.runOnce(DAY_BEFORE);

        assertNotNull(reload(b).getReminderSentAt());
        List<Map<String, Object>> events = reminderEvents(tag + "A");
        assertEquals(1, events.size());
        JsonNode payload = objectMapper.readTree((String) events.get(0).get("payload"));
        assertEquals(customer, payload.get("customerId").asText());
        assertEquals("SALON", payload.get("visitType").asText());
        assertEquals("200 Queen St W, Toronto, ON", payload.get("location").asText());
        assertTrue(payload.get("bookingsUrl").asText().endsWith("/dashboard"));
        List<NotificationEntity> inbox = notificationRepo.findByUserIdOrderByCreatedAtDesc(customer);
        assertEquals(1, inbox.size());
        assertEquals("BOOKING", inbox.get(0).getType());
        assertEquals("Appointment reminder", inbox.get(0).getTitle());
        assertTrue(inbox.get(0).getMessage().contains("Toronto Braids"), inbox.get(0).getMessage());

        // Later runs, even inside the window, leave it alone.
        service.runOnce(DAY_BEFORE.plusSeconds(600));
        service.runOnce(DAY_BEFORE.plusSeconds(3600));
        assertEquals(1, reminderEvents(tag + "A").size());
        assertEquals(1, notificationRepo.findByUserIdOrderByCreatedAtDesc(customer).size());
    }

    @Test
    void homeVisitReminderCarriesTheCustomersAddress() throws Exception {
        BookAppointmentEntity b = booking(tag + "H", customer, "3", "homeService");
        b.setServiceAddress("12 Elm St, Toronto, ON");
        appointmentRepo.save(b);

        service.runOnce(DAY_BEFORE);

        JsonNode payload = objectMapper.readTree((String) reminderEvents(tag + "H").get(0).get("payload"));
        assertEquals("HOME", payload.get("visitType").asText());
        assertEquals("12 Elm St, Toronto, ON", payload.get("location").asText());
    }

    @Test
    void onlyAcceptedBookingsOfActiveCustomersInsideTheWindowAreClaimed() {
        appointmentRepo.save(booking(tag + "P", customer, "1", "salonService"));  // pending
        appointmentRepo.save(booking(tag + "D", customer, "2", "salonService"));  // declined
        appointmentRepo.save(booking(tag + "C", customer, "0", "salonService"));  // completed
        appointmentRepo.save(booking(tag + "X", customer, "4", "salonService"));  // cancelled
        BookAppointmentEntity disputed = booking(tag + "Q", customer, "3", "salonService");
        disputed.setPaymentStatus("DISPUTED");
        appointmentRepo.save(disputed);
        appointmentRepo.save(booking(tag + "S", suspended, "3", "salonService")); // suspended customer
        appointmentRepo.save(booking(tag + "G", tag + "GONE", "3", "salonService")); // customer row gone
        BookAppointmentEntity ok = appointmentRepo.save(booking(tag + "A", customer, "3", "salonService"));

        // Too early (two days out), then too late (an hour before), then after the start: nothing.
        service.runOnce(DAY_BEFORE.minusSeconds(2 * 86400));
        service.runOnce(Instant.parse("2041-06-13T13:00:00Z"));
        service.runOnce(Instant.parse("2041-06-13T14:30:00Z"));
        assertNull(reload(ok).getReminderSentAt());

        service.runOnce(DAY_BEFORE);

        assertNotNull(reload(ok).getReminderSentAt());
        for (String id : List.of("P", "D", "C", "X", "Q", "S", "G")) {
            assertNull(appointmentRepo.findByAppointmentId(tag + id).orElseThrow().getReminderSentAt(), id);
            assertEquals(0, reminderEvents(tag + id).size(), id);
        }
        assertEquals(0, notificationRepo.findByUserIdOrderByCreatedAtDesc(suspended).size());
    }

    @Test
    void shortNoticeBookingIsRemindedOnceWhileTwoHoursRemain() {
        BookAppointmentEntity b = appointmentRepo.save(booking(tag + "N", customer, "3", "salonService"));
        // Accepted five hours before the 14:00Z start: next run sends it.
        service.runOnce(Instant.parse("2041-06-13T09:00:00Z"));
        assertNotNull(reload(b).getReminderSentAt());
        service.runOnce(Instant.parse("2041-06-13T11:00:00Z"));
        assertEquals(1, reminderEvents(tag + "N").size());
    }

    @Test
    void twoConcurrentRunsSendEachReminderExactlyOnce() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(appointmentRepo.save(booking(tag + "R" + i, customer, "3", "salonService")).getAppointmentId());
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<Integer> run = () -> {
                go.await();
                return service.runOnce(DAY_BEFORE);
            };
            Future<Integer> a = pool.submit(run);
            Future<Integer> b = pool.submit(run);
            go.countDown();
            a.get(60, TimeUnit.SECONDS);
            b.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        for (String id : ids) {
            assertEquals(1, reminderEvents(id).size(), id);
            assertNotNull(appointmentRepo.findByAppointmentId(id).orElseThrow().getReminderSentAt(), id);
        }
        assertEquals(ids.size(), notificationRepo.findByUserIdOrderByCreatedAtDesc(customer).size());
    }

    @Test
    void readOnlyDemoNeverRuns() {
        BookAppointmentEntity b = appointmentRepo.save(booking(tag + "M", customer, "3", "salonService"));
        ReflectionTestUtils.setField(service, "readOnly", true);
        ReflectionTestUtils.setField(service, "enabled", true);
        try {
            assertEquals(0, service.runOnce(DAY_BEFORE));
            service.scheduledRun();
        } finally {
            ReflectionTestUtils.setField(service, "enabled", false);
            ReflectionTestUtils.setField(service, "readOnly", false);
        }
        assertNull(reload(b).getReminderSentAt());
        assertEquals(0, reminderEvents(tag + "M").size());
    }

    @Test
    void migrationAddsANullableReminderColumnThatHibernateValidates() {
        // The context starting at all proves ddl validate accepted the entity; this pins the V38 shape.
        Map<String, Object> column = jdbc.queryForMap("SELECT IS_NULLABLE, DATA_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'reminder_sent_at'");
        assertEquals("YES", column.get("IS_NULLABLE"));
        assertEquals("datetime", String.valueOf(column.get("DATA_TYPE")).toLowerCase());
        Integer applied = jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '38' AND success = 1",
                Integer.class);
        assertEquals(1, applied);
    }

    private BookAppointmentEntity reload(BookAppointmentEntity b) {
        return appointmentRepo.findByAppointmentId(b.getAppointmentId()).orElseThrow();
    }

    private List<Map<String, Object>> reminderEvents(String appointmentId) {
        return jdbc.queryForList("SELECT payload FROM outbox_events WHERE event_type = 'BOOKING_REMINDER' AND aggregate_id = ?",
                appointmentId).stream().collect(Collectors.toList());
    }

    private UserEntity user(String id, String status) {
        UserEntity u = new UserEntity();
        u.setUserId(id);
        u.setFirstname("Ada");
        u.setLastname("Lovelace");
        u.setEmailAddress(id.toLowerCase() + "@rapidstylers.test");
        u.setStatus(status);
        return u;
    }

    private BookAppointmentEntity booking(String id, String userId, String status, String visit) {
        BookAppointmentEntity b = new BookAppointmentEntity();
        b.setAppointmentId(id);
        b.setUserId(userId);
        b.setStylerId(stylerId);
        b.setSubServiceId("999999");
        b.setStatus(status);
        b.setServiceTime(visit);
        b.setAppointmentDate(DAY.toString());
        b.setAppointmentDateValue(DAY);
        b.setArrivalTime("10:00");
        b.setAppointmentStartTime(LocalTime.of(10, 0));
        b.setAppointmentEndTime(LocalTime.of(11, 0));
        b.setDurationMinutes(60);
        b.setPrice("100.00");
        b.setServicePrice("100.00");
        b.setTravelFee("0.00");
        b.setCreatedAt("06 01, 2041 09:00:00");
        return b;
    }
}
