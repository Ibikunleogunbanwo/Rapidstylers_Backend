package com.macrotel.rapidstylers.catalog.service;

import com.macrotel.rapidstylers.config.AppUtils;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

/**
 * What each kind of website content may contain, and how an email template
 * becomes a safe email.
 *
 * <p>Picture a form with labelled boxes: every kind has a fixed set of boxes,
 * each with a length limit. Anything outside the boxes is dropped, text is
 * stripped of HTML, and links must be https or a path on this site. The
 * website shows the text as text, so nothing an admin types can run as code.
 *
 * <p>Email templates are plain text with {{placeholders}}. Rendering escapes
 * every character, then turns blank lines into paragraphs, so the only HTML
 * in the email is HTML the server wrote.
 */
public final class SiteContentRules {

    public static final String FAQ = "FAQ";
    public static final String HOMEPAGE = "HOMEPAGE";
    public static final String SEO = "SEO";
    public static final String EMAIL = "EMAIL";

    private static final Pattern SEO_PATH = Pattern.compile("/[a-z0-9/_-]{0,100}");
    private static final Pattern SITE_PATH = Pattern.compile("/[A-Za-z0-9/_\\-.?=&#%]{0,300}");
    private static final Pattern HTTPS_URL = Pattern.compile("https://[A-Za-z0-9.-]+(:\\d+)?(/[A-Za-z0-9/_\\-.~?=&#%+,:@]*)?");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z]+)\\s*}}");

    private static final List<String> BOOKING_FIELDS =
            List.of("firstName", "serviceName", "stylistName", "appointmentDate", "appointmentTime");

    /** The email templates an admin can edit, and the placeholders each one may use. */
    public static final Map<String, List<String>> EMAIL_TEMPLATES;

    static {
        Map<String, List<String>> t = new LinkedHashMap<>();
        t.put("welcome_customer", List.of("firstName"));
        t.put("welcome_stylist", List.of("firstName"));
        t.put("booking_received", BOOKING_FIELDS);
        t.put("booking_confirmed", BOOKING_FIELDS);
        t.put("booking_cancelled", BOOKING_FIELDS);
        t.put("booking_reminder", BOOKING_FIELDS);
        EMAIL_TEMPLATES = Collections.unmodifiableMap(t);
    }

    private SiteContentRules() {
    }

    /** The kind a key belongs to, or null when the key is not one the site has. */
    public static String kindOf(String key) {
        if (key == null) return null;
        if ("faq".equals(key)) return FAQ;
        if ("homepage".equals(key)) return HOMEPAGE;
        if (key.startsWith("seo:") && SEO_PATH.matcher(key.substring(4)).matches()) return SEO;
        if (key.startsWith("email:") && EMAIL_TEMPLATES.containsKey(key.substring(6))) return EMAIL;
        return null;
    }

    /** Checks a draft and returns the cleaned copy to store. Throws IllegalArgumentException with a message for the admin. */
    public static Map<String, Object> clean(String key, Map<String, Object> body) {
        String kind = kindOf(key);
        if (kind == null) throw new IllegalArgumentException("Unknown content key");
        if (body == null) throw new IllegalArgumentException("Nothing to save");
        switch (kind) {
            case FAQ: return faq(body);
            case HOMEPAGE: return homepage(body);
            case SEO: return seo(body);
            default: return email(key.substring(6), body);
        }
    }

    private static Map<String, Object> faq(Map<String, Object> body) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<?, ?> raw : list(body.get("items"), "items", 200)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("question", text(raw.get("question"), "question", 300, true));
            item.put("answer", text(raw.get("answer"), "answer", 4000, true));
            item.put("category", text(raw.get("category"), "category", 60, false));
            items.add(item);
        }
        return Map.of("items", items);
    }

    private static Map<String, Object> homepage(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("heroTitle", text(body.get("heroTitle"), "heroTitle", 120, true));
        out.put("heroSubtitle", text(body.get("heroSubtitle"), "heroSubtitle", 300, false));
        out.put("heroImageUrl", httpsUrl(body.get("heroImageUrl"), "heroImageUrl"));
        out.put("ctaText", text(body.get("ctaText"), "ctaText", 40, false));
        out.put("ctaLink", link(body.get("ctaLink"), "ctaLink"));
        out.put("announcement", text(body.get("announcement"), "announcement", 200, false));
        List<Map<String, Object>> sections = new ArrayList<>();
        for (Map<?, ?> raw : list(body.get("sections"), "sections", 20)) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("title", text(raw.get("title"), "section title", 120, true));
            s.put("body", text(raw.get("body"), "section body", 2000, false));
            s.put("imageUrl", httpsUrl(raw.get("imageUrl"), "section imageUrl"));
            sections.add(s);
        }
        out.put("sections", sections);
        return out;
    }

    private static Map<String, Object> seo(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", text(body.get("title"), "title", 70, true));
        out.put("description", text(body.get("description"), "description", 170, false));
        out.put("ogImageUrl", httpsUrl(body.get("ogImageUrl"), "ogImageUrl"));
        out.put("noindex", Boolean.parseBoolean(String.valueOf(body.get("noindex"))));
        return out;
    }

    private static Map<String, Object> email(String name, Map<String, Object> body) {
        String subject = text(body.get("subject"), "subject", 150, true);
        Object rawBody = body.get("body");
        if (rawBody == null || String.valueOf(rawBody).isBlank()) throw new IllegalArgumentException("body is required");
        String text = String.valueOf(rawBody).replace("\r\n", "\n").trim();
        if (text.length() > 20000) throw new IllegalArgumentException("Keep body under 20000 characters");
        List<String> allowed = EMAIL_TEMPLATES.get(name);
        for (String s : List.of(subject, text)) {
            Matcher m = PLACEHOLDER.matcher(s);
            while (m.find()) {
                if (!allowed.contains(m.group(1))) {
                    throw new IllegalArgumentException("{{" + m.group(1) + "}} isn't available here. Use: " + allowed);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subject", subject);
        out.put("body", text);
        return out;
    }

    /** A rendered email: a one-line subject and HTML built only from escaped text. */
    public static final class RenderedEmail {
        public final String subject;
        public final String html;

        RenderedEmail(String subject, String html) {
            this.subject = subject;
            this.html = html;
        }
    }

    /** Fills a template's placeholders. Values are escaped; a missing value becomes empty. */
    public static RenderedEmail render(Map<String, Object> template, Map<String, String> values) {
        String subject = fill(String.valueOf(template.get("subject")), values).replaceAll("[\\r\\n]+", " ").trim();
        String escaped = HtmlUtils.htmlEscape(fill(String.valueOf(template.get("body")), values));
        StringBuilder html = new StringBuilder();
        for (String paragraph : escaped.split("\\n\\s*\\n")) {
            if (paragraph.isBlank()) continue;
            html.append("<p>").append(paragraph.trim().replace("\n", "<br>")).append("</p>");
        }
        return new RenderedEmail(subject, html.toString());
    }

    /** Sample values for previews. */
    public static Map<String, String> sampleValues() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("firstName", "Ada");
        v.put("serviceName", "Knotless braids");
        v.put("stylistName", "Bella's Braids");
        v.put("appointmentDate", "2026-11-14");
        v.put("appointmentTime", "10:30");
        return v;
    }

    private static String fill(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values == null ? null : values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ── field checks ────────────────────────────────────────────────────────

    private static List<Map<?, ?>> list(Object raw, String name, int max) {
        if (raw == null) return List.of();
        if (!(raw instanceof List)) throw new IllegalArgumentException(name + " must be a list");
        List<?> in = (List<?>) raw;
        if (in.size() > max) throw new IllegalArgumentException("At most " + max + " " + name);
        List<Map<?, ?>> out = new ArrayList<>();
        for (Object o : in) {
            if (!(o instanceof Map)) throw new IllegalArgumentException("Each of " + name + " must be an object");
            out.add((Map<?, ?>) o);
        }
        return out;
    }

    private static String text(Object raw, String name, int max, boolean required) {
        String value = raw == null ? null : AppUtils.sanitizeText(String.valueOf(raw).trim());
        if (value == null || value.isEmpty()) {
            if (required) throw new IllegalArgumentException(name + " is required");
            return null;
        }
        if (value.length() > max) throw new IllegalArgumentException("Keep " + name + " under " + max + " characters");
        return value;
    }

    private static String httpsUrl(Object raw, String name) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        String url = String.valueOf(raw).trim();
        if (url.length() > 500 || !HTTPS_URL.matcher(url).matches()) {
            throw new IllegalArgumentException(name + " must be an https:// link");
        }
        return url;
    }

    private static String link(Object raw, String name) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        String link = String.valueOf(raw).trim();
        if (link.startsWith("//") || !(SITE_PATH.matcher(link).matches() || HTTPS_URL.matcher(link).matches())) {
            throw new IllegalArgumentException(name + " must be a page on this site (/...) or an https:// link");
        }
        return link;
    }
}
