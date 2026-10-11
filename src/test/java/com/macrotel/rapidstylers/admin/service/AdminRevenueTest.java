package com.macrotel.rapidstylers.admin.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.payment.entity.RefundEntity;
import com.macrotel.rapidstylers.payment.repo.RefundRepo;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Revenue reports against the real database: money counts on the Edmonton day
 * the card was charged, refunds on the day they went through, the previous
 * period is the same length just before, and breakdowns group the charges.
 * Dates are in 2031 so other tests' rows never fall in range.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminRevenueTest {

    @Autowired AdminRevenueService service;
    @Autowired BookAppointmentRepo appointmentRepo;
    @Autowired StylerRepo stylerRepo;
    @Autowired RefundRepo refundRepo;
    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @MockBean EmailConfig emailConfig;
    @Value("${app.api.key}") String apiKey;

    private final String tag = "AR" + Long.toString(System.nanoTime(), 36).toUpperCase();
    private final String stylerOn = tag + "ON";
    private final String stylerAb = tag + "AL";

    @BeforeEach
    void seed() {
        stylerRepo.save(stylist(stylerOn, "Ontario"));
        stylerRepo.save(stylist(stylerAb, "Alberta"));
        // Settled at completion: fee and share fixed.
        BookAppointmentEntity settled = booking(tag + "1", stylerOn, "100.00", utc(2031, 3, 2, 18));
        settled.setPlatformFeeCents(1500L);
        settled.setStylistShareCents(8500L);
        appointmentRepo.save(settled);
        // 05:00 UTC on the 3rd is 22:00 on the 2nd in Edmonton; fee estimated from the snapped rate.
        BookAppointmentEntity lateEvening = booking(tag + "2", stylerAb, "50.00", utc(2031, 3, 3, 5));
        lateEvening.setCommissionPercent(10.0);
        appointmentRepo.save(lateEvening);
        appointmentRepo.save(booking(tag + "3", stylerAb, "40.00", utc(2031, 2, 25, 18)));
        appointmentRepo.save(booking(tag + "4", stylerAb, "999.00", null));

        RefundEntity refund = new RefundEntity();
        refund.setRefundId(tag + "R1");
        refund.setAppointmentId(tag + "1");
        refund.setPaymentIntentId("pi_" + tag);
        refund.setAmount("20.00");
        refund.setStatus("COMPLETED");
        refund.setCreatedBy("ADMIN");
        refund.setCreatedAt("2031-03-05 11:00:00");
        refund.setCompletedAt(LocalDateTime.of(2031, 3, 5, 18, 0).atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        refundRepo.save(refund);
    }

    @AfterEach
    void cleanUp() {
        refundRepo.findByRefundId(tag + "R1").ifPresent(refundRepo::delete);
        appointmentRepo.findAll().stream().filter(b -> b.getAppointmentId().startsWith(tag)).forEach(appointmentRepo::delete);
        stylerRepo.findByStylerId(stylerOn).ifPresent(stylerRepo::delete);
        stylerRepo.findByStylerId(stylerAb).ifPresent(stylerRepo::delete);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWeekOfRevenueWithThePreviousWeekForComparison() {
        BaseResponse r = service.report("2031-03-01", "2031-03-07", null);
        assertEquals("200", r.getStatusCode());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("CAD", data.get("currency"));
        assertEquals("America/Edmonton", data.get("timeZone"));
        assertEquals("day", data.get("groupBy"));

        Map<String, Object> s = (Map<String, Object>) data.get("summary");
        assertEquals(2L, s.get("charges"), "the uncharged booking does not count");
        assertEquals(new BigDecimal("150.00"), s.get("capturedValue"));
        assertEquals(new BigDecimal("20.00"), s.get("refunds"));
        assertEquals(new BigDecimal("130.00"), s.get("netValue"));
        assertEquals(new BigDecimal("20.00"), s.get("platformRevenue"), "15.00 settled + 5.00 estimated at 10%");
        assertEquals(new BigDecimal("130.00"), s.get("stylistEarnings"));

        Map<String, Object> prev = (Map<String, Object>) data.get("previousPeriod");
        assertEquals("2031-02-22", prev.get("from"));
        assertEquals("2031-02-28", prev.get("to"));
        assertEquals(new BigDecimal("40.00"), ((Map<String, Object>) prev.get("summary")).get("capturedValue"));
        assertEquals(new BigDecimal("275.0"), ((Map<String, Object>) data.get("changePercent")).get("capturedValue"));

        List<Map<String, Object>> trend = (List<Map<String, Object>>) data.get("trend");
        assertEquals(7, trend.size(), "every day, empty ones included");
        assertEquals("2031-03-02", trend.get(1).get("periodStart"));
        assertEquals(2L, trend.get(1).get("charges"), "22:00 Edmonton counts on the 2nd, not the UTC 3rd");
        assertEquals(new BigDecimal("20.00"), trend.get(4).get("refunds"));

        List<Map<String, Object>> provinces = (List<Map<String, Object>>) data.get("byProvince");
        assertEquals("Ontario", provinces.get(0).get("name"));
        assertEquals(new BigDecimal("100.00"), provinces.get(0).get("capturedValue"));
        assertEquals("Alberta", provinces.get(1).get("name"));
        assertFalse(((List<?>) data.get("byServiceType")).isEmpty());
        assertNotNull(data.get("definitions"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void weeksStartOnMondayAndAreClippedToTheRange() {
        Map<String, Object> data = (Map<String, Object>) service.report("2031-03-01", "2031-03-31", "week").getData();
        List<Map<String, Object>> trend = (List<Map<String, Object>>) data.get("trend");
        assertEquals("2031-03-01", trend.get(0).get("periodStart"), "1 March 2031 is a Saturday");
        assertEquals("2031-03-02", trend.get(0).get("periodEnd"));
        assertEquals("2031-03-03", trend.get(1).get("periodStart"));
        assertEquals("2031-03-31", trend.get(trend.size() - 1).get("periodEnd"));
    }

    @Test
    void badRangesAreRefused() {
        assertEquals("400", service.report("2031-03-07", "2031-03-01", null).getStatusCode());
        assertEquals("400", service.report("2031-03-01", "2031-03-07", "year").getStatusCode());
        assertEquals("400", service.report("March", null, null).getStatusCode());
        assertEquals("400", service.report("2020-01-01", "2031-01-01", "month").getStatusCode());
    }

    @Test
    void theFirstCaptureTimeIsKept() {
        BookAppointmentEntity b = new BookAppointmentEntity();
        b.markCaptured();
        LocalDateTime first = b.getPaymentCapturedAt();
        assertNotNull(first);
        b.setPaymentStatus("DISPUTED");
        b.markCaptured();
        assertEquals("CAPTURED", b.getPaymentStatus());
        assertEquals(first, b.getPaymentCapturedAt());
    }

    @Test
    void onlyAdminsSeeRevenue() throws Exception {
        String url = "/rapid_stylers/admin/revenue_export?from=2031-03-01&to=2031-03-07";
        mockMvc.perform(get(url).header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(tag, "CUSTOMER")))
                .andExpect(status().isUnauthorized());
        String csv = mockMvc.perform(get(url).header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken("ADM" + tag, "ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(8, csv.split("\n").length, "header plus seven days");
    }

    private static LocalDateTime utc(int y, int m, int d, int hour) {
        return LocalDateTime.of(y, m, d, hour, 0);
    }

    private StylerEntity stylist(String id, String province) {
        StylerEntity s = new StylerEntity();
        s.setStylerId(id);
        s.setProvince(province);
        s.setBusinessName(province + " Studio");
        s.setFirstname("Test");
        s.setLastname("Stylist");
        s.setEmailAddress(id.toLowerCase() + "@rapidstylers.test");
        s.setServiceTypeId("1");
        s.setStatus("0");
        return s;
    }

    private BookAppointmentEntity booking(String id, String stylerId, String amount, LocalDateTime capturedUtc) {
        BookAppointmentEntity b = new BookAppointmentEntity();
        b.setAppointmentId(id);
        b.setUserId(tag + "CU");
        b.setStylerId(stylerId);
        b.setSubServiceId("999999");
        b.setStatus("3");
        LocalDate date = LocalDate.of(2031, 4, 1);
        b.setAppointmentDate(date.toString());
        b.setAppointmentDateValue(date);
        b.setArrivalTime("10:00");
        b.setAppointmentStartTime(LocalTime.of(10, 0));
        b.setAppointmentEndTime(LocalTime.of(11, 0));
        b.setDurationMinutes(60);
        b.setPrice(amount);
        b.setServicePrice(amount);
        b.setTravelFee("0.00");
        b.setPaymentAmount(amount);
        b.setPaymentStatus(capturedUtc == null ? "AUTHORIZED" : "CAPTURED");
        b.setPaymentCapturedAt(capturedUtc);
        b.setCreatedAt("03 01, 2031 09:00:00");
        return b;
    }
}
