package com.macrotel.rapidstylers.engagement.controller;

import com.macrotel.rapidstylers.engagement.service.ResendWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ResendWebhookController {
    private final ResendWebhookService service;
    public ResendWebhookController(ResendWebhookService service) { this.service = service; }
    @PostMapping("/rapid_stylers/resend/webhook")
    public ResponseEntity<Void> receive(@RequestBody String rawBody,
            @RequestHeader(value = "svix-id", required = false) String id,
            @RequestHeader(value = "svix-timestamp", required = false) String timestamp,
            @RequestHeader(value = "svix-signature", required = false) String signature) {
        service.receive(rawBody, id, timestamp, signature);
        return ResponseEntity.ok().build();
    }
}
