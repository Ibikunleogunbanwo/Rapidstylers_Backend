package com.macrotel.rapidstylers.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.dto.StylerAccountDTO;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.repo.StylerRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the Category A N+1 fix: searchNearby must fetch all nearby stylers
 * with ONE batch IN query and build DTOs through the read cache (never one
 * findByStylerId call per styler, never a raw DTO build per request).
 */
class SearchReadCacheTest {

    private AppService appService;
    private StylerRepo stylerRepo;
    private LocationCacheService locationCacheService;
    private DTOService dtoService;
    private ReadCacheService readCacheService;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        stylerRepo = mock(StylerRepo.class);
        locationCacheService = mock(LocationCacheService.class);
        dtoService = mock(DTOService.class);
        readCacheService = mock(ReadCacheService.class);
        appService.stylerRepo = stylerRepo;
        appService.locationCacheService = locationCacheService;
        appService.dtoService = dtoService;
        appService.readCacheService = readCacheService;
        appService.objectMapper = new ObjectMapper();
    }

    @Test
    void searchNearbyUsesOneBatchQueryAndServesCachedDtos() {
        when(locationCacheService.findNearbyWithDistance(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(distances("S1", 5.0, "S2", 8.0));
        when(stylerRepo.findByStylerIdIn(anyCollection()))
                .thenReturn(Arrays.asList(approvedStyler("S1"), approvedStyler("S2")));
        // Warm DTO cache: the read cache serves canned DTOs, so the DTO builder
        // (which itself does service-name + review queries) is never invoked.
        when(readCacheService.getOrLoad(anyString(), any(Duration.class), any(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.startsWith(ReadCacheService.KEY_STYLER_DTO)) {
                StylerAccountDTO dto = new StylerAccountDTO();
                dto.setStylerId(key.substring(ReadCacheService.KEY_STYLER_DTO.length()));
                return dto;
            }
            return ((Supplier<?>) inv.getArgument(3)).get();
        });

        BaseResponse response = appService.searchNearby(10.0, 20.0, 50.0, null, null);

        assertEquals("200", response.getStatusCode());
        List<?> data = (List<?>) response.getData();
        assertEquals(2, data.size());
        verify(stylerRepo).findByStylerIdIn(anyCollection());
        verify(stylerRepo, never()).findByStylerId(anyString());
        verify(dtoService, never()).stylerAccountDTO(any(StylerEntity.class));
        // Distance is stamped on a per-result copy — the shared cached DTO keeps
        // its own distance so concurrent searches cannot corrupt each other.
        StylerAccountDTO first = (StylerAccountDTO) data.get(0);
        assertEquals(5.0, first.getDistanceKm());
    }

    @Test
    void searchNearbyBuildsDtoThroughLoaderOnColdCache() {
        when(locationCacheService.findNearbyWithDistance(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Collections.singletonMap("S1", 5.0));
        StylerEntity s1 = approvedStyler("S1");
        when(stylerRepo.findByStylerIdIn(anyCollection())).thenReturn(Collections.singletonList(s1));
        when(dtoService.stylerAccountDTO(s1)).thenReturn(dto("S1"));
        when(readCacheService.getOrLoad(anyString(), any(Duration.class), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());

        BaseResponse response = appService.searchNearby(10.0, 20.0, 50.0, null, null);

        assertEquals("200", response.getStatusCode());
        List<?> data = (List<?>) response.getData();
        assertEquals(1, data.size());
        verify(stylerRepo, never()).findByStylerId(anyString());
        verify(dtoService).stylerAccountDTO(s1);
    }

    private StylerEntity approvedStyler(String id) {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId(id);
        styler.setVerificationStatus("APPROVED");
        styler.setServiceTypeId("3");
        return styler;
    }

    private StylerAccountDTO dto(String id) {
        StylerAccountDTO dto = new StylerAccountDTO();
        dto.setStylerId(id);
        return dto;
    }

    private Map<String, Double> distances(String id1, double d1, String id2, double d2) {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put(id1, d1);
        map.put(id2, d2);
        return map;
    }
}
