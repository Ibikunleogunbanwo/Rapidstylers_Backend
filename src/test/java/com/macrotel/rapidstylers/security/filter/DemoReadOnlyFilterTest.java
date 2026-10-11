package com.macrotel.rapidstylers.security.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo copy of the app serves the showcase database: visitors browse, but
 * nothing that creates an account, a booking or a payment gets through.
 */
class DemoReadOnlyFilterTest {

    private DemoReadOnlyFilter filter(boolean readOnly) {
        DemoReadOnlyFilter filter = new DemoReadOnlyFilter();
        filter.readOnly = readOnly;
        filter.liveUrl = "https://rapidstylers.ca";
        return filter;
    }

    private MockHttpServletResponse call(DemoReadOnlyFilter filter, String method, String path, MockFilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void liveAppIsUntouchedWhenTheSwitchIsOff() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = call(filter(false), "POST", "/rapid_stylers/create_user_account", chain);
        assertNotNull(chain.getRequest(), "request must reach the app");
        assertEquals(200, response.getStatus());
    }

    @Test
    void demoRefusesSignUpBookingAndPaymentWithALinkToTheLiveSite() throws Exception {
        for (String path : new String[]{"/rapid_stylers/generate_sign_up_otp_code", "/rapid_stylers/create_user_account",
                "/rapid_stylers/create_styler", "/rapid_stylers/sign_in", "/rapid_stylers/book_appointment",
                "/rapid_stylers/card_setup_intent", "/rapid_stylers/stripe/webhook", "/rapid_stylers/get_upload_signature"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = call(filter(true), "POST", path, chain);
            assertNull(chain.getRequest(), path + " must not reach the app");
            assertEquals(403, response.getStatus(), path);
            assertTrue(response.getContentAsString().contains("https://rapidstylers.ca"), path);
        }
    }

    @Test
    void demoStillServesBrowsingAdminWorkAndReads() {
        DemoReadOnlyFilter filter = filter(true);
        assertTrue(filter.isAllowed("POST", "/rapid_stylers/search_nearby"));
        assertTrue(filter.isAllowed("POST", "/rapid_stylers/single_styler"));
        assertTrue(filter.isAllowed("POST", "/rapid_stylers/admin_sign_in"));
        assertTrue(filter.isAllowed("POST", "/rapid_stylers/admin/update_styler_verification"));
        assertTrue(filter.isAllowed("POST", "/rapid_stylers/admin/accounts/7/disable"));
        assertTrue(filter.isAllowed("POST", "/rapid_stylers/auth/logout"));
        assertTrue(filter.isAllowed("GET", "/rapid_stylers/list_service"));
        assertTrue(filter.isAllowed("GET", "/actuator/health"));
        assertFalse(filter.isAllowed("POST", "/rapid_stylers/add_feedback"));
        assertFalse(filter.isAllowed("GET", "/rapid_stylers/card_setup_intent"));
        assertFalse(filter.isAllowed("GET", "/rapid_stylers/get_upload_signature"));
        assertFalse(filter.isAllowed("DELETE", "/rapid_stylers/notifications/delete"));
    }
}
