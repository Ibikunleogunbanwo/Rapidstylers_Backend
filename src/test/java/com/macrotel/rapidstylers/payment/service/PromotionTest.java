package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.payment.entity.PromoCodeEntity;
import com.macrotel.rapidstylers.payment.repo.PromoCodeRepo;
import com.macrotel.rapidstylers.payment.repo.PromoRedemptionRepo;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Promo codes against the real database: what the admin may save, what a code
 * takes off, every limit a code can carry, and who may reach which endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PromotionTest {

    @Autowired PromotionService service;
    @Autowired PromoCodeRepo codeRepo;
    @Autowired PromoRedemptionRepo redemptionRepo;
    @Autowired BookAppointmentRepo appointmentRepo;
    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @MockBean EmailConfig emailConfig;
    @Value("${app.api.key}") String apiKey;

    private final String tag = "PT" + Long.toString(System.nanoTime(), 36).toUpperCase();
    private final String admin = "ADM" + tag;

    @AfterEach
    void cleanUp() {
        codeRepo.findAll().stream().filter(p -> p.getCode().startsWith(tag)).forEach(p -> {
            redemptionRepo.findByPromoIdAndStatus(p.getId(), "APPLIED").forEach(redemptionRepo::delete);
            redemptionRepo.findByPromoIdAndStatus(p.getId(), "RELEASED").forEach(redemptionRepo::delete);
            codeRepo.delete(p);
        });
        appointmentRepo.findAll().stream().filter(b -> b.getAppointmentId().startsWith(tag)).forEach(appointmentRepo::delete);
    }

    @Test
    void savingRefusesBadInputAndStoresDollarsAsCents() {
        assertEquals("400", service.save(admin, code("x!", "PERCENT", "10")).getStatusCode(), "bad characters");
        assertEquals("400", service.save(admin, code(tag + "A", "BOGO", "10")).getStatusCode(), "unknown type");
        assertEquals("400", service.save(admin, code(tag + "A", "PERCENT", "101")).getStatusCode(), "over 100%");
        assertEquals("400", service.save(admin, code(tag + "A", "PERCENT", "12.5")).getStatusCode(), "percent is whole");
        assertEquals("400", service.save(admin, code(tag + "A", "FIXED", "0")).getStatusCode(), "nothing off");
        assertEquals("400", service.save(admin, code(tag + "A", "FIXED", "ten")).getStatusCode());
        Map<String, Object> backwards = code(tag + "A", "FIXED", "5");
        backwards.put("startsAt", "2031-02-01");
        backwards.put("expiresAt", "2031-01-01");
        assertEquals("400", service.save(admin, backwards).getStatusCode(), "expires before it starts");

        Map<String, Object> body = code(tag.toLowerCase() + "a", "FIXED", "$12.50");
        body.put("minBooking", "40");
        assertEquals("200", service.save(admin, body).getStatusCode());
        PromoCodeEntity saved = codeRepo.findByCode(tag + "A").orElseThrow();
        assertEquals(1250L, saved.getDiscountValue(), "stored in cents, code upper-cased");
        assertEquals(4000L, saved.getMinBookingCents());
        assertEquals(1, saved.getMaxPerCustomer(), "one use per customer unless set");
        assertTrue(saved.isActive());
        assertEquals("400", service.save(admin, code(tag + "A", "FIXED", "5")).getStatusCode(), "codes are unique");
    }

    @Test
    void percentIsCappedFixedIsFlatAndTheChargeNeverDropsBelowStripesMinimum() {
        Map<String, Object> percent = code(tag + "P", "PERCENT", "20");
        percent.put("maxDiscount", "15");
        service.save(admin, percent);
        assertEquals(1000L, service.quote(tag + "P", "U" + tag, 5000).discountCents, "20% of $50");
        assertEquals(1500L, service.quote(tag + "P", "U" + tag, 20000).discountCents, "capped at $15");

        service.save(admin, code(tag + "F", "FIXED", "30"));
        assertEquals(3000L, service.quote(tag + "F", "U" + tag, 10000).discountCents);
        assertEquals(1950L, service.quote(tag + "F", "U" + tag, 2000).discountCents, "leaves $0.50 to charge");
        assertFalse(service.quote(tag + "F", "U" + tag, 50).ok, "nothing to take off");
        assertFalse(service.quote("NOPE" + tag, "U" + tag, 10000).ok);
        assertEquals(1000L, service.quote(" " + tag.toLowerCase() + "p ", "U" + tag, 5000).discountCents, "typed loosely");
    }

    @Test
    void startExpiryActiveAndMinimumBookingAreChecked() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> later = code(tag + "L", "FIXED", "5");
        later.put("startsAt", now.plusDays(1).toString());
        service.save(admin, later);
        assertEquals("That promo code isn't active yet", service.quote(tag + "L", "U" + tag, 10000).message);

        Map<String, Object> old = code(tag + "E", "FIXED", "5");
        old.put("startsAt", now.minusDays(3).toString());
        old.put("expiresAt", now.minusDays(1).toString());
        service.save(admin, old);
        assertEquals("That promo code has expired", service.quote(tag + "E", "U" + tag, 10000).message);

        Map<String, Object> off = code(tag + "O", "FIXED", "5");
        off.put("active", false);
        service.save(admin, off);
        assertFalse(service.quote(tag + "O", "U" + tag, 10000).ok);

        Map<String, Object> min = code(tag + "M", "FIXED", "5");
        min.put("minBooking", "60.00");
        service.save(admin, min);
        assertFalse(service.quote(tag + "M", "U" + tag, 5999).ok);
        assertTrue(service.quote(tag + "M", "U" + tag, 6000).ok);
    }

    @Test
    void usesAreLimitedInAllAndPerCustomerAndAReleasedUseComesBack() {
        Map<String, Object> body = code(tag + "T", "FIXED", "5");
        body.put("maxRedemptions", "2");
        service.save(admin, body);

        assertTrue(service.redeem(tag + "T", "U1" + tag, tag + "B1", 10000).ok);
        assertFalse(service.redeem(tag + "T", "U1" + tag, tag + "B2", 10000).ok, "one per customer");
        assertTrue(service.redeem(tag + "T", "U2" + tag, tag + "B3", 10000).ok);
        assertEquals("That promo code has been fully used",
                service.redeem(tag + "T", "U3" + tag, tag + "B4", 10000).message);
        assertEquals(500L, service.discountOn(tag + "B1"));

        service.release(tag + "B1");
        assertEquals(0L, service.discountOn(tag + "B1"));
        assertTrue(service.redeem(tag + "T", "U3" + tag, tag + "B4", 10000).ok, "the cancelled booking's use is free again");

        Map<String, Object> rename = code(tag + "T2", "FIXED", "5");
        rename.put("id", codeRepo.findByCode(tag + "T").orElseThrow().getId());
        assertEquals("This code has been used, so it can't be renamed", service.save(admin, rename).getMessage());
    }

    @Test
    void firstBookingOnlyCodesRefuseCustomersWhoHaveBookedBefore() {
        Map<String, Object> body = code(tag + "N", "PERCENT", "10");
        body.put("firstBookingOnly", true);
        service.save(admin, body);
        String customer = "U" + tag;
        assertTrue(service.quote(tag + "N", customer, 10000).ok);

        appointmentRepo.save(booking(tag + "OLD", customer));
        assertEquals("This promo code is for your first booking only", service.quote(tag + "N", customer, 10000).message);
        assertTrue(service.redeem(tag + "N", customer, tag + "OLD", 10000).ok,
                "inside a booking, the booking being made doesn't count against it");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAdminListShowsUsesAndDiscountGiven() {
        service.save(admin, code(tag + "S", "FIXED", "7.25"));
        service.redeem(tag + "S", "U1" + tag, tag + "B1", 10000);
        service.redeem(tag + "S", "U2" + tag, tag + "B2", 10000);
        service.release(tag + "B2");

        Map<String, Object> row = ((List<Map<String, Object>>) service.list().getData()).stream()
                .filter(m -> (tag + "S").equals(m.get("code"))).findFirst().orElseThrow();
        assertEquals(1, row.get("redemptions"));
        assertEquals(new BigDecimal("7.25"), row.get("discountGiven"));
        assertEquals(new BigDecimal("7.25"), row.get("discountValue"));

        BaseResponse uses = service.redemptions((Long) row.get("id"));
        assertEquals(2, ((List<?>) uses.getData()).size(), "released uses stay in the history");
        assertEquals("404", service.redemptions(-1L).getStatusCode());
    }

    @Test
    void thePlatformPaysTheDiscountSoTheStylistIsPaidOnTheFullPrice() {
        assertEquals(15000L, PromotionService.stylistBaseCents(2500L, 12500, 12500), "$125 charged + $25 off");
        assertEquals(12000L, PromotionService.stylistBaseCents(2500L, 10000, 12500), "a fifth refunded takes a fifth of the discount");
        assertEquals(0L, PromotionService.stylistBaseCents(2500L, 0, 12500), "fully refunded");
        assertEquals(9000L, PromotionService.stylistBaseCents(null, 9000, 9000), "no code");
    }

    @Test
    void customersCheckCodesAndOnlyAdminsManageThem() throws Exception {
        service.save(admin, code(tag + "C", "PERCENT", "10"));
        String customer = jwtUtil.generateToken("U" + tag, "CUSTOMER");

        mockMvc.perform(post("/rapid_stylers/check_promo_code").header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + tag + "C\",\"subtotal\":\"80.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.discount").value(8.0))
                .andExpect(jsonPath("$.data.total").value(72.0));

        mockMvc.perform(get("/rapid_stylers/admin/promo_codes").header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/rapid_stylers/admin/save_promo_code").header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + tag + "Z\",\"discountType\":\"FIXED\",\"discountValue\":\"5\"}"))
                .andExpect(status().isUnauthorized());
        assertTrue(codeRepo.findByCode(tag + "Z").isEmpty());

        mockMvc.perform(post("/rapid_stylers/admin/save_promo_code").header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + tag + "Z\",\"discountType\":\"FIXED\",\"discountValue\":\"5\"}"))
                .andExpect(status().isOk());
        assertTrue(codeRepo.findByCode(tag + "Z").isPresent());
    }

    private static Map<String, Object> code(String code, String type, String value) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("discountType", type);
        m.put("discountValue", value);
        return m;
    }

    private BookAppointmentEntity booking(String id, String userId) {
        BookAppointmentEntity b = new BookAppointmentEntity();
        b.setAppointmentId(id);
        b.setUserId(userId);
        b.setStylerId(tag + "ST");
        b.setSubServiceId("999999");
        b.setStatus("0");
        LocalDate date = LocalDate.of(2031, 3, 2);
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
