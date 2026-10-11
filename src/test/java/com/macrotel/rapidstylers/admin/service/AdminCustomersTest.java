package com.macrotel.rapidstylers.admin.service;

import com.macrotel.rapidstylers.auth.service.RefreshTokenService;
import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.common.repo.AuditLogRepo;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin customers against the real database: search by text and status with
 * booking counts, one account in full, and suspend/reinstate that needs a
 * reason, signs the customer out and is audited.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminCustomersTest {

    @Autowired AdminCustomerService service;
    @Autowired UserRepo userRepo;
    @Autowired BookAppointmentRepo appointmentRepo;
    @Autowired AuditLogRepo auditLogRepo;
    @Autowired RefreshTokenService refreshTokenService;
    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @MockBean EmailConfig emailConfig;
    @Value("${app.api.key}") String apiKey;

    private final String tag = "AC" + Long.toString(System.nanoTime(), 36).toUpperCase();
    private final String ada = tag + "ADA";
    private final String bo = tag + "BO";

    @BeforeEach
    void seed() {
        userRepo.save(user(ada, "Ada", "Lovelace", "0"));
        userRepo.save(user(bo, "Bo", "Diddley", "SUSPENDED"));
        appointmentRepo.save(booking(tag + "1", LocalDate.of(2031, 3, 2)));
        appointmentRepo.save(booking(tag + "2", LocalDate.of(2031, 6, 9)));
    }

    @AfterEach
    void cleanUp() {
        appointmentRepo.findAll().stream().filter(b -> b.getAppointmentId().startsWith(tag)).forEach(appointmentRepo::delete);
        userRepo.findByUserId(ada).ifPresent(userRepo::delete);
        userRepo.findByUserId(bo).ifPresent(userRepo::delete);
    }

    @Test
    void searchMatchesTextAndStatusWithBookingCounts() {
        BaseResponse both = service.search(tag.toLowerCase(), null, 0, 25);
        assertEquals("200", both.getStatusCode());
        assertEquals(List.of(bo, ada), userIds(both), "newest account first");

        Map<?, ?> adaRow = items(service.search(tag + "ADA", null, 0, 25)).get(0);
        assertEquals("ACTIVE", adaRow.get("status"));
        assertEquals(2L, adaRow.get("bookingCount"));
        assertEquals("2031-06-09", adaRow.get("lastAppointmentDate"));

        assertEquals(List.of(bo), userIds(service.search(tag, "suspended", 0, 25)));
        assertEquals(List.of(ada), userIds(service.search(tag, "ACTIVE", 0, 25)));
        assertEquals(List.of(), userIds(service.search(tag + "%", null, 0, 25)), "% is matched literally");
        assertEquals("400", service.search(tag, "banned", 0, 25).getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailShowsTheAccountWithItsBookingsAndUnknownIdsAre404() {
        BaseResponse found = service.detail(ada);
        assertEquals("200", found.getStatusCode());
        Map<String, Object> data = (Map<String, Object>) found.getData();
        assertEquals("Ada", data.get("firstname"));
        assertEquals(2, ((List<?>) data.get("bookings")).size());
        assertEquals(2, ((Map<?, ?>) data.get("bookingTotals")).get("count"));
        assertNotNull(data.get("history"));
        assertEquals("404", service.detail(tag + "nope").getStatusCode());
    }

    @Test
    void suspendNeedsAReasonSignsTheCustomerOutAndIsAudited() {
        assertEquals("400", service.setSuspended("ADM" + tag, ada, true, " ").getStatusCode());
        assertEquals("0", userRepo.findByUserId(ada).orElseThrow().getStatus(), "no reason, no change");

        String refresh = refreshTokenService.issue(ada, "CUSTOMER");
        assertNotNull(refreshTokenService.validate(refresh));

        BaseResponse r = service.setSuspended("ADM" + tag, ada, true, "chargeback fraud");
        assertEquals("200", r.getStatusCode());
        assertEquals("SUSPENDED", userRepo.findByUserId(ada).orElseThrow().getStatus());
        assertNull(refreshTokenService.validate(refresh), "suspension signs them out");
        assertTrue(auditLogRepo.findTop100ByOrderByCreatedAtDesc().stream()
                .anyMatch(a -> "ADMIN_CUSTOMER_SUSPENDED".equals(a.getAction())
                        && ada.equals(a.getResourceId()) && a.getDetails().contains("chargeback fraud")));

        assertEquals("400", service.setSuspended("ADM" + tag, ada, true, "again").getStatusCode(), "already suspended");
        assertEquals("404", service.setSuspended("ADM" + tag, tag + "nope", true, "x").getStatusCode());
    }

    @Test
    void reinstateRestoresSignIn() {
        BaseResponse r = service.setSuspended("ADM" + tag, bo, false, "appeal accepted");
        assertEquals("200", r.getStatusCode());
        assertEquals("0", userRepo.findByUserId(bo).orElseThrow().getStatus());
        assertTrue(auditLogRepo.findTop100ByOrderByCreatedAtDesc().stream()
                .anyMatch(a -> "ADMIN_CUSTOMER_REINSTATED".equals(a.getAction()) && bo.equals(a.getResourceId())));
    }

    @Test
    void onlyAdminsMaySuspend() throws Exception {
        String body = "{\"userId\":\"" + ada + "\",\"reason\":\"test\"}";
        mockMvc.perform(post("/rapid_stylers/admin/suspend_customer").header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(bo, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertEquals("0", userRepo.findByUserId(ada).orElseThrow().getStatus());

        mockMvc.perform(post("/rapid_stylers/admin/suspend_customer").header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken("ADM" + tag, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertEquals("SUSPENDED", userRepo.findByUserId(ada).orElseThrow().getStatus());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> items(BaseResponse r) {
        return (List<Map<?, ?>>) ((Map<String, Object>) r.getData()).get("items");
    }

    private static List<String> userIds(BaseResponse r) {
        return items(r).stream().map(i -> (String) i.get("userId")).collect(Collectors.toList());
    }

    private UserEntity user(String id, String first, String last, String status) {
        UserEntity u = new UserEntity();
        u.setUserId(id);
        u.setFirstname(first);
        u.setLastname(last);
        u.setEmailAddress(id.toLowerCase() + "@rapidstylers.test");
        u.setStatus(status);
        return u;
    }

    private BookAppointmentEntity booking(String id, LocalDate date) {
        BookAppointmentEntity b = new BookAppointmentEntity();
        b.setAppointmentId(id);
        b.setUserId(ada);
        b.setStylerId(tag + "ST");
        b.setSubServiceId("999999");
        b.setStatus("0");
        b.setAppointmentDate(date.toString());
        b.setAppointmentDateValue(date);
        b.setArrivalTime("10:00");
        b.setAppointmentStartTime(LocalTime.of(10, 0));
        b.setAppointmentEndTime(LocalTime.of(11, 0));
        b.setDurationMinutes(60);
        b.setPrice("50.00");
        b.setServicePrice("50.00");
        b.setTravelFee("0.00");
        b.setCreatedAt("03 01, 2031 09:00:00");
        return b;
    }
}
