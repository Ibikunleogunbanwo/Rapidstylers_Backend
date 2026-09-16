package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.entity.AvailabilityEntity;
import com.macrotel.rapidstylers.entity.ServiceEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.SubServiceEntity;
import com.macrotel.rapidstylers.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.repo.ServiceRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoContentInitializerTest {

    private ServiceEntity serviceType(long id, String name) {
        ServiceEntity s = new ServiceEntity();
        s.setId(id);
        s.setServiceName(name);
        return s;
    }

    private StylerEntity approvedStyler(String typeId) {
        StylerEntity s = new StylerEntity();
        s.setVerificationStatus("APPROVED");
        s.setServiceTypeId(typeId);
        return s;
    }

    private DemoContentInitializer init(ServiceRepo services, StylerRepo stylers,
                                        SubServiceRepo subs, AvailabilityRepo availability) {
        stylersEchoingSave(stylers);
        DemoContentInitializer initializer = new DemoContentInitializer(services, stylers, subs, availability);
        ReflectionTestUtils.setField(initializer, "demoSeed", "true");
        return initializer;
    }

    private SubServiceRepo subsAlwaysMissing() {
        SubServiceRepo subs = mock(SubServiceRepo.class);
        when(subs.isServiceExist(anyString(), anyString())).thenReturn(Optional.empty());
        return subs;
    }

    /** A repo mock whose save returns its argument, like a real JPA repository. */
    private StylerRepo stylersEchoingSave(StylerRepo stylers) {
        when(stylers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return stylers;
    }

    private AvailabilityRepo availabilityAlwaysEmpty() {
        AvailabilityRepo availability = mock(AvailabilityRepo.class);
        when(availability.findByStylerId(anyString())).thenReturn(List.of());
        return availability;
    }

    @Test
    void doesNothingWhenGateIsOff() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        DemoContentInitializer initializer = new DemoContentInitializer(services, stylers, subsAlwaysMissing(), availabilityAlwaysEmpty());
        // default (unset) gate
        ReflectionTestUtils.setField(initializer, "demoSeed", "false");

        initializer.run();

        verify(services, never()).findAll();
        verify(stylers, never()).save(any());
    }

    @Test
    void topsEveryServiceTypeUpToTheShowroomFloor() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(
                serviceType(1, "Nail Technician"),
                serviceType(2, "Eyelash Technician"),
                serviceType(3, "Barber")));
        // Type 2 already has the full floor: nothing should be added there.
        List<StylerEntity> covered = List.of(
                approvedStyler("2"), approvedStyler("2"), approvedStyler("2"),
                approvedStyler("2"), approvedStyler("2"));
        when(stylers.findByServiceTypeId("1")).thenReturn(List.of());
        when(stylers.findByServiceTypeId("2")).thenReturn(covered);
        when(stylers.findByServiceTypeId("3")).thenReturn(List.of());
        SubServiceRepo subs = subsAlwaysMissing();
        AvailabilityRepo availability = availabilityAlwaysEmpty();

        init(services, stylers, subs, availability).run();

        ArgumentCaptor<StylerEntity> saved = ArgumentCaptor.forClass(StylerEntity.class);
        // 5 for the empty nails type + 5 for the empty barber type; the covered type untouched.
        verify(stylers, times(10)).save(saved.capture());
        List<String> typeIds = saved.getAllValues().stream().map(StylerEntity::getServiceTypeId).toList();
        assertEquals(10, typeIds.size());
        assertTrue(typeIds.contains("1") && typeIds.contains("3") && !typeIds.contains("2"));
        // all saved rows are approved (the visibility gate public search filters on)
        assertTrue(saved.getAllValues().stream().allMatch(s -> "APPROVED".equals(s.getVerificationStatus())));
    }

    @Test
    void aPartiallyFilledTypeIsOnlyToppedUp() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(1, "Nail Technician")));
        when(stylers.findByServiceTypeId("1")).thenReturn(List.of(approvedStyler("1"), approvedStyler("1")));

        init(services, stylers, subsAlwaysMissing(), availabilityAlwaysEmpty()).run();

        ArgumentCaptor<StylerEntity> saved = ArgumentCaptor.forClass(StylerEntity.class);
        verify(stylers, times(3)).save(saved.capture());
        // business names continue the count: Studio 3, 4, 5 — never a duplicate "Studio 1"
        List<String> names = saved.getAllValues().stream().map(StylerEntity::getBusinessName).toList();
        assertTrue(names.contains("Demo Nail Technician Studio 3"));
        assertTrue(names.contains("Demo Nail Technician Studio 5"));
        assertFalse(names.contains("Demo Nail Technician Studio 1"));
    }

    @Test
    void everySeededStylerGetsPricedServicesAndWeeklyAvailability() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(1, "Nail Technician")));
        when(stylers.findByServiceTypeId("1")).thenReturn(List.of());
        SubServiceRepo subs = subsAlwaysMissing();
        AvailabilityRepo availability = availabilityAlwaysEmpty();

        init(services, stylers, subs, availability).run();

        // 5 stylists x 2 services each (manicure + pedicure)
        ArgumentCaptor<SubServiceEntity> savedServices = ArgumentCaptor.forClass(SubServiceEntity.class);
        verify(subs, times(10)).save(savedServices.capture());
        assertTrue(savedServices.getAllValues().stream().allMatch(s -> s.getPrice() != null && s.getDurationMinutes() != null));
        assertEquals("Manicure", savedServices.getAllValues().get(0).getName());
        assertEquals("Pedicure", savedServices.getAllValues().get(1).getName());
        // 5 stylists x 2 weekly windows each (saved as one two-slot batch per stylist)
        ArgumentCaptor<List<AvailabilityEntity>> savedSlots = ArgumentCaptor.forClass(List.class);
        verify(availability, times(5)).saveAll(savedSlots.capture());
        assertTrue(savedSlots.getAllValues().stream().allMatch(slots -> slots.size() == 2));
    }

    @Test
    void theCatalogueMatchesTheStylistField() {
        DemoContentInitializer initializer = init(mock(ServiceRepo.class), mock(StylerRepo.class), subsAlwaysMissing(), availabilityAlwaysEmpty());
        StylerEntity barber = initializer.demoStylerFor(serviceType(3, "Barber"));
        StylerEntity nails = initializer.demoStylerFor(serviceType(1, "Nail Technician"));
        StylerEntity unknown = initializer.demoStylerFor(serviceType(9, "Threading Specialist"));

        // Catalogues are per-field: a barber never lists a pedicure.
        assertTrue(barber.getBusinessName().contains("Barber"));
        assertTrue(nails.getBusinessName().contains("Nail"));
        // Unknown types still get a sensible (hair) catalogue, not nothing.
        assertTrue(unknown.getDescription() != null && !unknown.getDescription().isEmpty());
    }

    @Test
    void legacyDemoStylistsGetTheCatalogueAndAddressBackfilledButRealOnesDoNot() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(1, "Nail Technician")));
        // One legacy DEMO row and one real professional, floor already met.
        StylerEntity legacy = approvedStyler("1");
        legacy.setStylerId("DEMO9999");
        StylerEntity real = approvedStyler("1");
        real.setStylerId("DS5713");
        when(stylers.findByServiceTypeId("1")).thenReturn(List.of(legacy, real, approvedStyler("1"), approvedStyler("1"), approvedStyler("1")));
        SubServiceRepo subs = subsAlwaysMissing();
        AvailabilityRepo availability = availabilityAlwaysEmpty();

        init(services, stylers, subs, availability).run();

        // The legacy demo row is backfilled (2 services + 2 slots)...
        ArgumentCaptor<SubServiceEntity> savedServices = ArgumentCaptor.forClass(SubServiceEntity.class);
        verify(subs, times(2)).save(savedServices.capture());
        ArgumentCaptor<List<AvailabilityEntity>> savedSlots = ArgumentCaptor.forClass(List.class);
        verify(availability, times(1)).saveAll(savedSlots.capture());
        // ...and its missing address is filled in, so bookings against it can
        // finally name a destination.
        ArgumentCaptor<StylerEntity> savedStyler = ArgumentCaptor.forClass(StylerEntity.class);
        verify(stylers, times(1)).save(savedStyler.capture());
        assertEquals("DEMO9999", savedStyler.getValue().getStylerId());
        assertEquals(
                DemoContentInitializer.demoAddressFor("DEMO9999"),
                savedStyler.getValue().getBusinessAddress());
    }

    @Test
    void aRealProfessionalsAddressIsNeverRewrittenByTheSeeder() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(1, "Nail Technician")));
        StylerEntity demoWithAddress = approvedStyler("1");
        demoWithAddress.setStylerId("DEMO4242");
        demoWithAddress.setBusinessAddress("Suite 300, 1 Real St SW");
        StylerEntity real = approvedStyler("1");
        real.setStylerId("DS5713");
        real.setBusinessAddress("700 2 St SW");
        when(stylers.findByServiceTypeId("1")).thenReturn(List.of(
                demoWithAddress, real, approvedStyler("1"), approvedStyler("1"), approvedStyler("1")));

        init(services, stylers, subsAlwaysMissing(), availabilityAlwaysEmpty()).run();

        assertEquals("Suite 300, 1 Real St SW", demoWithAddress.getBusinessAddress());
        assertEquals("700 2 St SW", real.getBusinessAddress());
        verify(stylers, never()).save(any());
    }

    @Test
    void everySeededStylerGetsAnAddressOnARealCalgaryBlock() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(4, "Hairstylist")));
        when(stylers.findByServiceTypeId("4")).thenReturn(List.of());

        init(services, stylers, subsAlwaysMissing(), availabilityAlwaysEmpty()).run();

        ArgumentCaptor<StylerEntity> saved = ArgumentCaptor.forClass(StylerEntity.class);
        verify(stylers, times(5)).save(saved.capture());
        for (StylerEntity demo : saved.getAllValues()) {
            assertTrue(demo.getBusinessAddress() != null && !demo.getBusinessAddress().isEmpty());
            // The address stays put on a restart, so a saved booking never moves.
            assertEquals(demo.getBusinessAddress(), DemoContentInitializer.demoAddressFor(demo.getStylerId()));
        }
    }

    @Test
    void reRunningNeverDuplicatesRows() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(1, "Nail Technician")));
        // A restart sees the floor already met: nothing new is created.
        when(stylers.findByServiceTypeId("1")).thenReturn(List.of(
                approvedStyler("1"), approvedStyler("1"), approvedStyler("1"),
                approvedStyler("1"), approvedStyler("1")));
        SubServiceRepo subs = mock(SubServiceRepo.class);
        when(subs.isServiceExist(anyString(), anyString())).thenReturn(Optional.of(new SubServiceEntity()));
        AvailabilityRepo availability = mock(AvailabilityRepo.class);
        when(availability.findByStylerId(anyString())).thenReturn(List.of(new AvailabilityEntity()));

        init(services, stylers, subs, availability).run();

        verify(stylers, never()).save(any());
        verify(subs, never()).save(any());
        verify(availability, never()).save(any());
    }

    @Test
    void seededRowsAreInertAndSearchVisible() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(4, "Hairstylist")));
        when(stylers.findByServiceTypeId("4")).thenReturn(List.of());

        init(services, stylers, subsAlwaysMissing(), availabilityAlwaysEmpty()).run();

        ArgumentCaptor<StylerEntity> saved = ArgumentCaptor.forClass(StylerEntity.class);
        verify(stylers, times(5)).save(saved.capture());
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
        init(services, stylers, subsAlwaysMissing(), availabilityAlwaysEmpty()).run();

        verify(stylers, never()).save(any());
    }

    @Test
    void idsAreUniqueAcrossCalls() {
        DemoContentInitializer initializer = new DemoContentInitializer(
                mock(ServiceRepo.class), mock(StylerRepo.class), subsAlwaysMissing(), availabilityAlwaysEmpty());
        StylerEntity a = initializer.demoStylerFor(serviceType(1, "Nail Technician"));
        StylerEntity b = initializer.demoStylerFor(serviceType(1, "Nail Technician"));
        assertNotEquals(a.getStylerId(), b.getStylerId());
    }
}
