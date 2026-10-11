package com.macrotel.rapidstylers.catalog.service;

import com.macrotel.rapidstylers.catalog.entity.ServiceAreaEntity;
import com.macrotel.rapidstylers.catalog.repo.ServiceAreaRepo;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.service.StylerEligibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static com.macrotel.rapidstylers.config.AppConstants.VERIFICATION_APPROVED;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Service areas: with none set everywhere is served; once set, only stylists
 * inside a whole province or a listed city can be found and booked; switching
 * an area off or removing it takes effect at once.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceAreaTest {

    @Autowired ServiceAreaService service;
    @Autowired ServiceAreaRepo repo;
    @Autowired StylerEligibility eligibility;
    @Autowired StylerRepo stylerRepo;
    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @MockBean EmailConfig emailConfig;
    @Value("${app.api.key}") String apiKey;

    private final String tag = "SA" + Long.toString(System.nanoTime(), 36).toUpperCase();
    private final List<Long> created = new ArrayList<>();
    private StylerEntity toronto;
    private StylerEntity ottawa;

    @BeforeEach
    void seed() {
        assertTrue(repo.findAll().isEmpty(), "another test left service areas behind");
        toronto = stylerRepo.save(stylist(tag + "TO", "ON", "Toronto"));
        ottawa = stylerRepo.save(stylist(tag + "OT", "Ontario", "Ottawa"));
    }

    @AfterEach
    void cleanUp() {
        for (Long id : created) service.delete("ADM" + tag, id);
        repo.findAll().forEach(a -> service.delete("ADM" + tag, a.getId()));
        stylerRepo.delete(toronto);
        stylerRepo.delete(ottawa);
    }

    @Test
    void withNoAreasEverywhereIsServed() {
        assertTrue(service.covers("Yukon", "Whitehorse"));
        assertTrue(eligibility.isBookableStyler(ottawa));
        assertEquals(false, data(service.publicAreas()).get("restricted"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyListedProvincesAndCitiesAreServed() {
        add("AB", null, null);
        add("Ontario", " toronto ", null);

        assertTrue(service.covers("Alberta", "Red Deer"), "a whole province covers every city");
        assertTrue(service.covers("ab", null));
        assertTrue(service.covers("ON", "Toronto"));
        assertFalse(service.covers("Ontario", "Ottawa"));
        assertFalse(service.covers("Quebec", "Montreal"));
        assertFalse(service.covers(null, "Toronto"));

        assertTrue(eligibility.isBookableStyler(toronto));
        assertFalse(eligibility.isBookableStyler(ottawa), "outside every area: not listed, not bookable");

        Map<String, Object> admin = data(service.adminAreas());
        List<Map<String, Object>> outside = (List<Map<String, Object>>) admin.get("approvedStylistsOutside");
        assertTrue(outside.stream().anyMatch(s -> ottawa.getStylerId().equals(s.get("stylerId"))));
        assertFalse(outside.stream().anyMatch(s -> toronto.getStylerId().equals(s.get("stylerId"))));

        Map<String, Object> pub = data(service.publicAreas());
        assertEquals(true, pub.get("restricted"));
        assertEquals(2, ((List<?>) pub.get("areas")).size());
    }

    @Test
    void duplicatesAndUnknownProvincesAreRefused() {
        add("Alberta", null, null);
        assertEquals("400", service.save("ADM" + tag, null, "alberta", "", true, null).getStatusCode());
        assertEquals("400", service.save("ADM" + tag, null, "Atlantis", null, true, null).getStatusCode());
        assertEquals("404", service.delete("ADM" + tag, -1L).getStatusCode());
    }

    @Test
    void switchingAnAreaOffTakesEffectAtOnce() {
        Long ontario = add("Ontario", null, null);
        assertTrue(eligibility.isBookableStyler(ottawa));
        add("Alberta", null, null);
        assertEquals("200", service.save("ADM" + tag, ontario, "Ontario", null, false, "paused").getStatusCode());
        assertFalse(eligibility.isBookableStyler(ottawa));
        assertFalse(eligibility.isBookableStyler(toronto));
    }

    @Test
    void onlyAdminsChangeAreasButAnyoneCanReadThem() throws Exception {
        String body = "{\"province\":\"Alberta\"}";
        mockMvc.perform(post("/rapid_stylers/admin/save_service_area").header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(tag, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertTrue(repo.findAll().isEmpty());
        mockMvc.perform(get("/rapid_stylers/service_areas").header("x-api-key", apiKey))
                .andExpect(status().isOk());
    }

    private Long add(String province, String city, String note) {
        BaseResponse r = service.save("ADM" + tag, null, province, city, true, note);
        assertEquals("200", r.getStatusCode(), r.getMessage());
        Long id = (Long) data(r).get("id");
        created.add(id);
        return id;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(BaseResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private static StylerEntity stylist(String id, String province, String city) {
        StylerEntity s = new StylerEntity();
        s.setStylerId(id);
        s.setProvince(province);
        s.setCity(city);
        s.setBusinessName(city + " Studio");
        s.setFirstname("Area");
        s.setLastname("Test");
        s.setEmailAddress(id.toLowerCase() + "@rapidstylers.test");
        s.setBusinessAddress("1 Main St, " + city);
        s.setVerificationStatus(VERIFICATION_APPROVED);
        s.setStatus("0");
        return s;
    }
}
