package com.macrotel.rapidstylers.catalog.service;

import com.macrotel.rapidstylers.catalog.repo.SiteContentRepo;
import com.macrotel.rapidstylers.catalog.repo.SiteContentVersionRepo;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Website content against the real database: drafts stay private until
 * published, every kind is checked and cleaned, versions can be restored,
 * email templates render safely, and only admins can edit.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SiteContentTest {

    @Autowired SiteContentService service;
    @Autowired SiteContentRepo contentRepo;
    @Autowired SiteContentVersionRepo versionRepo;
    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @MockBean EmailConfig emailConfig;
    @Value("${app.api.key}") String apiKey;

    private final String tag = Long.toString(System.nanoTime(), 36).toLowerCase();
    private final String seoKey = "seo:/t-" + tag;
    private final String admin = "ADM" + tag;

    @AfterEach
    void cleanUp() {
        for (String key : List.of(seoKey, "faq", "email:booking_confirmed")) {
            versionRepo.findByContentKeyOrderByVersionDesc(key).forEach(versionRepo::delete);
            contentRepo.findByContentKey(key).ifPresent(contentRepo::delete);
        }
    }

    @Test
    void aDraftIsPrivateUntilPublishedAndOldVersionsComeBack() {
        assertEquals("404", service.publicContent(seoKey).getStatusCode());
        assertEquals("200", service.saveDraft(admin, seoKey, Map.of("title", "Braids in Calgary")).getStatusCode());
        assertEquals("404", service.publicContent(seoKey).getStatusCode(), "saving is not publishing");

        assertEquals("200", service.publish(admin, seoKey).getStatusCode());
        assertEquals("Braids in Calgary", content(service.publicContent(seoKey)).get("title"));
        assertEquals("The draft is already published", service.publish(admin, seoKey).getMessage());

        service.saveDraft(admin, seoKey, Map.of("title", "Knotless braids in Calgary"));
        assertEquals("Braids in Calgary", content(service.publicContent(seoKey)).get("title"), "visitors still see v1");
        service.publish(admin, seoKey);
        assertEquals("Knotless braids in Calgary", content(service.publicContent(seoKey)).get("title"));
        assertEquals(2, ((List<?>) service.history(seoKey).getData()).size());

        service.restore(admin, seoKey, 1);
        service.publish(admin, seoKey);
        assertEquals("Braids in Calgary", content(service.publicContent(seoKey)).get("title"));
        assertEquals(3, contentRepo.findByContentKey(seoKey).orElseThrow().getVersion());

        @SuppressWarnings("unchecked")
        Map<String, Object> pages = (Map<String, Object>) service.publicSeo().getData();
        assertTrue(pages.containsKey("/t-" + tag));

        service.unpublish(admin, seoKey);
        assertEquals("404", service.publicContent(seoKey).getStatusCode());
    }

    @Test
    void contentIsCheckedAndHtmlIsStripped() {
        assertEquals("Unknown content key", service.saveDraft(admin, "about-us", Map.of()).getMessage());
        assertEquals("400", service.saveDraft(admin, "seo:/Bad Path", Map.of("title", "x")).getStatusCode());
        assertEquals("title is required", service.saveDraft(admin, seoKey, Map.of("title", " ")).getMessage());
        assertEquals("400", service.saveDraft(admin, seoKey, Map.of("title", "x".repeat(71))).getStatusCode());
        assertEquals("ogImageUrl must be an https:// link",
                service.saveDraft(admin, seoKey, Map.of("title", "x", "ogImageUrl", "javascript:alert(1)")).getMessage());

        Map<String, Object> faq = Map.of("items", List.of(
                Map.of("question", "Do you <b>travel</b>?", "answer", "Yes<script>alert(1)</script> within 20 km")));
        service.saveDraft(admin, "faq", faq);
        service.publish(admin, "faq");
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) content(service.publicContent("faq")).get("items")).get(0);
        assertEquals("Do you travel ?", item.get("question"));
        assertEquals("Yes within 20 km", item.get("answer"));

        Map<String, Object> home = new HashMap<>();
        home.put("heroTitle", "Book a stylist");
        home.put("ctaLink", "//evil.example");
        assertEquals("400", service.saveDraft(admin, "homepage", home).getStatusCode(), "protocol-relative links refused");
    }

    @Test
    void emailTemplatesAllowOnlyTheirPlaceholdersAndRenderEscaped() {
        String key = "email:booking_confirmed";
        assertTrue(service.saveDraft(admin, key, Map.of("subject", "Hi {{firstName}}", "body", "Your {{password}}"))
                .getMessage().startsWith("{{password}} isn't available here"));
        assertEquals("404", service.publicContent(key).getStatusCode(), "templates are never public");

        service.saveDraft(admin, key, Map.of("subject", "Booked: {{serviceName}}",
                "body", "Hi {{firstName}},\n\nSee you {{appointmentDate}} at {{appointmentTime}}.\nThanks"));
        @SuppressWarnings("unchecked")
        Map<String, Object> preview = (Map<String, Object>) service.previewEmail(key, false).getData();
        assertEquals("Booked: Knotless braids", preview.get("subject"));
        assertEquals("<p>Hi Ada,</p><p>See you 2026-11-14 at 10:30.<br>Thanks</p>", preview.get("html"));

        assertTrue(service.renderEmail("booking_confirmed", Map.of()).isEmpty(), "nothing published, callers keep their own text");
        service.publish(admin, key);
        SiteContentRules.RenderedEmail sent = service.renderEmail("booking_confirmed",
                Map.of("firstName", "<img src=x onerror=alert(1)>", "serviceName", "Braids\r\nBcc: x@evil.test")).orElseThrow();
        assertTrue(sent.html.startsWith("<p>Hi &lt;img src=x onerror=alert(1)&gt;,</p>"), "values are escaped");
        assertEquals("Booked: Braids Bcc: x@evil.test", sent.subject, "no line breaks reach the subject");
    }

    @Test
    void onlyAdminsEditAndAnyoneReadsPublishedContent() throws Exception {
        String body = "{\"key\":\"" + seoKey + "\",\"content\":{\"title\":\"Hello\"}}";
        mockMvc.perform(post("/rapid_stylers/admin/save_site_content").header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken("U" + tag, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertTrue(contentRepo.findByContentKey(seoKey).isEmpty());

        String adminToken = "Bearer " + jwtUtil.generateToken(admin, "ADMIN");
        mockMvc.perform(post("/rapid_stylers/admin/save_site_content").header("x-api-key", apiKey)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/rapid_stylers/admin/publish_site_content").header("x-api-key", apiKey)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"" + seoKey + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/rapid_stylers/site_content").param("key", seoKey).header("x-api-key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.title").value("Hello"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(BaseResponse r) {
        assertEquals("200", r.getStatusCode(), r.getMessage());
        return (Map<String, Object>) ((Map<String, Object>) r.getData()).get("content");
    }
}
