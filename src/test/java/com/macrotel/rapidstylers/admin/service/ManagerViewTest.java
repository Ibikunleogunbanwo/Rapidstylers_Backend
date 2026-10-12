package com.macrotel.rapidstylers.admin.service;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The manager's view keeps only allowlisted, money-free fields. */
class ManagerViewTest {

    private static BaseResponse with(Object data) {
        BaseResponse response = new BaseResponse(true);
        response.setStatusCode("200");
        response.setData(data);
        return response;
    }

    private static Map<String, Object> booking() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("appointmentId", "A1");
        row.put("status", "COMPLETED");
        row.put("paymentStatus", "CAPTURED");
        row.put("price", "80.00");
        row.put("platformFee", "12.00");
        row.put("stylistShare", "68.00");
        row.put("paymentIntentId", "pi_123");
        row.put("someNewMoneyField", "1.00");
        return row;
    }

    @Test
    void bookingListDropsMoneyAndKeepsCounts() {
        Map<String, Object> totals = new LinkedHashMap<>(Map.of("count", 1, "bookingValue", 80, "byStatus", Map.of("COMPLETED", 1L)));
        Map<String, Object> data = new LinkedHashMap<>(Map.of("items", List.of(booking()), "totals", totals, "total", 1L));

        Map<?, ?> out = (Map<?, ?>) ManagerView.bookingList(with(data)).getData();

        Map<?, ?> row = (Map<?, ?>) ((List<?>) out.get("items")).get(0);
        assertEquals("CAPTURED", row.get("paymentStatus"));
        assertFalse(row.containsKey("price"));
        assertFalse(row.containsKey("paymentIntentId"));
        assertFalse(row.containsKey("someNewMoneyField"), "unknown fields are dropped, not passed through");
        assertEquals(Map.of("count", 1, "byStatus", Map.of("COMPLETED", 1L)), out.get("totals"));
        assertEquals(1L, out.get("total"));
    }

    @Test
    void detailAndKpisKeepOnlyTheirFields() {
        Map<String, Object> detail = booking();
        detail.put("serviceAddress", "1 Main St");
        detail.put("refunds", List.of());
        detail.put("history", List.of());
        Map<?, ?> out = (Map<?, ?>) ManagerView.bookingDetail(with(detail)).getData();
        assertTrue(out.containsKey("serviceAddress"));
        assertFalse(out.containsKey("refunds"));
        assertFalse(out.containsKey("history"));

        Map<String, Object> kpis = new LinkedHashMap<>(Map.of("customers", 3, "revenue", 100));
        assertEquals(Map.of("customers", 3), ManagerView.kpis(with(kpis)).getData());
    }

    @Test
    void anErrorAnswerIsLeftAlone() {
        BaseResponse notFound = with(new Object[0]);
        assertEquals(notFound, ManagerView.bookingDetail(notFound));
    }

    @Test
    void onlyTheManagerRoleCounts() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("role", "MANAGER");
        assertTrue(ManagerView.isManager(request));
        request.setAttribute("role", "ADMIN");
        assertFalse(ManagerView.isManager(request));
    }
}
