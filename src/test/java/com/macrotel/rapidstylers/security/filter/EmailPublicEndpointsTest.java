package com.macrotel.rapidstylers.security.filter;
import com.macrotel.rapidstylers.security.access.Routes;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
class EmailPublicEndpointsTest {
    @Test void signedWebhookAndTokenPostBypassApiKeyButOtherNewsletterWritesStillRequireIt() throws Exception {
        ApiKeyFilter filter=new ApiKeyFilter();ReflectionTestUtils.setField(filter,"apiKey","test-shared-key");
        for(String route:new String[]{"/rapid_stylers/resend/webhook","/rapid_stylers/newsletter_one_click_unsubscribe"}) {
            MockHttpServletResponse response=new MockHttpServletResponse();boolean[] reached={false};
            filter.doFilter(new MockHttpServletRequest("POST",route),response,(request,result)->reached[0]=true);
            assertTrue(reached[0], "Provider request must reach signature/token validation");assertTrue(Arrays.asList(Routes.PUBLIC).contains(route));
        }
        MockHttpServletResponse response=new MockHttpServletResponse();boolean[] reached={false};
        filter.doFilter(new MockHttpServletRequest("POST","/rapid_stylers/newsletter_subscribe"),response,(request,result)->reached[0]=true);
        assertFalse(reached[0]);assertEquals(401,response.getStatus());
    }
}
