package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.entity.ServiceEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.repo.ServiceRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoContentInitializerTest {

    private ServiceEntity serviceType(long id, String name) {
        ServiceEntity s = new ServiceEntity();
        s.setId(id);
        s.setServiceName(name);
        return s;
    }

    private DemoContentInitializer init(ServiceRepo services, StylerRepo stylers) {
        DemoContentInitializer init = new DemoContentInitializer(services, stylers);
        ReflectionTestUtils.setField(init, "demoSeed", "true");
        return init;
    }

    @Test
    void doesNothingWhenGateIsOff() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        DemoContentInitializer init = new DemoContentInitializer(services, stylers);
        // default (unset) gate
        ReflectionTestUtils.setField(init, "demoSeed", "false");

        init.run();

        verify(services, never()).findAll();
        verify(stylers, never()).save(any());
    }

    @Test
    void seedsOneStylerPerEmptyServiceType() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(
                serviceType(1, "Nail Technician"),
                serviceType(2, "Eyelash Technician"),
                serviceType(3, "Barber")));
        // Only service type 2 already has an approved stylist.
        StylerEntity existing = new StylerEntity();
        existing.setVerificationStatus("APPROVED");
        existing.setServiceTypeId("2");
        when(stylers.findByServiceTypeId("1")).thenReturn(List.of());
        when(stylers.findByServiceTypeId("2")).thenReturn(List.of(existing));
        when(stylers.findByServiceTypeId("3")).thenReturn(List.of());

        init(services, stylers).run();

        ArgumentCaptor<StylerEntity> saved = ArgumentCaptor.forClass(StylerEntity.class);
        verify(stylers, org.mockito.Mockito.times(2)).save(saved.capture());
        // exactly the two empty types, not the covered one
        assertTrue(saved.getAllValues().stream().allMatch(s -> "APPROVED".equals(s.getVerificationStatus())));
        List<String> typeIds = saved.getAllValues().stream().map(StylerEntity::getServiceTypeId).toList();
        assertTrue(typeIds.contains("1") && typeIds.contains("3") && !typeIds.contains("2"));
    }

    @Test
    void seededRowsAreInertAndSearchVisible() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(4, "Hairstylist")));
        when(stylers.findByServiceTypeId("4")).thenReturn(List.of());

        init(services, stylers).run();

        ArgumentCaptor<StylerEntity> saved = ArgumentCaptor.forClass(StylerEntity.class);
        verify(stylers).save(saved.capture());
        StylerEntity demo = saved.getValue();

        // visible to public search, which filters on approved + bookable
        assertEquals("APPROVED", demo.getVerificationStatus());
        assertEquals("COMPLETE", demo.getConnectOnboardingStatus());
        assertEquals("0", demo.getIsOnline());
        assertEquals("4", demo.getServiceTypeId());
        // inert: unmatchable password, no contact surface
        assertNotEquals("password", demo.getPassword());
        assertTrue(demo.getPassword().startsWith("$2"));
        assertTrue(demo.getEmailAddress().endsWith("@" + DemoContentInitializer.DEMO_EMAIL_DOMAIN));
        assertFalse(demo.getPassword().isEmpty());
        // id starts with the greppable DEMO marker
        assertTrue(demo.getStylerId().startsWith("DEMO"));
        // coordinates present so nearby search also works
        assertTrue(demo.getLatitude() != null && demo.getLongitude() != null);
    }

    @Test
    void unmatchableHashNeverVerifiesARealPassword() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertFalse(encoder.matches("password", DemoContentInitializer.UNMATCHABLE_PASSWORD_HASH));
        assertFalse(encoder.matches("changeme", DemoContentInitializer.UNMATCHABLE_PASSWORD_HASH));
        assertFalse(encoder.matches("demo", DemoContentInitializer.UNMATCHABLE_PASSWORD_HASH));
    }

    @Test
    void aSeedingFailureNeverBlocksBoot() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenThrow(new RuntimeException("db down"));

        // must not throw
        init(services, stylers).run();

        verify(stylers, never()).save(any());
    }

    @Test
    void idsAreUniqueAcrossCalls() {
        DemoContentInitializer initializer = new DemoContentInitializer(mock(ServiceRepo.class), mock(StylerRepo.class));
        StylerEntity a = initializer.demoStylerFor(serviceType(1, "Nail Technician"));
        StylerEntity b = initializer.demoStylerFor(serviceType(1, "Nail Technician"));
        assertNotEquals(a.getStylerId(), b.getStylerId());
    }
}
