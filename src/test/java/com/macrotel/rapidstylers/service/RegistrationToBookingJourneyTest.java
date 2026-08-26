package com.macrotel.rapidstylers.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.entity.OTPEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.SubServiceEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.repo.AuditLogRepo;
import com.macrotel.rapidstylers.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.BookingSlotLockRepo;
import com.macrotel.rapidstylers.repo.NotificationRepo;
import com.macrotel.rapidstylers.repo.OTPRepo;
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
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full marketplace journey against the real HTTP stack and the real database
 * (the test profile loads .env, exactly like production):
 *
 *   customer OTP -> verify -> create account -> sign in
 *   styler OTP   -> verify -> create styler
 *   (booking while unapproved is rejected)
 *   admin sign in -> approve styler
 *   styler sign in -> create sub-service -> set weekly availability
 *   customer books -> both sides see it -> styler accepts
 *
 * Emails are intercepted (EmailConfig mocked) and the OTP is read from the
 * mail body, so the suite runs offline and deterministically. Every row the
 * journey creates is deleted in @AfterEach.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationToBookingJourneyTest {

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

    @Value("${app.api.key}") String apiKey;
    @Value("${app.admin.email}") String adminEmail;
    @Value("${app.admin.password}") String adminPassword;

    private String custEmail;
    private String stylerEmail;
    private String customerUserId;
    private String stylerId;
    private String subServiceId;
    private String appointmentId;
    private String stylerPassword = "Test1234!";

    @BeforeEach
    void uniqueIdentities() {
        long ts = System.currentTimeMillis();
        custEmail = "journey.cust." + ts + "@rapidstylers.test";
        stylerEmail = "journey.styl." + ts + "@rapidstylers.test";
    }

    @AfterEach
    void cleanup() {
        if (appointmentId != null) {
            slotLockRepo.deleteByAppointmentId(appointmentId);
            appointmentRepo.findByAppointmentId(appointmentId).ifPresent(appointmentRepo::delete);
        }
        if (stylerId != null) {
            notificationRepo.findByStylerId(stylerId).forEach(notificationRepo::delete);
            subServiceRepo.findByStylerId(stylerId).forEach(subServiceRepo::delete);
            availabilityRepo.deleteByStylerId(stylerId);
            try {
                locationCacheService.removeStyler(stylerId);
            } catch (Exception ignored) {
            }
            stylerRepo.findByEmailAddress(stylerEmail).ifPresent(stylerRepo::delete);
        }
        if (customerUserId != null) {
            notificationRepo.findByUserIdOrderByCreatedAtDesc(customerUserId).forEach(notificationRepo::delete);
            userRepo.findByEmailAddress(custEmail).ifPresent(userRepo::delete);
        }
        otpRepo.findAll().stream()
                .filter(o -> custEmail.equals(o.getEmailAddress()) || stylerEmail.equals(o.getEmailAddress()))
                .forEach(otpRepo::delete);
    }

    @Test
    void fullRegistrationToBookingJourney() throws Exception {
        // ---- 1. Customer registers ------------------------------------------------
        assertOk(apiPost("/generate_sign_up_otp_code", Map.of("emailAddress", custEmail), null));
        String custOtp = lastOtp();
        assertOk(apiGet("/verify_otp_code?otpCode=" + custOtp, null));

        Map<String, Object> userData = new LinkedHashMap<>();
        userData.put("firstname", "Journey");
        userData.put("lastname", "Customer");
        userData.put("emailAddress", custEmail);
        userData.put("country", "Canada");
        userData.put("state", "Ontario");
        userData.put("address", "123 Main St, Toronto, ON M5V 2T6");
        userData.put("phoneNumber", "587555" + String.format("%04d", System.currentTimeMillis() % 10000));
        userData.put("password", "Test1234!");
        userData.put("agreeToTerms", true);
        assertOk(apiPost("/create_user_account", userData, null));

        String customerToken = signIn("/user_sign_in", custEmail, "Test1234!");
        customerUserId = userRepo.findByEmailAddress(custEmail).orElseThrow().getUserId();
        assertOk(apiGet("/user_data", customerToken)); // JWT-gated read works

        // ---- 2. Styler registers ---------------------------------------------------
        assertOk(apiPost("/styler_generate_otp", Map.of("emailAddress", stylerEmail), null));
        String stylerOtp = lastOtp();
        assertOk(apiGet("/styler_verify_otp?otpCode=" + stylerOtp, null));

        String identificationId = firstListId("/list_identification");
        String serviceTypeId = firstListId("/list_service");
        assertNotNull(identificationId, "seed data needs at least one identification type");
        assertNotNull(serviceTypeId, "seed data needs at least one service type");

        Map<String, Object> stylerData = new LinkedHashMap<>();
        stylerData.put("firstname", "Journey");
        stylerData.put("lastname", "Stylist");
        stylerData.put("emailAddress", stylerEmail);
        stylerData.put("country", "Canada");
        stylerData.put("state", "Ontario");
        stylerData.put("address", "45 King St W, Toronto, ON");
        stylerData.put("phoneNumber", "587444" + String.format("%04d", System.currentTimeMillis() % 10000));
        stylerData.put("password", stylerPassword);
        stylerData.put("agreeToTerms", true);
        stylerData.put("identificationTypeId", identificationId);
        stylerData.put("identificationImageUrl", "https://example.com/id.jpg");
        stylerData.put("profileImageUrl", "https://example.com/profile.jpg");
        stylerData.put("businessName", "Journey Styles");
        stylerData.put("serviceTypeId", serviceTypeId);
        stylerData.put("businessAddress", "45 King St W, Toronto, ON");
        stylerData.put("businessProvince", "Ontario");
        stylerData.put("latitude", 43.6532);
        stylerData.put("longitude", -79.3832);
        assertOk(apiPost("/create_styler", stylerData, null));
        stylerId = stylerRepo.findByEmailAddress(stylerEmail).orElseThrow().getStylerId();

        // ---- 3. Unapproved stylist cannot be booked --------------------------------
        LocalDate bookingDate = nextDateWithDow(1); // next Monday
        Map<String, Object> booking = bookingBody(bookingDate, "999");
        JsonNode unapproved = body(apiPost("/book_appointment", booking, customerToken));
        assertEquals("400", unapproved.get("statusCode").asText(),
                "unapproved stylist must be rejected at booking");
        assertTrue(unapproved.get("message").asText().toLowerCase().contains("not yet available"),
                "unexpected rejection message: " + unapproved.get("message").asText());

        // ---- 4. Admin approves -----------------------------------------------------
        String adminToken = signIn("/sign_in", adminEmail, adminPassword);
        assertOk(apiGet("/admin/styler_verification_queue", adminToken));
        assertOk(apiPost("/admin/update_styler_verification",
                Map.of("stylerId", stylerId, "action", "APPROVE"), adminToken));
        assertEquals("APPROVED",
                stylerRepo.findByEmailAddress(stylerEmail).orElseThrow().getVerificationStatus());

        // ---- 5. Styler signs in, adds a service and availability -------------------
        String stylerToken = signIn("/styler_sign_in", stylerEmail, stylerPassword);
        assertOk(apiGet("/styler_own_sub_services", stylerToken));

        assertOk(apiPost("/create_sub_service",
                Map.of("name", "Journey Braids", "price", "85.00", "durationMinutes", 60), stylerToken));
        subServiceId = String.valueOf(subServiceRepo.findByStylerId(stylerId).stream()
                .filter(s -> "Journey Braids".equals(s.getName()))
                .findFirst().orElseThrow().getId());

        assertOk(apiPost("/update_styler_availability", Map.of("slots", java.util.List.of(
                Map.of("dayOfWeek", "1", "startTime", "09:00", "endTime", "17:00"))), stylerToken));

        // ---- 6. Customer books, both sides see it, styler accepts ------------------
        booking.put("subServiceId", subServiceId);
        booking.put("price", "85.00");
        assertOk(apiPost("/book_appointment", booking, customerToken));
        appointmentId = appointmentRepo.findByUserId(customerUserId).stream()
                .findFirst().orElseThrow().getAppointmentId();

        String userAppointments = raw(apiGet("/user_appointments", customerToken));
        String stylerAppointments = raw(apiGet("/styler_appointments", stylerToken));
        assertTrue(userAppointments.contains(appointmentId), "customer must see the appointment");
        assertTrue(stylerAppointments.contains(appointmentId), "stylist must see the appointment");

        // ---- 6b. Summary shows the pending request before acceptance ----------------
        JsonNode pendingSummary = body(apiGet("/styler/business_summary", stylerToken)).get("data");
        assertEquals(1, pendingSummary.get("pending").asInt(), "one pending request before accept");
        assertEquals(0, pendingSummary.get("confirmed").asInt(), "none confirmed yet");

        assertOk(apiPost("/accept_appointment", Map.of("appointmentId", appointmentId), stylerToken));
        BookAppointmentEntity accepted = appointmentRepo.findByAppointmentId(appointmentId).orElseThrow();
        assertEquals("3", accepted.getStatus(), "accepted appointment status must be 3 (1=pending, 3=accepted)");

        // ---- 7. Business summary reflects the real stats ----------------------------
        JsonNode summary = body(apiGet("/styler/business_summary", stylerToken)).get("data");
        assertNotNull(summary, "business summary must return data");
        assertEquals(1, summary.get("totalAppointments").asInt(), "one booking exists");
        assertEquals(1, summary.get("clients").asInt(), "one distinct client");
        assertEquals(0, summary.get("pending").asInt(), "pending cleared after accept");
        assertEquals(1, summary.get("confirmed").asInt(), "one accepted appointment");
        assertEquals(0, summary.get("finished").asInt(), "none completed yet");
        assertEquals(0, summary.get("cancelled").asInt(), "none cancelled");
        assertEquals("0.00", summary.get("totalRevenue").asText(), "no revenue before completion");
        assertEquals("Journey Braids", summary.get("popularServices").get(0).get("name").asText());
        assertEquals(1, summary.get("popularServices").get(0).get("count").asInt());

        // ---- 7b. Completed appointment drives revenue (net of commission) -----------
        // Set status directly: the completion-timing guard blocks completing a
        // future-dated appointment, so we assert the revenue math through the repo.
        BookAppointmentEntity completed = appointmentRepo.findByAppointmentId(appointmentId).orElseThrow();
        completed.setStatus("0");
        appointmentRepo.save(completed);
        JsonNode done = body(apiGet("/styler/business_summary", stylerToken)).get("data");
        assertEquals(1, done.get("finished").asInt(), "one completed appointment");
        assertEquals("85.00", done.get("totalRevenue").asText(), "gross revenue = service price");
        double gross = Double.parseDouble(done.get("totalRevenue").asText());
        double commission = Double.parseDouble(done.get("totalCommission").asText());
        double net = Double.parseDouble(done.get("netRevenue").asText());
        assertEquals(gross - commission, net, 0.001, "net = gross - commission");
        assertTrue(commission > 0, "commission must be positive with default setting");

        // ---- 7c. Admin can list per-stylist stats -----------------------------------
        JsonNode adminRows = body(apiGet("/admin/styler_business_summaries", adminToken)).get("data");
        assertNotNull(adminRows, "admin business summaries must return data");
        boolean found = false;
        for(JsonNode row : adminRows){
            if(stylerId.equals(row.get("stylerId").asText())){
                found = true;
                assertEquals("Journey Styles", row.get("businessName").asText());
                assertEquals(1, row.get("totalAppointments").asInt());
                break;
            }
        }
        assertTrue(found, "admin list must include the journey styler");

        // ---- 8. Role gates hold -----------------------------------------------------
        assertEquals(401, rawStatus(apiGet("/styler_own_sub_services", customerToken)),
                "customer token must not reach styler endpoints");
        assertEquals(401, rawStatus(apiGet("/styler/business_summary", customerToken)),
                "customer token must not reach the business summary");
        assertEquals(401, rawStatus(apiGet("/admin/styler_business_summaries", customerToken)),
                "customer token must not reach the admin summaries");
    }

    // ---- helpers ---------------------------------------------------------------

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

    private String signIn(String path, String email, String password) throws Exception {
        JsonNode response = body(apiPost(path, Map.of("emailAddress", email, "password", password), null));
        assertEquals("200", response.get("statusCode").asText(), "sign-in failed: " + response);
        String token = response.get("token").asText();
        assertFalse(token.isBlank(), "sign-in must return a token");
        return token;
    }

    private String firstListId(String path) throws Exception {
        JsonNode data = body(apiGet(path, null)).get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            return null;
        }
        JsonNode id = data.get(0).get("id");
        return id == null ? null : id.asText();
    }

    private String lastOtp() throws Exception {
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailConfig, atLeastOnce()).sendSimpleMail(anyString(), anyString(), bodyCaptor.capture());
        Matcher matcher = OTP_PATTERN.matcher(bodyCaptor.getValue());
        assertTrue(matcher.find(), "no 6-digit OTP found in email body: " + bodyCaptor.getValue());
        return matcher.group(1);
    }

    private void assertOk(MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("200", response.get("statusCode").asText(),
                "expected success, got: " + response);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String raw(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private int rawStatus(MvcResult result) throws Exception {
        return result.getResponse().getStatus();
    }

    private MvcResult apiPost(String path, Object payload, String token) throws Exception {
        MockHttpServletRequestBuilder request = post("/rapid_stylers" + path)
                .header("x-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON);
        if (payload != null) {
            request.content(objectMapper.writeValueAsString(payload));
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult apiGet(String path, String token) throws Exception {
        MockHttpServletRequestBuilder request = get("/rapid_stylers" + path)
                .header("x-api-key", apiKey);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
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
