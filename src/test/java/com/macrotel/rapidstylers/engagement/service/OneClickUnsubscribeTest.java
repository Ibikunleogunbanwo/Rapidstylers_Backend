package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.engagement.controller.NewsletterController;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OneClickUnsubscribeTest {
    @Test void acceptsTokenPostWithoutAuthenticationAndNeverUnsubscribesOnGet() throws Exception {
        NewsletterSubscriptionService service = mock(NewsletterSubscriptionService.class);
        BaseResponse success = new BaseResponse(true); success.setStatusCode("200");
        when(service.unsubscribe(anyString())).thenReturn(success);
        NewsletterController controller = new NewsletterController();
        ReflectionTestUtils.setField(controller, "subscriptionService", service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String token = "a".repeat(64);
        mvc.perform(get("/rapid_stylers/newsletter_one_click_unsubscribe").param("token", token)).andExpect(status().isMethodNotAllowed());
        verify(service, never()).unsubscribe(anyString());
        mvc.perform(post("/rapid_stylers/newsletter_one_click_unsubscribe").param("token",token).contentType("application/x-www-form-urlencoded").content("List-Unsubscribe=One-Click")).andExpect(status().isOk());
        verify(service).unsubscribe(token);
    }
    @Test void rejectsMalformedOrMissingOneClickMarker() throws Exception {
        NewsletterController controller = new NewsletterController();
        NewsletterSubscriptionService service = mock(NewsletterSubscriptionService.class);
        ReflectionTestUtils.setField(controller, "subscriptionService", service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/rapid_stylers/newsletter_one_click_unsubscribe").param("token","not-a-token").contentType("application/x-www-form-urlencoded").content("List-Unsubscribe=One-Click")).andExpect(status().isBadRequest());
        mvc.perform(post("/rapid_stylers/newsletter_one_click_unsubscribe").param("token","a".repeat(64)).contentType("application/x-www-form-urlencoded").content("List-Unsubscribe=wrong")).andExpect(status().isBadRequest());
        verify(service, never()).unsubscribe(anyString());
    }
}
