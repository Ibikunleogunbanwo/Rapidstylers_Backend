package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.catalog.service.SiteContentRules;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/** Small shared pieces of the newsletter: statuses, addresses, tokens, answers and the email's HTML. */
final class NewsletterRules {

    static final String PENDING = "PENDING";
    static final String SUBSCRIBED = "SUBSCRIBED";
    static final String UNSUBSCRIBED = "UNSUBSCRIBED";

    static final String GUEST = "GUEST";

    /** The sentence a person agrees to. Stored with each subscriber as proof of consent. */
    static final String CONSENT_TEXT =
            "Yes, send me RapidStylers news, offers and tips by email. I can unsubscribe at any time.";

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+");
    private static final SecureRandom RANDOM = new SecureRandom();

    private NewsletterRules() {
    }

    /** Lower-case address, or null when it isn't one. */
    static String email(String raw) {
        if (raw == null) return null;
        String e = raw.trim().toLowerCase(Locale.ROOT);
        return e.length() <= 254 && EMAIL.matcher(e).matches() ? e : null;
    }

    /** 64 hex characters; unguessable, safe in a link. */
    static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String province(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String p = raw.trim();
        return p.length() > 60 ? p.substring(0, 60) : p;
    }

    /** "ad***@example.com", for pages reached from an email link. */
    static String masked(String email) {
        int at = email.indexOf('@');
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }

    static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * The newsletter as HTML: the text escaped and split into paragraphs (see
     * {@link SiteContentRules#render}), then a footer with the unsubscribe link.
     */
    static SiteContentRules.RenderedEmail render(String subject, String body, String unsubscribeUrl) {
        SiteContentRules.RenderedEmail text = SiteContentRules.render(Map.of("subject", subject, "body", body), Map.of());
        String footer = "<hr><p style=\"font-size:12px;color:#666\">You're getting this because you subscribed to "
                + "RapidStylers news. <a href=\"" + unsubscribeUrl + "\">Unsubscribe</a></p>";
        return new SiteContentRules.RenderedEmail(text.subject, text.html + footer);
    }

    static BaseResponse ok(String message, Object data) {
        BaseResponse r = new BaseResponse(true);
        r.setStatusCode(SUCCESS_STATUS_CODE);
        r.setMessage(message == null ? SUCCESS_MESSAGE : message);
        r.setData(data == null ? EMPTY_DATA : data);
        return r;
    }

    static BaseResponse error(String message) {
        BaseResponse r = new BaseResponse(true);
        r.setMessage(message);
        return r;
    }

    static BaseResponse status(String code, String message) {
        BaseResponse r = new BaseResponse(true);
        r.setStatusCode(code);
        r.setMessage(message);
        return r;
    }
}
