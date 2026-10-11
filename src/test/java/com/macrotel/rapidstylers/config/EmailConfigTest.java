package com.macrotel.rapidstylers.config;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailConfigTest {

    @Test
    void sendsEmailToConfiguredLoopbackEndpoint() throws Exception {
        var endpointField = ReflectionUtils.findField(EmailConfig.class, "resendApiUrl");
        assertNotNull(endpointField, "EmailConfig must allow a local test inbox endpoint");
        Value endpointConfig = endpointField.getAnnotation(Value.class);
        assertNotNull(endpointConfig);
        assertTrue(endpointConfig.value().startsWith("${app.resend.api-url:"));

        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> payload = new AtomicReference<>();
        HttpServer inbox = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        inbox.createContext("/emails", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"id\":\"local-test-email\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        inbox.start();
        try {
            EmailConfig config = new EmailConfig();
            ReflectionTestUtils.setField(config, "resendApiUrl",
                    "http://127.0.0.1:" + inbox.getAddress().getPort() + "/emails");
            ReflectionTestUtils.setField(config, "resendApiKey", "local-only-fake-token");
            ReflectionTestUtils.setField(config, "sender", "onboarding@example.test");

            assertEquals("Mail Sent Successfully...",
                    config.sendSimpleMail("customer@example.test", "Test subject", "Test body"));
            assertEquals("POST", method.get());
            assertEquals("Bearer local-only-fake-token", authorization.get());
            assertTrue(payload.get().contains("customer@example.test"));
            assertTrue(payload.get().contains("Test subject"));
            assertTrue(payload.get().contains("Test body"));
        } finally {
            inbox.stop(0);
        }
    }
}
