package com.macrotel.rapidstylers.admin.service;

import com.macrotel.rapidstylers.common.entity.FunnelEventEntity;
import com.macrotel.rapidstylers.common.repo.FunnelEventRepo;
import com.macrotel.rapidstylers.common.service.FunnelService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sign-up funnel against the real database: people count once per step,
 * conversions compare neighbouring steps, a role narrows every step but
 * visits, recording waits for the caller's commit, and the visit beacon is
 * public while the report is admin-only. Report rows are dated 2031 so other
 * tests' events never fall in range.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminFunnelTest {

    @Autowired AdminFunnelService service;
    @Autowired FunnelService funnelService;
    @Autowired FunnelEventRepo repo;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @MockBean EmailConfig emailConfig;
    @Value("${app.api.key}") String apiKey;

    private final String tag = "AF" + Long.toString(System.nanoTime(), 36).toUpperCase();

    @AfterEach
    void cleanUp() {
        repo.findAll().stream()
                .filter(e -> e.getDedupeKey().contains(tag) || e.getSubject().startsWith(tag)
                        || e.getSubject().equals(FunnelService.sha256(("visitor-" + tag).toLowerCase())))
                .forEach(repo::delete);
    }

    @Test
    @SuppressWarnings("unchecked")
    void peopleCountOncePerStepAndConversionsCompareNeighbours() {
        event("VISITED", null, "v1", "2031-05-01");
        event("VISITED", null, "v1", "2031-05-02");
        event("VISITED", null, "v2", "2031-05-02");
        event("VISITED", null, "v3", "2031-05-03");
        event("VISITED", null, "v4", "2031-05-07");
        event("VISITED", null, "v5", "2031-04-28");
        event("SIGNUP_STARTED", "CUSTOMER", "a", "2031-05-02");
        event("SIGNUP_STARTED", "CUSTOMER", "b", "2031-05-03");
        event("SIGNUP_STARTED", "STYLIST", "s", "2031-05-03");
        event("EMAIL_VERIFIED", "CUSTOMER", "a", "2031-05-02");
        event("ACCOUNT_CREATED", "CUSTOMER", "a", "2031-05-02");
        event("FIRST_BOOKING", "CUSTOMER", "a", "2031-05-04");

        BaseResponse r = service.report("2031-05-01", "2031-05-07", null, null);
        assertEquals("200", r.getStatusCode());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> steps = (List<Map<String, Object>>) data.get("steps");
        assertEquals(4L, steps.get(0).get("count"), "v1 came twice but counts once");
        assertNull(steps.get(0).get("conversionFromPrevious"));
        assertEquals(3L, steps.get(1).get("count"));
        assertEquals(new BigDecimal("75.0"), steps.get(1).get("conversionFromPrevious"));
        assertEquals(1L, steps.get(2).get("count"));
        assertEquals(new BigDecimal("33.3"), steps.get(2).get("conversionFromPrevious"));
        assertEquals(new BigDecimal("25.0"), steps.get(4).get("conversionFromStart"));

        List<Map<String, Object>> before = (List<Map<String, Object>>)
                ((Map<String, Object>) data.get("previousPeriod")).get("steps");
        assertEquals(1L, before.get(0).get("count"));

        List<Map<String, Object>> trend = (List<Map<String, Object>>) data.get("trend");
        assertEquals(7, trend.size());
        assertEquals(2L, trend.get(1).get("VISITED"), "v1 and v2 on the 2nd");

        Map<String, Object> customers = (Map<String, Object>) service.report("2031-05-01", "2031-05-07", null, "customer").getData();
        List<Map<String, Object>> customerSteps = (List<Map<String, Object>>) customers.get("steps");
        assertEquals(4L, customerSteps.get(0).get("count"), "visits count for every role");
        assertEquals(2L, customerSteps.get(1).get("count"), "the stylist sign-up is left out");

        assertEquals("400", service.report("2031-05-01", "2031-05-07", null, "robot").getStatusCode());
    }

    @Test
    void recordingDedupesAndWaitsForTheCommit() {
        String visitor = "visitor-" + tag;
        funnelService.record(FunnelService.VISITED, null, visitor);
        funnelService.record(FunnelService.VISITED, null, visitor);
        assertEquals(1, rowsFor(visitor), "same browser, same day: one visit");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            funnelService.record(FunnelService.ACCOUNT_CREATED, "CUSTOMER", tag + "-rolled-back");
            status.setRollbackOnly();
        });
        assertEquals(0, rowsFor(tag + "-rolled-back"), "a rolled-back sign-up is not counted");

        tx.executeWithoutResult(status -> funnelService.record(FunnelService.ACCOUNT_CREATED, "CUSTOMER", tag + "-kept"));
        assertEquals(1, rowsFor(tag + "-kept"));
        repo.findAll().stream().filter(e -> e.getSubject().equals(FunnelService.sha256((tag + "-kept").toLowerCase()))
                || e.getSubject().equals(FunnelService.sha256((tag + "-rolled-back").toLowerCase()))).forEach(repo::delete);
    }

    @Test
    void theBeaconIsPublicAndTheReportIsAdminOnly() throws Exception {
        mockMvc.perform(post("/rapid_stylers/funnel_visit").header("x-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"visitorId\":\"<script>\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/rapid_stylers/funnel_visit").header("x-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"visitorId\":\"visitor-" + tag + "\"}"))
                .andExpect(status().isOk());
        assertEquals(1, rowsFor("visitor-" + tag));

        mockMvc.perform(get("/rapid_stylers/admin/signup_funnel").header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(tag, "CUSTOMER")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/rapid_stylers/admin/signup_funnel").header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken("ADM" + tag, "ADMIN")))
                .andExpect(status().isOk());
    }

    private long rowsFor(String rawSubject) {
        String hashed = FunnelService.sha256(rawSubject.toLowerCase());
        return repo.findAll().stream().filter(e -> e.getSubject().equals(hashed)).count();
    }

    private void event(String step, String role, String who, String day) {
        FunnelEventEntity e = new FunnelEventEntity();
        e.setStep(step);
        e.setRole(role);
        e.setSubject(tag + who);
        e.setDayKey(day);
        e.setOccurredAt(LocalDateTime.parse(day + "T12:00:00"));
        e.setDedupeKey(step + ":" + tag + who + ":" + day);
        repo.save(e);
    }
}
