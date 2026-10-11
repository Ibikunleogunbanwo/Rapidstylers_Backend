package com.macrotel.rapidstylers.engagement.controller;

import com.macrotel.rapidstylers.engagement.service.ResendWebhookService;
import com.macrotel.rapidstylers.engagement.repo.EmailSuppressionRepo;
import com.macrotel.rapidstylers.engagement.entity.EmailSuppressionEntity;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResendWebhookTest {
    static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString("local-test-webhook-key-32-bytes!!!".getBytes(StandardCharsets.UTF_8));
    static final String BODY = "{\"type\":\"email.bounced\",\"data\":{\"email_id\":\"resend-email-1\",\"to\":[\"Recipient@example.test\"],\"bounce\":{\"type\":\"Permanent\",\"message\":\"User unknown\"}}}";
    @Test void authenticatedBounceIsNormalizedStoredOnceAndTamperingIsRejected() throws Exception {
        EmailSuppressionRepo repo = mock(EmailSuppressionRepo.class);
        when(repo.existsByEmail("recipient@example.test")).thenReturn(false, true);
        ResendWebhookService service = new ResendWebhookService(repo);
        ReflectionTestUtils.setField(service,"webhookSecret",SECRET);
        var mvc = MockMvcBuilders.standaloneSetup(new ResendWebhookController(service)).build();
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = sign(BODY,timestamp);
        for(int i=0;i<2;i++) mvc.perform(post("/rapid_stylers/resend/webhook").contentType("application/json").content(BODY).header("svix-id","msg-local").header("svix-timestamp",timestamp).header("svix-signature",signature)).andExpect(status().isOk());
        ArgumentCaptor<EmailSuppressionEntity> row=ArgumentCaptor.forClass(EmailSuppressionEntity.class);
        verify(repo,times(1)).save(row.capture());
        assertEquals("recipient@example.test",row.getValue().getEmail());assertEquals("BOUNCE",row.getValue().getReason());
        mvc.perform(post("/rapid_stylers/resend/webhook").contentType("application/json").content(BODY.replace("Recipient","Victim")).header("svix-id","msg-local").header("svix-timestamp",timestamp).header("svix-signature",signature)).andExpect(status().isUnauthorized());
        verify(repo,never()).existsByEmail("victim@example.test");
    }
    @Test void staleUnsignedAndUnconfiguredEventsFailClosed() throws Exception {
        EmailSuppressionRepo repo=mock(EmailSuppressionRepo.class);ResendWebhookService service=new ResendWebhookService(repo);
        ReflectionTestUtils.setField(service,"webhookSecret",SECRET);
        var mvc=MockMvcBuilders.standaloneSetup(new ResendWebhookController(service)).build();
        String stale=Long.toString(Instant.now().minusSeconds(600).getEpochSecond());
        mvc.perform(post("/rapid_stylers/resend/webhook").content(BODY).header("svix-id","msg-local").header("svix-timestamp",stale).header("svix-signature",sign(BODY,stale))).andExpect(status().isUnauthorized());
        mvc.perform(post("/rapid_stylers/resend/webhook").content(BODY)).andExpect(status().isUnauthorized());
        ReflectionTestUtils.setField(service,"webhookSecret","");
        mvc.perform(post("/rapid_stylers/resend/webhook").content(BODY)).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(repo);
    }
    @Test void temporaryDelayIsIgnoredAndComplaintSuppresses() throws Exception {
        EmailSuppressionRepo repo=mock(EmailSuppressionRepo.class);ResendWebhookService service=new ResendWebhookService(repo);ReflectionTestUtils.setField(service,"webhookSecret",SECRET);
        var mvc=MockMvcBuilders.standaloneSetup(new ResendWebhookController(service)).build();String timestamp=Long.toString(Instant.now().getEpochSecond());
        for(String type:new String[]{"email.bounced","email.delivery_delayed","email.complained"}) {String body=BODY.replace("email.bounced",type); if ("email.bounced".equals(type)) body=body.replace("Permanent","Transient");mvc.perform(post("/rapid_stylers/resend/webhook").content(body).header("svix-id","msg-local").header("svix-timestamp",timestamp).header("svix-signature",sign(body,timestamp))).andExpect(status().isOk());}
        ArgumentCaptor<EmailSuppressionEntity> row=ArgumentCaptor.forClass(EmailSuppressionEntity.class);verify(repo,times(1)).save(row.capture());assertEquals("COMPLAINT",row.getValue().getReason());
    }
    static String sign(String body,String timestamp) throws Exception {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET.substring(6)),"HmacSHA256"));return "v1,"+Base64.getEncoder().encodeToString(mac.doFinal(("msg-local."+timestamp+"."+body).getBytes(StandardCharsets.UTF_8)));}
}
