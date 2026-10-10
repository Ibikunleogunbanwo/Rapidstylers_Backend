package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.common.service.DTOService;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.support.TestServices;
import com.macrotel.rapidstylers.catalog.entity.ServiceEntity;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.catalog.repo.IdentificationRepo;
import com.macrotel.rapidstylers.catalog.repo.ServiceRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in bounded, non-blocking read-cache warming (issue #4): the catalog keys
 * are always warmed via the shared loaders, only a capped number of approved
 * stylist profiles are warmed (newest first), and warming is a no-op when
 * disabled. It must never throw when loaders/repos are sparse.
 */
class CacheWarmerTest {

    private TestServices services;
    private ReadCacheService readCacheService;
    private StylerRepo stylerRepo;
    private ServiceRepo serviceRepo;
    private DTOService dtoService;

    @BeforeEach
    void setUp() {
        services = new TestServices();
        readCacheService = mock(ReadCacheService.class);
        stylerRepo = mock(StylerRepo.class);
        serviceRepo = mock(ServiceRepo.class);
        IdentificationRepo identificationRepo = mock(IdentificationRepo.class);
        com.macrotel.rapidstylers.catalog.repo.BlogPostRepo blogPostRepo =
                mock(com.macrotel.rapidstylers.catalog.repo.BlogPostRepo.class);
        dtoService = mock(DTOService.class);
        services.readCacheService = readCacheService;
        services.stylerRepo = stylerRepo;
        services.serviceRepo = serviceRepo;
        services.identificationRepo = identificationRepo;
        services.blogPostRepo = blogPostRepo;
        services.dtoService = dtoService;
        when(serviceRepo.findAll()).thenReturn(Collections.emptyList());
        when(identificationRepo.findAll()).thenReturn(Collections.emptyList());
        when(blogPostRepo.findAll()).thenReturn(Collections.emptyList());
        when(dtoService.stylerAccountDTO(any())).thenReturn(new com.macrotel.rapidstylers.styler.dto.StylerAccountDTO());
        ReflectionTestUtils.setField(services, "warmReadCachesEnabled", true);
        ReflectionTestUtils.setField(services, "warmMaxStylers", 2);
    }

    private void answerThroughLoader() {
        when(readCacheService.getOrLoad(anyString(), any(), any(), any())).thenAnswer(inv ->
                ((Supplier<?>) inv.getArgument(3)).get());
    }

    @Test
    void warmsAllCatalogKeys() {
        when(stylerRepo.findAll()).thenReturn(Collections.emptyList());
        answerThroughLoader();

        services.stylerService().warmReadCaches();

        verify(readCacheService).getOrLoad(eq(ReadCacheService.KEY_CATALOG_SERVICES),
                any(), any(), any());
        verify(readCacheService).getOrLoad(eq(ReadCacheService.KEY_CATALOG_IDENTIFICATIONS),
                any(), any(), any());
        verify(readCacheService).getOrLoad(eq(ReadCacheService.KEY_CATALOG_BLOGS),
                any(), any(), any());
    }

    @Test
    void serviceLoaderBuildsExpectedMapShape() {
        ServiceEntity service = new ServiceEntity();
        service.setId(7L);
        service.setServiceName("Nail Technician");
        service.setStatus("ACTIVE");
        service.setServiceImageUrl("http://img");
        service.setDescription("desc");
        when(serviceRepo.findAll()).thenReturn(Arrays.asList(service));

        List<?> result = services.catalogService().loadCatalogServices();

        assertEquals(1, result.size());
        Map<?, ?> entry = (Map<?, ?>) result.get(0);
        assertEquals("7", entry.get("id"));
        assertEquals("Nail Technician", entry.get("serviceName"));
        assertEquals("ACTIVE", entry.get("status"));
        assertEquals("http://img", entry.get("imageUrl"));
        assertEquals("desc", entry.get("description"));
    }

    @Test
    void warmsNewestApprovedStylerFirstWithinCap() {
        when(stylerRepo.findAll()).thenReturn(Arrays.asList(
                approvedStyler("S1", "2026-01-01 00:00:00"),
                approvedStyler("S2", "2026-02-01 00:00:00")));
        answerThroughLoader();
        ReflectionTestUtils.setField(services, "warmMaxStylers", 1);

        org.mockito.ArgumentCaptor<String> cap = org.mockito.ArgumentCaptor.forClass(String.class);
        services.stylerService().warmReadCaches();
        verify(readCacheService, org.mockito.Mockito.atLeastOnce()).getOrLoad(cap.capture(), any(), any(), any());

        // S2 is newest -> warmed; S1 (older) must NOT be warmed under cap=1.
        verify(readCacheService).getOrLoad(eq(ReadCacheService.KEY_STYLER_DTO + "S2"),
                any(), any(), any());
        verify(readCacheService, never()).getOrLoad(eq(ReadCacheService.KEY_STYLER_DTO + "S1"),
                any(), any(), any());
    }

    @Test
    void warmingIsNoOpWhenDisabled() {
        ReflectionTestUtils.setField(services, "warmReadCachesEnabled", false);

        services.stylerService().warmReadCaches();

        verify(readCacheService, never()).getOrLoad(anyString(), any(), any(), any());
    }

    @Test
    void warmingIsSkippedWhileAnotherWarmIsInFlight() {
        when(stylerRepo.findAll()).thenReturn(Collections.emptyList());
        answerThroughLoader();
        java.util.concurrent.atomic.AtomicBoolean inFlight =
                (java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(services.stylerService(), "warmInFlight");
        inFlight.set(true);

        services.stylerService().warmReadCaches();

        verify(readCacheService, never()).getOrLoad(anyString(), any(), any(), any());
    }

    private StylerEntity approvedStyler(String id, String insertedDt) {
        StylerEntity e = new StylerEntity();
        e.setStylerId(id);
        e.setInsertedDt(insertedDt);
        e.setVerificationStatus("APPROVED");
        return e;
    }
}