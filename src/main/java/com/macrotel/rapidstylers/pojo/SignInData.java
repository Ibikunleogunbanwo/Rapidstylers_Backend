package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;

@Data
public class SignInData {
    @NotEmpty(message = "Email Address cannot be empty")
    @Email(message = "Enter a valid email address")
    private String emailAddress;
    @NotEmpty(message = "Password cannot be empty")
    private String password;
    /**
     * Cloudflare Turnstile challenge response. Optional on the wire so a client
     * built before bot protection existed still deserialises; the controller
     * rejects the request when the challenge is configured and this is absent.
     */
    private String captchaToken;
}
