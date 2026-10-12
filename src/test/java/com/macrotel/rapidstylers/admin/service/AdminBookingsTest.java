package com.macrotel.rapidstylers.admin.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.common.repo.AuditLogRepo;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The admin bookings ledger against the real database: filters narrow the
 * list, totals cover the whole filtered set, the detail view labels the
 * booking, and the CSV export is admin-only, spreadsheet-safe and audited.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminBookingsTest {

    @Autowired AdminBookingService service;
    @Autowired BookAppointmentRepo appointmentRepo;
    @Autowired StylerRepo stylerRepo;
    @Autowired UserRepo userRepo;
    @Autowired AuditLogRepo auditLogRepo;
    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @MockBean EmailConfig emailConfig;
    @Value("${app.api.key}") String apiKey;

    private final String tag = "AB" + Long.toString(System.nanoTime(), 36).toUpperCase();
    private final String stylerOn = tag + "ON";
    private final String stylerAb = tag + "AL";
    private final String customer = tag + "CU";

    @BeforeEach
    void seed() {
        stylerRepo.save(stylist(stylerOn, "Ontario", "Toronto Braids"));
        stylerRepo.save(stylist(stylerAb, "Alberta", "Calgary Cuts"));
        UserEntity user = new UserEntity();
        user.setUserId(customer);
        user.setFirstname("=Ada");
        user.setLastname("Lovelace");
        user.setEmailAddress(tag.toLowerCase() + "@rapidstylers.test");
        user.setStatus("0");
        userRepo.save(user);
        appointmentRepo.save(booking(tag + "1", stylerOn, "1", LocalDate.of(2031, 3, 2), "100.00", null));
        appointmentRepo.save(booking(tag + "2", stylerOn, "0", LocalDate.of(2031, 3, 9), "1,200.50", 18000L));
        appointmentRepo.save(booking(tag + "3", stylerAb, "4", LocalDate.of(2031, 4, 1), "80.00", null));
    }

    @AfterEach
    void cleanUp() {
        appointmentRepo.findAll().stream().filter(b -> b.getAppointmentId().startsWith(tag)).forEach(appointmentRepo::delete);
        stylerRepo.findByStylerId(stylerOn).ifPresent(stylerRepo::delete);
        stylerRepo.findByStylerId(stylerAb).ifPresent(stylerRepo::delete);
        userRepo.findByUserId(customer).ifPresent(userRepo::delete);
    }

    @Test
    void filtersNarrowTheListAndTotalsCoverTheWholeSet() {
        AdminBookingService.Filters byCustomer = filters();
        BaseResponse all = service.list(byCustomer, 0, 2);
        assertEquals("200", all.getStatusCode());
        Map<String, Object> data = data(all);
        assertEquals(3L, data.get("total"));
        assertEquals(2, ((List<?>) data.get("items")).size(), "page size applies to items");
        Map<?, ?> totals = (Map<?, ?>) data.get("totals");
        assertEquals(3, totals.get("count"), "totals cover every match, not just the page");
        assertEquals(new BigDecimal("1380.50"), totals.get("bookingValue"));
        assertEquals(new BigDecimal("180.00"), totals.get("platformFees"));

        AdminBookingService.Filters ontario = filters();
        ontario.setProvince("ontario");
        assertEquals(List.of(tag + "2", tag + "1"), ids(service.list(ontario, 0, 25)), "newest appointment first");

        AdminBookingService.Filters pendingOrCancelled = filters();
        pendingOrCancelled.setStatus("pending,4");
        assertEquals(List.of(tag + "3", tag + "1"), ids(service.list(pendingOrCancelled, 0, 25)));

        AdminBookingService.Filters march = filters();
        march.setFrom("2031-03-05");
        march.setTo("2031-03-31");
        assertEquals(List.of(tag + "2"), ids(service.list(march, 0, 25)));

        AdminBookingService.Filters nowhere = filters();
        nowhere.setProvince("Yukon");
        assertEquals(List.of(), ids(service.list(nowhere, 0, 25)));
    }

    @Test
    void rowsCarryNamesStatusNamesAndTheStylistsZone() {
        AdminBookingService.Filters one = filters();
        one.setAppointmentId(tag + "2");
        Map<?, ?> row = (Map<?, ?>) ((List<?>) data(service.list(one, 0, 25)).get("items")).get(0);
        assertEquals("COMPLETED", row.get("status"));
        assertEquals("CAD", row.get("currency"));
        assertEquals(new BigDecimal("1200.50"), row.get("price"));
        assertEquals("=Ada Lovelace", ((Map<?, ?>) row.get("customer")).get("name"));
        Map<?, ?> stylist = (Map<?, ?>) row.get("stylist");
        assertEquals("Toronto Braids", stylist.get("name"));
        assertEquals("America/Toronto", stylist.get("timeZone"));
    }

    @Test
    void badFiltersAreRefusedWithAReason() {
        AdminBookingService.Filters bad = filters();
        bad.setStatus("teleported");
        BaseResponse r = service.list(bad, 0, 25);
        assertEquals("400", r.getStatusCode());
        assertTrue(r.getMessage().contains("teleported"));

        AdminBookingService.Filters backwards = filters();
        backwards.setFrom("2031-05-01");
        backwards.setTo("2031-04-01");
        assertEquals("400", service.list(backwards, 0, 25).getStatusCode());
    }

    @Test
    void detailShowsOneBookingAndUnknownIdsAre404() {
        BaseResponse found = service.detail(tag + "2");
        assertEquals("200", found.getStatusCode());
        assertEquals(tag + "2", data(found).get("appointmentId"));
        assertNotNull(data(found).get("history"));
        assertEquals("404", service.detail(tag + "nope").getStatusCode());
    }

    @Test
    void exportIsAdminOnlySpreadsheetSafeAndAudited() throws Exception {
        String url = "/rapid_stylers/admin/bookings_export?userId=" + customer;
        mockMvc.perform(get(url).header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(customer, "CUSTOMER")))
                .andExpect(status().isUnauthorized());

        String admin = "ADM" + tag;
        String csv = mockMvc.perform(get(url).header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andReturn().getResponse().getContentAsString();
        String[] lines = csv.split("\n");
        assertEquals(4, lines.length, "header plus three bookings");
        assertTrue(lines[0].startsWith("appointmentId,"));
        assertTrue(csv.contains("\"'=Ada Lovelace\""), "a cell starting with = is opened as text");
        assertTrue(auditLogRepo.findTop100ByOrderByCreatedAtDesc().stream()
                .anyMatch(a -> "ADMIN_BOOKINGS_EXPORT".equals(a.getAction()) && admin.equals(a.getActorId())));
    }

    /** The money keys a manager must never receive, at any depth. */
    private static final java.util.regex.Pattern MONEY = java.util.regex.Pattern.compile(
            "\"(price|travelFee|paymentAmount|platformFee|stylistShare|currency|commissionPercent|paymentIntentId"
            + "|paymentFailureCode|stripeTransferId|payoutDueAt|payoutHoldReason|refunds|refundedTotal|bookingValue"
            + "|platformFees|stylistEarnings|history)\"\\s*:");

    private String read(String url, String role) throws Exception {
        return mockMvc.perform(get(url).header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(tag.toLowerCase() + "@staff.test", role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aManagerSeesBookingsCustomersAndCountsWithoutMoney() throws Exception {
        String[] urls = {
                "/rapid_stylers/admin/bookings?userId=" + customer,
                "/rapid_stylers/admin/booking_detail?appointmentId=" + tag + "2",
                "/rapid_stylers/admin/customers?q=" + tag.toLowerCase(),
                "/rapid_stylers/admin/customer_detail?userId=" + customer,
                "/rapid_stylers/admin/kpis",
        };
        for (String url : urls) {
            String body = read(url, "MANAGER");
            assertFalse(MONEY.matcher(body).find(), "money in a manager's answer from " + url + ": " + body);
        }
        String list = read(urls[0], "MANAGER");
        assertTrue(list.contains("\"paymentStatus\""), "status words stay");
        assertTrue(list.contains("\"count\":3"), "the booking count stays");
        assertTrue(read(urls[1], "MANAGER").contains("\"serviceAddress\""));
        assertTrue(read(urls[3], "MANAGER").contains("\"bookings\""));
        assertTrue(read(urls[4], "MANAGER").contains("\"customers\""));
    }

    @Test
    void anOwnerStillSeesTheMoney() throws Exception {
        String list = read("/rapid_stylers/admin/bookings?userId=" + customer, "ADMIN");
        assertTrue(list.contains("\"price\""));
        assertTrue(list.contains("\"bookingValue\""));
        assertTrue(list.contains("\"platformFee\""));
        String detail = read("/rapid_stylers/admin/booking_detail?appointmentId=" + tag + "2", "ADMIN");
        assertTrue(detail.contains("\"refundedTotal\""));
        assertTrue(detail.contains("\"paymentIntentId\""));
        assertTrue(detail.contains("\"history\""));
        String customerDetail = read("/rapid_stylers/admin/customer_detail?userId=" + customer, "ADMIN");
        assertTrue(customerDetail.contains("\"bookingValue\""));
    }

    private AdminBookingService.Filters filters() {
        AdminBookingService.Filters f = new AdminBookingService.Filters();
        f.setUserId(customer);
        return f;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(BaseResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private static List<String> ids(BaseResponse r) {
        return ((List<?>) data(r).get("items")).stream()
                .map(i -> (String) ((Map<?, ?>) i).get("appointmentId")).collect(Collectors.toList());
    }

    private StylerEntity stylist(String id, String province, String business) {
        StylerEntity s = new StylerEntity();
        s.setStylerId(id);
        s.setProvince(province);
        s.setBusinessName(business);
        s.setFirstname("Test");
        s.setLastname("Stylist");
        s.setEmailAddress(id.toLowerCase() + "@rapidstylers.test");
        s.setTimeZone("Ontario".equals(province) ? "America/Toronto" : "America/Edmonton");
        s.setStatus("0");
        return s;
    }

    private BookAppointmentEntity booking(String id, String stylerId, String status, LocalDate date,
                                          String price, Long platformFeeCents) {
        BookAppointmentEntity b = new BookAppointmentEntity();
        b.setAppointmentId(id);
        b.setUserId(customer);
        b.setStylerId(stylerId);
        b.setSubServiceId("999999");
        b.setStatus(status);
        b.setAppointmentDate(date.toString());
        b.setAppointmentDateValue(date);
        b.setArrivalTime("10:00");
        b.setAppointmentStartTime(LocalTime.of(10, 0));
        b.setAppointmentEndTime(LocalTime.of(11, 0));
        b.setDurationMinutes(60);
        b.setPrice(price);
        b.setServicePrice(price);
        b.setTravelFee("0.00");
        b.setPlatformFeeCents(platformFeeCents);
        b.setCreatedAt("03 01, 2031 09:00:00");
        return b;
    }
}
