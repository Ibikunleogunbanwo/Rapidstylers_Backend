package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.dto.StylerAccountDTO;
import com.macrotel.rapidstylers.entity.ServiceEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.repo.ServiceRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Public search shows only professionals a customer can actually book: with
 * payments live, an approved stylist who never finished Connect onboarding
 * must not appear in any customer-facing list (category tabs, name, province,
 * city). Before this rule the booking endpoint rejected exactly the profiles
 * search happily served — a service failure rather than a detail.
 */
class BookableFilterTest {

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
        when(dtoService.stylerAccountDTO(any())).thenReturn(new StylerAccountDTO());
        // The passthrough invokes the real loader so the filter is exercised.
        when(readCacheService.getOrLoad(anyString(), any(Duration.class), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
        // Payments ON: the Stripe clause of the bookable filter must exercise,
        // which is the production configuration this test exists to pin.
        StripeService stripeService = mock(StripeService.class);
        when(stripeService.isConfigured()).thenReturn(true);
        appService.stripeService = stripeService;
        // The category search resolves the service type before filtering.
        ServiceRepo serviceRepo = mock(ServiceRepo.class);
        ServiceEntity serviceType = new ServiceEntity();
        serviceType.setId(3L);
        serviceType.setServiceName("Barber");
        when(serviceRepo.findById(3L)).thenReturn(Optional.of(serviceType));
        appService.serviceRepo = serviceRepo;
    }

    private StylerEntity styler(String id, String verification, String onboarding) {
        StylerEntity s = new StylerEntity();
        s.setStylerId(id);
        s.setVerificationStatus(verification);
        s.setConnectOnboardingStatus(onboarding);
        s.setProvince("Alberta");
        // A published address is part of being bookable, so a fixture without
        // one would be filtered for the wrong reason and the Stripe clause
        // this test exists to pin would never be reached.
        s.setBusinessAddress("700 2 St SW, Calgary, Alberta");
        return s;
    }

    @Test
    void nameSearchExcludesApprovedButNotOnboardedStylists() {
        when(stylerRepo.searchStyler("studio"))
                .thenReturn(Collections.singletonList(styler("S1", "APPROVED", "PENDING")));

        List<?> data = (List<?>) appService.searchStyler("studio").getData();

        assertEquals(0, data.size());
    }

    @Test
    void nameSearchIncludesFullyCompletedProfiles() {
        when(stylerRepo.searchStyler("studio"))
                .thenReturn(Collections.singletonList(styler("S1", "APPROVED", "COMPLETE")));

        List<?> data = (List<?>) appService.searchStyler("studio").getData();

        assertEquals(1, data.size());
    }

    @Test
    void categorySearchExcludesApprovedButNotOnboardedStylists() {
        when(stylerRepo.findByServiceTypeId("3"))
                .thenReturn(Collections.singletonList(styler("S1", "APPROVED", null)));

        List<?> data = (List<?>) appService.getStylerByService("3").getData();

        assertEquals(0, data.size());
    }

    @Test
    void provinceSearchExcludesApprovedButNotOnboardedStylists() {
        when(stylerRepo.findByProvinceIgnoreCase("Alberta"))
                .thenReturn(Collections.singletonList(styler("S1", "APPROVED", "PENDING")));

        List<?> data = (List<?>) appService.searchStylerByProvince("Alberta").getData();

        assertEquals(0, data.size());
    }

    @Test
    void cityWideningConsidersOnlyBookableStylistsForTheWidenedProvince() {
        // The city has one approved-but-not-onboarded stylist: invisible, but
        // their row still reveals the province to widen to.
        when(stylerRepo.findByCityIgnoreCase("Strathmore"))
                .thenReturn(Collections.singletonList(styler("S1", "APPROVED", "PENDING")));
        // The province's only stylist is also not onboarded, so widening
        // must come back empty rather than surfacing an unbookable profile.
        when(stylerRepo.findByProvinceIgnoreCase("Alberta"))
                .thenReturn(Collections.singletonList(styler("S9", "APPROVED", "PENDING")));

        Object data = appService.searchStylerByCity("Strathmore").getData();

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> payload = (java.util.Map<String, Object>) data;
        assertEquals(0, ((List<?>) payload.get("items")).size());
        assertEquals(Boolean.TRUE, payload.get("widened"));
    }
}
