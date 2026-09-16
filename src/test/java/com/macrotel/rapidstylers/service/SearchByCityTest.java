package com.macrotel.rapidstylers.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.dto.StylerAccountDTO;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.repo.StylerRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in the city-search contract: a city with professionals returns them;
 * a city without any widens to that city's province with the widened marker,
 * so the results page never shows an empty grid it could have filled.
 */
class SearchByCityTest {

    private AppService appService;
    private StylerRepo stylerRepo;
    private ReadCacheService readCacheService;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        stylerRepo = mock(StylerRepo.class);
        readCacheService = mock(ReadCacheService.class);
        DTOService dtoService = mock(DTOService.class);
        appService.stylerRepo = stylerRepo;
        appService.readCacheService = readCacheService;
        appService.dtoService = dtoService;
        appService.objectMapper = new ObjectMapper();
        // The read-cache passthrough invokes real loaders, so the DTO builder
        // must produce something (its internals query repos we don't stub).
        when(dtoService.stylerAccountDTO(any())).thenReturn(new StylerAccountDTO());
    }

    @Test
    void cityWithProfessionalsReturnsThemWithoutWidening() {
        StylerEntity calgaryStyler = approvedStyler("S1", "Calgary", "Alberta");
        when(readCacheService.getOrLoad(anyString(), any(Duration.class), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
        when(stylerRepo.findByCityIgnoreCase("Calgary"))
                .thenReturn(Collections.singletonList(calgaryStyler));

        BaseResponse response = appService.searchStylerByCity("Calgary");

        assertEquals("200", response.getStatusCode());
        List<?> data = (List<?>) response.getData();
        assertEquals(1, data.size());
    }

    @Test
    void emptyCityWidensToTheCitysProvinceWithTheWidenedMarker() {
        when(readCacheService.getOrLoad(anyString(), any(Duration.class), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
        // The city has rows, but none approved — so the cached city list is
        // empty while the rows still reveal which province to widen to.
        when(stylerRepo.findByCityIgnoreCase("Strathmore"))
                .thenReturn(Collections.singletonList(pendingStyler("S1", "Strathmore", "Alberta")));
        when(stylerRepo.findByProvinceIgnoreCase("Alberta"))
                .thenReturn(Collections.singletonList(approvedStyler("S9", "Calgary", "Alberta")));

        BaseResponse response = appService.searchStylerByCity("Strathmore");

        assertEquals("200", response.getStatusCode());
        assertTrue(response.getData() instanceof Map);
        Map<?, ?> payload = (Map<?, ?>) response.getData();
        assertEquals(Boolean.TRUE, payload.get("widened"));
        assertEquals("Alberta", payload.get("widenedProvince"));
        List<?> items = (List<?>) payload.get("items");
        assertEquals(1, items.size());
    }

    @Test
    void cityUnknownAnywhereReturnsAnEmptyListNotAnError() {
        when(readCacheService.getOrLoad(anyString(), any(Duration.class), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
        when(stylerRepo.findByCityIgnoreCase("Nowhere")).thenReturn(Collections.emptyList());

        BaseResponse response = appService.searchStylerByCity("Nowhere");

        assertEquals("200", response.getStatusCode());
        List<?> data = (List<?>) response.getData();
        assertEquals(0, data.size());
    }

    private StylerEntity approvedStyler(String id, String city, String province) {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId(id);
        styler.setVerificationStatus("APPROVED");
        styler.setServiceTypeId("3");
        styler.setCity(city);
        styler.setProvince(province);
        // Bookability includes a published address, so the fixture carries one;
        // otherwise these rows would be filtered for the wrong reason.
        styler.setBusinessAddress("700 2 St SW, " + city);
        return styler;
    }

    private StylerEntity pendingStyler(String id, String city, String province) {
        StylerEntity styler = approvedStyler(id, city, province);
        styler.setVerificationStatus("PENDING");
        return styler;
    }
}
