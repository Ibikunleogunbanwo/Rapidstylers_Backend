package com.macrotel.rapidstylers.styler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.common.service.DTOService;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.styler.dto.StylerAccountDTO;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.AvailabilityExceptionRepo;
import com.macrotel.rapidstylers.styler.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.support.TestServices;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The home page's distance choice: the city lookup that backs up the geo index
 * must keep the same radius, and one stylist without coordinates must not
 * empty everyone else's results.
 */
class NearbyRadiusTest {

    // Downtown Calgary; the far stylist is in the city's north-east (about 18 km).
    private static final double LAT = 51.0447, LNG = -114.0719;

    private TestServices services;
    private StylerRepo stylerRepo;

    @BeforeEach
    void setUp() {
        services = new TestServices();
        stylerRepo = mock(StylerRepo.class);
        ReadCacheService readCacheService = mock(ReadCacheService.class);
        DTOService dtoService = mock(DTOService.class);
        LocationCacheService geo = mock(LocationCacheService.class);
        services.stylerRepo = stylerRepo;
        services.readCacheService = readCacheService;
        services.dtoService = dtoService;
        services.locationCacheService = geo;
        services.objectMapper = new ObjectMapper();
        services.availabilityRepo = mock(AvailabilityRepo.class);
        services.availabilityExceptionRepo = mock(AvailabilityExceptionRepo.class);
        when(readCacheService.getOrLoad(anyString(), any(Duration.class), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
        when(dtoService.stylerAccountDTO(any())).thenAnswer(inv -> {
            StylerAccountDTO dto = new StylerAccountDTO();
            dto.setStylerId(((StylerEntity) inv.getArgument(0)).getStylerId());
            return dto;
        });
        // The geo index has not picked anyone up yet: only the city lookup answers.
        when(geo.findNearbyWithDistance(anyDouble(), anyDouble(), anyDouble())).thenReturn(Collections.emptyMap());
    }

    @Test
    void cityLookupKeepsTheChosenRadius() {
        StylerEntity near = styler("NEAR", 51.0480, -114.0700);
        StylerEntity far = styler("FAR", 51.1500, -113.9000);
        when(stylerRepo.findByCityIgnoreCase("Calgary")).thenReturn(List.of(near, far));
        when(stylerRepo.findByStylerIdIn(anyList())).thenReturn(List.of(near, far));

        assertEquals(List.of("NEAR"), ids(services.searchService().searchNearby(LNG, LAT, 10, null, "Calgary")));
        assertEquals(List.of("NEAR", "FAR"), ids(services.searchService().searchNearby(LNG, LAT, 25, null, "Calgary")));
    }

    @Test
    void aStylistWithoutCoordinatesDoesNotEmptyTheSearch() {
        StylerEntity near = styler("NEAR", 51.0480, -114.0700);
        StylerEntity unplaced = styler("NOGEO", null, null);
        when(stylerRepo.findByCityIgnoreCase("Calgary")).thenReturn(List.of(unplaced, near));
        when(stylerRepo.findByStylerIdIn(anyList())).thenReturn(List.of(near));

        BaseResponse response = services.searchService().searchNearby(LNG, LAT, 25, null, "Calgary");

        assertEquals("200", response.getStatusCode());
        assertEquals(List.of("NEAR"), ids(response));
    }

    private static List<String> ids(BaseResponse response) {
        Object data = response.getData();
        if (data instanceof Map) data = ((Map<?, ?>) data).get("items");
        return ((List<?>) data).stream().map(d -> ((StylerAccountDTO) d).getStylerId()).toList();
    }

    private static StylerEntity styler(String id, Double lat, Double lng) {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId(id);
        styler.setVerificationStatus("APPROVED");
        styler.setServiceTypeId("3");
        styler.setCity("Calgary");
        styler.setProvince("Alberta");
        styler.setBusinessAddress("700 2 St SW, Calgary");
        styler.setLatitude(lat);
        styler.setLongitude(lng);
        return styler;
    }
}
