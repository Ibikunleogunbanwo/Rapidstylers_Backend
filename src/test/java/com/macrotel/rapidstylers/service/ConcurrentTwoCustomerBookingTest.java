package com.macrotel.rapidstylers.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.config.InnoDbReconciler;
import com.macrotel.rapidstylers.config.SlotLockUniqueReconciler;
import com.macrotel.rapidstylers.entity.IdentificationEntity;
import com.macrotel.rapidstylers.entity.ServiceEntity;
import com.macrotel.rapidstylers.repo.AuditLogRepo;
import com.macrotel.rapidstylers.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.BookingSlotLockRepo;
import com.macrotel.rapidstylers.repo.IdentificationRepo;
import com.macrotel.rapidstylers.repo.NotificationRepo;
import com.macrotel.rapidstylers.repo.OTPRepo;
import com.macrotel.rapidstylers.repo.ServiceRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The genuine slot race: two distinct live customers concurrently submit a
 * booking request for the SAME styler, date and slot over the real HTTP stack
 * against the real MySQL database (test profile loads .env like production).
 *
 * Unlike ConcurrentBookingTest (which only mocks the Redis idempotency claim),
 * this exercises the DB unique constraint on booking_slot_locks(styler_id,
 * appointment_date, slot_start) combined with the pessimistic lock, and asserts
 * the marketplace invariant: exactly one customer wins.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentTwoCustomerBookingTest {

    private static final Pattern OTP_PATTERN = Pattern.compile("<strong>(\\d{6})</strong>");
    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean EmailConfig emailConfig;

    @Autowired UserRepo userRepo;
    @Autowired StylerRepo stylerRepo;
    @Autowired OTPRepo otpRepo;
    @Autowired SubServiceRepo subServiceRepo;
    @Autowired AvailabilityRepo availabilityRepo;
    @Autowired BookAppointmentRepo appointmentRepo;
    @Autowired BookingSlotLockRepo slotLockRepo;
    @Autowired NotificationRepo notificationRepo;
    @Autowired AuditLogRepo auditLogRepo;
    @Autowired LocationCacheService locationCacheService;
    @Autowired IdentificationRepo identificationRepo;
    @Autowired ServiceRepo serviceRepo;
    @Autowired SlotLockUniqueReconciler slotLockReconciler;
    @Autowired InnoDbReconciler innoDbReconciler;

    @Value("${app.api.key}") String apiKey;
    @Value("${app.admin.email}") String adminEmail;
    @Value("${app.admin.password}") String adminPassword;

    private String custEmailA;
    private String custEmailB;
    private String stylerEmail;
    private String customerUserIdA;
    private String customerUserIdB;
    private String stylerId;
    private Long subServiceId;
    private String appointmentId;
    private Long identificationTypeId;
    private Long serviceTypeId;
    private final List<String> emailsToClean = new ArrayList<>();

    @BeforeEach
    void uniqueIdentities() {
        long ts = System.currentTimeMillis();
        custEmailA = "race.a." + ts + "@rapidstylers.test";
        custEmailB = "race.b." + ts + "@rapidstylers.test";
        stylerEmail = "race.styl." + ts + "@rapidstylers.test";
        emailsToClean.add(custEmailA);
        emailsToClean.add(custEmailB);
        emailsToClean.add(stylerEmail);
        // Styler registration needs an identification type and a service type.
        // They exist on long-lived dev databases but not on a fresh CI database,
        // so create marker rows (idempotent) and remove them in @AfterEach.
        IdentificationEntity idType = identificationRepo.findByIdentificationName("Race Test ID")
                .orElseGet(() -> {
                    IdentificationEntity e = new IdentificationEntity();
                    e.setIdentificationName("Race Test ID");
                    return identificationRepo.save(e);
                });
        identificationTypeId = idType.getId();
        ServiceEntity svcType = serviceRepo.findByServiceName("Race Test Service")
                .orElseGet(() -> {
                    ServiceEntity e = new ServiceEntity();
                    e.setServiceName("Race Test Service");
                    return serviceRepo.save(e);
                });
        serviceTypeId = svcType.getId();
    }

    @AfterEach
    void cleanup() {
        if (appointmentId != null) {
            slotLockRepo.deleteByAppointmentId(appointmentId);
            appointmentRepo.findByAppointmentId(appointmentId).ifPresent(appointmentRepo::delete);
        }
        if (stylerId != null) {
            // Remove every appointment and its slot locks this run (or a stale run
            // with the same styler id) created, not just the winner's.
            appointmentRepo.findAll().stream()
                    .filter(a -> stylerId.equals(a.getStylerId()))
                    .forEach(a -> {
                        slotLockRepo.deleteByAppointmentId(a.getAppointmentId());
                        appointmentRepo.delete(a);
                    });
            notificationRepo.findByStylerId(stylerId).forEach(notificationRepo::delete);
            subServiceRepo.findByStylerId(stylerId).forEach(subServiceRepo::delete);
            availabilityRepo.deleteByStylerId(stylerId);
            try {
                locationCacheService.removeStyler(stylerId);
            } catch (Exception ignored) {
            }
            stylerRepo.findByEmailAddress(stylerEmail).ifPresent(stylerRepo::delete);
        }
        if (customerUserIdA != null) {
            notificationRepo.findByUserIdOrderByCreatedAtDesc(customerUserIdA).forEach(notificationRepo::delete);
            userRepo.findByEmailAddress(custEmailA).ifPresent(userRepo::delete);
        }
        if (customerUserIdB != null) {
            notificationRepo.findByUserIdOrderByCreatedAtDesc(customerUserIdB).forEach(notificationRepo::delete);
            userRepo.findByEmailAddress(custEmailB).ifPresent(userRepo::delete);
        }
        otpRepo.findAll().stream()
                .filter(o -> emailsToClean.contains(o.getEmailAddress()))
                .forEach(otpRepo::delete);
        identificationRepo.findByIdentificationName("Race Test ID").ifPresent(identificationRepo::delete);
        serviceRepo.findByServiceName("Race Test Service").ifPresent(serviceRepo::delete);
    }

    @Test
    void twoLiveCustomersRaceTheSameSlot_onlyOneWins() throws Exception {
        // ApplicationRunners do not execute inside @SpringBootTest, so invoke the
        // reconcilers directly. InnoDbReconciler converts the legacy MyISAM booking
        // tables so @Transactional rollback and the pessimistic stylist lock actually
        // work; SlotLockUniqueReconciler guarantees the unique race-guard index on
        // booking_slot_locks (creating it where ddl-auto never added it).
        innoDbReconciler.run(null);
        slotLockReconciler.run(null);

        // ---- Two live customers -------------------------------------------------
        String[] custA = registerCustomer(custEmailA);
        customerUserIdA = custA[1];
        String[] custB = registerCustomer(custEmailB);
        customerUserIdB = custB[1];

        // ---- One stylist with an approved profile + service + availability -------
        stylerId = registerStyler();
        String adminToken = signIn("/sign_in", adminEmail, adminPassword);
        apiPost("/admin/update_styler_verification",
                Map.of("stylerId", stylerId, "action", "APPROVE"), adminToken, null);
        assertEquals("APPROVED",
                stylerRepo.findByEmailAddress(stylerEmail).orElseThrow().getVerificationStatus());

        // Clear any appointments/locks left behind by a previous run that happened
        // to draw the same styler id, so the appointment count below starts at zero
        // and can never be polluted by stale debris.
        appointmentRepo.findAll().stream()
                .filter(a -> stylerId.equals(a.getStylerId()))
                .forEach(a -> {
                    slotLockRepo.deleteByAppointmentId(a.getAppointmentId());
                    appointmentRepo.delete(a);
                });

        String stylerToken = signIn("/styler_sign_in", stylerEmail, "Test1234!");
        apiPost("/create_sub_service",
                Map.of("name", "Race Braids", "price", "85.00", "durationMinutes", 60), stylerToken, null);
        subServiceId = subServiceRepo.findByStylerId(stylerId).stream()
                .filter(s -> "Race Braids".equals(s.getName()))
                .findFirst().orElseThrow().getId();
        apiPost("/update_styler_availability", Map.of("slots", List.of(
                Map.of("dayOfWeek", "1", "startTime", "09:00", "endTime", "17:00"))), stylerToken, null);

        // ---- The race: same slot, two different customers, released together ------
        LocalDate bookingDate = nextDateWithDow(1); // next Monday
        Map<String, Object> bookingA = bookingBody(bookingDate, String.valueOf(subServiceId));
        Map<String, Object> bookingB = bookingBody(bookingDate, String.valueOf(subServiceId));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        // Distinct customers contending for the same slot rely on the DB
        // booking_slot_locks unique constraint + pessimistic lock to pick a winner,
        // so no idempotency key is needed (and must not be used to short-circuit).
        Callable<JsonNode> submitA = () -> {
            start.await();
            return body(apiPost("/book_appointment", bookingA, custA[0], null));
        };
        Callable<JsonNode> submitB = () -> {
            start.await();
            return body(apiPost("/book_appointment", bookingB, custB[0], null));
        };
        Future<JsonNode> futureA = pool.submit(submitA);
        Future<JsonNode> futureB = pool.submit(submitB);
        start.countDown();
        JsonNode resultA = futureA.get(30, TimeUnit.SECONDS);
        JsonNode resultB = futureB.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // ---- Marketplace invariant: exactly one wins ------------------------------
        boolean aWon = isSuccess(resultA);
        boolean bWon = isSuccess(resultB);
        assertFalse(aWon && bWon, "two customers must not both win the same slot: "
                + resultA + " || " + resultB);
        assertTrue(aWon || bWon, "at least one customer must win the slot: "
                + resultA + " || " + resultB);

        // The winner's booking persisted a single slot lock row.
        assertTrue(slotLockRepo.existsByStylerIdAndAppointmentDateAndSlotStart(
                stylerId, bookingDate, LocalTime.of(10, 0)),
                "the winning booking must hold the slot lock");

        // Exactly one appointment was created for this styler by the winning customer.
        long appointmentCount = appointmentRepo.findAll().stream()
                .filter(a -> stylerId.equals(a.getStylerId()))
                .count();
        assertEquals(1, appointmentCount, "exactly one appointment may exist for the contested slot; A="
                + resultA + " B=" + resultB);

        appointmentId = appointmentRepo.findByUserId(aWon ? customerUserIdA : customerUserIdB).stream()
                .findFirst().map(a -> a.getAppointmentId()).orElse(null);
    }

    // ---- helpers ---------------------------------------------------------------

    private String[] registerCustomer(String email) throws Exception {
        apiPost("/generate_sign_up_otp_code", Map.of("emailAddress", email), null, null);
        String otp = lastOtp();
        apiPost("/verify_otp_code", Map.of("otpCode", otp), null, null);

        Map<String, Object> userData = new LinkedHashMap<>();
        userData.put("firstname", "Race");
        userData.put("lastname", "Customer");
        userData.put("emailAddress", email);
        userData.put("country", "Canada");
        userData.put("state", "Ontario");
        userData.put("address", "123 Main St, Toronto, ON M5V 2T6");
        userData.put("phoneNumber", "587777" + String.format("%04d", (email.hashCode() & 0xFFFF) % 10000));
        userData.put("password", "Test1234!");
        userData.put("agreeToTerms", true);
        apiPost("/create_user_account", userData, null, null);

        String token = signIn("/user_sign_in", email, "Test1234!");
        String userId = userRepo.findByEmailAddress(email).orElseThrow().getUserId();
        return new String[]{token, userId};
    }

    private String registerStyler() throws Exception {
        apiPost("/styler_generate_otp", Map.of("emailAddress", stylerEmail), null, null);
        String otp = lastOtp();
        apiPost("/styler_verify_otp", Map.of("otpCode", otp), null, null);

        Map<String, Object> stylerData = new LinkedHashMap<>();
        stylerData.put("firstname", "Race");
        stylerData.put("lastname", "Stylist");
        stylerData.put("emailAddress", stylerEmail);
        stylerData.put("country", "Canada");
        stylerData.put("state", "Ontario");
        stylerData.put("address", "45 King St W, Toronto, ON");
        stylerData.put("phoneNumber", "587666" + String.format("%04d", (stylerEmail.hashCode() & 0xFFFF) % 10000));
        stylerData.put("password", "Test1234!");
        stylerData.put("agreeToTerms", true);
        stylerData.put("identificationTypeId", String.valueOf(identificationTypeId));
        stylerData.put("identificationImageUrl", "https://example.com/id.jpg");
        stylerData.put("profileImageUrl", "https://example.com/profile.jpg");
        stylerData.put("businessName", "Race Styles");
        stylerData.put("serviceTypeId", String.valueOf(serviceTypeId));
        stylerData.put("businessAddress", "45 King St W, Toronto, ON");
        stylerData.put("businessProvince", "Ontario");
        stylerData.put("latitude", 43.6532);
        stylerData.put("longitude", -79.3832);
        apiPost("/create_styler", stylerData, null, null);
        return stylerRepo.findByEmailAddress(stylerEmail).orElseThrow().getStylerId();
    }

    private Map<String, Object> bookingBody(LocalDate date, String subServiceId) {
        Map<String, Object> booking = new LinkedHashMap<>();
        booking.put("stylerId", stylerId);
        booking.put("appointmentDate", date.toString());
        booking.put("arrivalTime", "10:00");
        booking.put("price", "85.00");
        booking.put("noOfPeople", "1");
        booking.put("subServiceId", subServiceId);
        return booking;
    }

    private boolean isSuccess(JsonNode node) {
        return node != null && node.has("statusCode") && "200".equals(node.get("statusCode").asText());
    }

    private String signIn(String path, String email, String password) throws Exception {
        JsonNode response = body(apiPost(path, Map.of("emailAddress", email, "password", password), null, null));
        assertEquals("200", response.get("statusCode").asText(), "sign-in failed: " + response);
        String token = response.get("token").asText();
        assertFalse(token.isBlank(), "sign-in must return a token");
        return token;
    }

    private String lastOtp() throws Exception {
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailConfig, atLeastOnce()).sendSimpleMail(anyString(), anyString(), bodyCaptor.capture());
        Matcher matcher = OTP_PATTERN.matcher(bodyCaptor.getValue());
        assertTrue(matcher.find(), "no 6-digit OTP found in email body: " + bodyCaptor.getValue());
        return matcher.group(1);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult apiPost(String path, Object payload, String token, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = post("/rapid_stylers" + path)
                .header("x-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON);
        if (payload != null) {
            request.content(objectMapper.writeValueAsString(payload));
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request).andReturn();
    }

    /** The next date (from tomorrow) whose ISO weekday matches the given dayOfWeek (1=Monday … 7=Sunday). */
    private LocalDate nextDateWithDow(int isoDow) {
        LocalDate date = LocalDate.now(TORONTO).plusDays(1);
        while (date.getDayOfWeek().getValue() != isoDow) {
            date = date.plusDays(1);
        }
        return date;
    }
}