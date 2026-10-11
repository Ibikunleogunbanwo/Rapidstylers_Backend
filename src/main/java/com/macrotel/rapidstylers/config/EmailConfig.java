package com.macrotel.rapidstylers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmailConfig {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.resend.api-key}")
    private String resendApiKey;

    @Value("${app.resend.api-url:https://api.resend.com/emails}")
    private String resendApiUrl;

    @Value("${app.resend.from}")
    private String sender;

    /**
     * Sends an email via the Resend REST API (replaces the old Gmail SMTP path).
     * Keeps the same signature so existing callers are unaffected.
     * Returns the same success/error strings as before.
     */
    public String sendSimpleMail(String receiverEmail, String subject, String body) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("from", sender);
            payload.put("to", List.of(receiverEmail));
            payload.put("subject", subject);
            payload.put("html", body);

            String json = objectMapper.writeValueAsString(payload);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resendApiUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + resendApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return "Mail Sent Successfully...";
            }
            System.err.println("Resend error " + response.statusCode() + ": " + response.body());
            return "Error while Sending Mail";
        } catch (Exception e) {
            System.err.println("Resend send failed: " + e.getMessage());
            return "Error while Sending Mail";
        }
    }
}
