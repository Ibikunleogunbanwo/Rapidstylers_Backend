package com.macrotel.rapidstylers.engagement.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.engagement.entity.EmailSuppressionEntity;
import com.macrotel.rapidstylers.engagement.repo.EmailSuppressionRepo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Resend/Svix signed notifications. The existing unique suppression address makes retries idempotent. */
@Service
public class ResendWebhookService {
    private final EmailSuppressionRepo suppressions;
    private final ObjectMapper json = new ObjectMapper();
    @Value("${app.resend.webhook-secret:}") private String webhookSecret;
    public ResendWebhookService(EmailSuppressionRepo suppressions) { this.suppressions = suppressions; }

    @Transactional
    public void receive(String body, String id, String timestamp, String signature) {
        verify(body, id, timestamp, signature);
        final JsonNode event;
        try { event = json.readTree(body); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook payload"); }
        if (event == null || !event.isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook payload");
        String type = event.path("type").asText();
        String reason = "email.bounced".equals(type) ? "BOUNCE" : "email.complained".equals(type) ? "COMPLAINT" : null;
        if ("BOUNCE".equals(reason) && !"Permanent".equalsIgnoreCase(event.path("data").path("bounce").path("type").asText())) return;
        if (reason == null) return; // A temporary delivery delay is not a permanent bounce.
        JsonNode recipients = event.path("data").path("to");
        if (!recipients.isArray() || recipients.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing recipients");
        for (JsonNode recipient : recipients) {
            String email = recipient.isTextual() ? NewsletterRules.email(recipient.asText()) : null;
            if (email == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid recipient");
            if (suppressions.existsByEmail(email)) continue;
            EmailSuppressionEntity row = new EmailSuppressionEntity();
            row.setEmail(email); row.setReason(reason); row.setCreatedBy("RESEND_WEBHOOK"); row.setCreatedAt(NewsletterRules.now());
            row.setNote("Resend " + type);
            suppressions.save(row);
        }
    }

    private void verify(String body, String id, String timestamp, String signatures) {
        if (webhookSecret == null || webhookSecret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook is not configured");
        try {
            if (body == null || id == null || id.isBlank() || timestamp == null || signatures == null || body.length() > 1_000_000 || signatures.length() > 4096 || id.length() > 256) throw new IllegalArgumentException();
            long sent = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (sent < now - 300 || sent > now + 300) throw new IllegalArgumentException();
            String secret = webhookSecret.startsWith("whsec_") ? webhookSecret.substring(6) : webhookSecret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256"));
            byte[] expected = mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            for (String candidate : signatures.split("\\s+")) {
                if (!candidate.startsWith("v1,")) continue;
                try { if (MessageDigest.isEqual(expected, Base64.getDecoder().decode(candidate.substring(3)))) return; }
                catch (IllegalArgumentException ignored) { /* A rotated signature may still match. */ }
            }
        } catch (Exception ignored) { /* Do not leak payloads, signing secrets or crypto errors. */ }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
    }
}
