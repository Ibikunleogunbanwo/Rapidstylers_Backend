package com.macrotel.rapidstylers.auth.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * An opaque handle for one customer sign-up, so the email address does not have
 * to ride along in every later request or sit in browser storage. Step 1 sends
 * the address once; the server answers with this token, and the code check,
 * resend and account creation send the token instead.
 *
 * The token is the address and an expiry, encrypted and authenticated with
 * AES-GCM under a key only the server holds: unreadable to the browser, and any
 * tampering makes it invalid. It is stateless, so it costs no storage and is
 * handed out identically whether or not an account already exists, keeping
 * step 1 enumeration-safe. It only stands in for the address: the emailed code
 * is still what proves the visitor owns it.
 */
@Service
public class SignupTokenService {

    static final String PREFIX = "st1.";
    static final Duration LIFETIME = Duration.ofHours(2);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Autowired
    public SignupTokenService(@Value("${app.jwt.secret:}") String jwtSecret) {
        this(jwtSecret, Clock.systemUTC());
    }

    SignupTokenService(String jwtSecret, Clock clock) {
        this.clock = clock;
        this.key = new SecretKeySpec(deriveKey(jwtSecret), "AES");
    }

    /** A separate key derived from the JWT secret; a random one when unset (tokens then last one boot). */
    private byte[] deriveKey(String secret) {
        try {
            if (secret == null || secret.isBlank()) {
                byte[] fresh = new byte[32];
                new SecureRandom().nextBytes(fresh);
                return fresh;
            }
            return MessageDigest.getInstance("SHA-256")
                    .digest(("rapidstylers-signup-token|" + secret).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot derive the sign-up token key", ex);
        }
    }

    public String issue(String emailAddress) {
        try {
            String email = emailAddress == null ? "" : emailAddress.trim().toLowerCase();
            long expiry = clock.instant().plus(LIFETIME).getEpochSecond();
            byte[] body = (expiry + "|" + email).getBytes(StandardCharsets.UTF_8);
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(body);
            byte[] out = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot issue a sign-up token", ex);
        }
    }

    /** The address a valid, unexpired token stands for; empty for anything else. */
    public Optional<String> resolve(String token) {
        if (token == null || !token.startsWith(PREFIX) || token.length() > 1024) return Optional.empty();
        try {
            byte[] raw = Base64.getUrlDecoder().decode(token.substring(PREFIX.length()));
            if (raw.length <= IV_BYTES) return Optional.empty();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            String body = new String(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.UTF_8);
            int bar = body.indexOf('|');
            if (bar <= 0) return Optional.empty();
            long expiry = Long.parseLong(body.substring(0, bar));
            String email = body.substring(bar + 1);
            if (clock.instant().getEpochSecond() > expiry || email.isBlank()) return Optional.empty();
            return Optional.of(email);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
