package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.catalog.entity.ServiceEntity;
import com.macrotel.rapidstylers.catalog.repo.ServiceRepo;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.engagement.entity.ReviewEntity;
import com.macrotel.rapidstylers.engagement.repo.ReviewRepo;
import com.macrotel.rapidstylers.styler.entity.AvailabilityEntity;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.repo.SubServiceRepo;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        return init(services, stylers, subs, availability,
                appointmentsEchoingSave(), reviewsEchoingSave(), usersEchoingSave());
    }

    private DemoContentInitializer init(ServiceRepo services, StylerRepo stylers, SubServiceRepo subs,
                                        AvailabilityRepo availability, BookAppointmentRepo appointments,
                                        ReviewRepo reviews, UserRepo users) {
        stylersEchoingSave(stylers);
        DemoContentInitializer initializer = new DemoContentInitializer(
                services, stylers, subs, availability, appointments, reviews, users);
        ReflectionTestUtils.setField(initializer, "demoSeed", "true");
        return initializer;
    }

    private BookAppointmentRepo appointmentsEchoingSave() {
        BookAppointmentRepo repo = mock(BookAppointmentRepo.class);
        when(repo.findByStylerId(anyString())).thenReturn(List.of());
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return repo;
    }

    private ReviewRepo reviewsEchoingSave() {
        ReviewRepo repo = mock(ReviewRepo.class);
        when(repo.findByStylerId(anyString())).thenReturn(List.of());
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return repo;
    }

    private UserRepo usersEchoingSave() {
        UserRepo repo = mock(UserRepo.class);
        when(repo.findByEmailAddress(anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return repo;
    }

    /** A user repo that remembers what it saved, the way a real one does. */
    private UserRepo usersPersisting() {
        UserRepo repo = mock(UserRepo.class);
        Map<String, UserEntity> byEmail = new HashMap<>();
        when(repo.findByEmailAddress(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(byEmail.get(invocation.getArgument(0))));
        when(repo.save(any())).thenAnswer(invocation -> {
            UserEntity client = invocation.getArgument(0);
            byEmail.put(client.getEmailAddress(), client);
            return client;
        });
        return repo;
    }

    /**
     * A catalogue that behaves the way JPA does: rows come back from the query
     * with the ids they were given on save, so an appointment can point at one.
     */
    private SubServiceRepo subsWithCatalogue(boolean acceptEveryService) {
        SubServiceRepo subs = mock(SubServiceRepo.class);
        Map<String, List<SubServiceEntity>> byStyler = new HashMap<>();
        when(subs.isServiceExist(anyString(), anyString()))
                .thenReturn(acceptEveryService ? Optional.of(new SubServiceEntity()) : Optional.empty());
        when(subs.save(any())).thenAnswer(invocation -> {
            SubServiceEntity service = invocation.getArgument(0);
            List<SubServiceEntity> rows = byStyler.computeIfAbsent(service.getStylerId(), k -> new ArrayList<>());
            rows.add(service);
            service.setId((long) rows.size());
            return service;
        });
        when(subs.findByStylerId(anyString()))
                .thenAnswer(invocation -> byStyler.getOrDefault(invocation.getArgument(0), List.of()));
        return subs;
    }

    /** A catalogue that is already on file, so nothing needs writing. */
    private SubServiceRepo existingCatalogue(String stylerId, int size) {
        SubServiceRepo subs = mock(SubServiceRepo.class);
        List<SubServiceEntity> rows = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            SubServiceEntity service = new SubServiceEntity();
            service.setStylerId(stylerId);
            service.setId((long) (i + 1));
            service.setName(i == 0 ? "Signature haircut" : "Beard trim");
            service.setPrice(i == 0 ? "55.00" : "25.00");
            service.setDurationMinutes(i == 0 ? 60 : 30);
            rows.add(service);
        }
        when(subs.isServiceExist(anyString(), anyString())).thenReturn(Optional.of(new SubServiceEntity()));
        // General stub first, then the specific row: the later match wins.
        when(subs.findByStylerId(anyString())).thenReturn(List.of());
        when(subs.findByStylerId(stylerId)).thenReturn(rows);
        return subs;
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
        DemoContentInitializer initializer = new DemoContentInitializer(services, stylers, subsAlwaysMissing(),
                availabilityAlwaysEmpty(), appointmentsEchoingSave(), reviewsEchoingSave(), usersEchoingSave());
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
    void fictionalProfilesAreDistinctAndLegacyRefreshPreservesAccountData() {
        DemoContentInitializer initializer = init(mock(ServiceRepo.class), mock(StylerRepo.class), subsAlwaysMissing(), availabilityAlwaysEmpty());
        ServiceEntity type = serviceType(1, "Nail Technician");
        StylerEntity first = initializer.demoStylerFor(type, 1);
        StylerEntity second = initializer.demoStylerFor(type, 2);
        assertNotEquals("Demo", first.getFirstname());
        assertNotEquals(first.getBusinessName(), second.getBusinessName());
        assertTrue(first.getDescription().contains("Fictional demo profile"));
        first.setFirstname("Demo");
        first.setLastname("Nail Technician");
        first.setBusinessName("Demo Nail Technician Studio 1");
        String id = first.getStylerId();
        String email = first.getEmailAddress();
        String password = first.getPassword();
        String address = first.getBusinessAddress();
        assertTrue(initializer.refreshDemoIdentity(first, type));
        assertEquals(id, first.getStylerId());
        assertEquals(email, first.getEmailAddress());
        assertEquals(password, first.getPassword());
        assertEquals(address, first.getBusinessAddress());
        assertFalse(initializer.refreshDemoIdentity(first, type));
        second.setStylerId("REAL123");
        second.setFirstname("Demo");
        second.setBusinessName("Demo custom business");
        assertFalse(initializer.refreshDemoIdentity(second, type));
    }

    @Test
    void eachDemoCategoryHasFiveDistinctStablePhotosAndKeepsUploads() {
        DemoContentInitializer initializer = init(mock(ServiceRepo.class), mock(StylerRepo.class), subsAlwaysMissing(), availabilityAlwaysEmpty());
        for (String category : List.of("Barber", "Eyelash Technician", "Makeup Artist", "Nail Technician", "Hairstylist")) {
            Set<String> photos = new HashSet<>();
            for (int ordinal = 1; ordinal <= 5; ordinal++) {
                StylerEntity demo = initializer.demoStylerFor(serviceType(1, category), ordinal);
                assertNotNull(demo.getProfileImageUrl());
                photos.add(demo.getProfileImageUrl());
                String original = demo.getProfileImageUrl();
                assertFalse(initializer.refreshDemoPhoto(demo, serviceType(1, category)));
                assertEquals(original, demo.getProfileImageUrl());
                demo.setProfileImageUrl(null);
                assertTrue(initializer.refreshDemoPhoto(demo, serviceType(1, category)));
                assertEquals(original, demo.getProfileImageUrl());
                demo.setProfileImageUrl("https://example.com/qa-upload.jpg");
                assertFalse(initializer.refreshDemoPhoto(demo, serviceType(1, category)));
            }
            assertEquals(5, photos.size(), category);
        }
        StylerEntity qa = approvedStyler("1");
        qa.setStylerId("QA123");
        qa.setEmailAddress("qa@example.com");
        assertFalse(initializer.refreshDemoPhoto(qa, serviceType(1, "Barber")));
    }

    @Test
    void supersededSeedSampleCanRefreshWithoutOverwritingAnUpload() {
        DemoContentInitializer initializer = init(mock(ServiceRepo.class), mock(StylerRepo.class), subsAlwaysMissing(), availabilityAlwaysEmpty());
        ServiceEntity type = serviceType(1, "Nail Technician");
        StylerEntity demo = initializer.demoStylerFor(type, 1);
        demo.setProfileImageUrl("/images/gallery/g-nails-1.jpg");
        assertTrue(initializer.refreshDemoPhoto(demo, type));
        assertEquals("/images/demo/nails-1.webp", demo.getProfileImageUrl());
        demo.setProfileImageUrl("https://example.com/upload.jpg");
        assertFalse(initializer.refreshDemoPhoto(demo, type));
        assertEquals("https://example.com/upload.jpg", demo.getProfileImageUrl());
    }

    @Test
    void seededSchedulesCoverEveryDayWithBroadVariedHoursAndPreserveCustomShifts() {
        AvailabilityRepo availability = mock(AvailabilityRepo.class);
        List<AvailabilityEntity> stored = new ArrayList<>();
        AvailabilityEntity custom = new AvailabilityEntity();
        custom.setStylerId("DEMO1234"); custom.setDayOfWeek("1");
        custom.setStartTime("11:00"); custom.setEndTime("21:00"); stored.add(custom);
        AvailabilityEntity legacy = new AvailabilityEntity();
        legacy.setStylerId("DEMO1234"); legacy.setDayOfWeek("2");
        legacy.setStartTime("10:00"); legacy.setEndTime("17:00"); stored.add(legacy);
        when(availability.findByStylerId("DEMO1234")).thenReturn(stored);
        when(availability.saveAll(any())).thenAnswer(invocation -> {
            List<AvailabilityEntity> rows = invocation.getArgument(0);
            for (AvailabilityEntity row : rows) if (!stored.contains(row)) stored.add(row);
            return rows;
        });
        DemoContentInitializer initializer = init(mock(ServiceRepo.class), mock(StylerRepo.class), subsAlwaysMissing(), availability);
        StylerEntity demo = initializer.demoStylerFor(serviceType(1, "Nail Technician"), 1);
        demo.setStylerId("DEMO1234");
        assertEquals(6, initializer.seedAvailability(demo));
        assertEquals(7, stored.size());
        assertEquals(Set.of("0", "1", "2", "3", "4", "5", "6"), stored.stream().map(AvailabilityEntity::getDayOfWeek).collect(java.util.stream.Collectors.toSet()));
        assertEquals("11:00", custom.getStartTime()); assertEquals("21:00", custom.getEndTime());
        assertTrue(stored.stream().allMatch(row -> java.time.Duration.between(java.time.LocalTime.parse(row.getStartTime()), java.time.LocalTime.parse(row.getEndTime())).toHours() >= 8));
        assertTrue(stored.stream().map(AvailabilityEntity::getStartTime).distinct().count() > 1);
        assertEquals(0, initializer.seedAvailability(demo));
        StylerEntity qa = approvedStyler("1"); qa.setStylerId("DS1234");
        assertEquals(0, initializer.seedAvailability(qa));
    }

    @Test
    void makeupSeedUsesMakeupServicesInsteadOfHairServices() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(5, "Makeup Artist")));
        when(stylers.findByServiceTypeId("5")).thenReturn(List.of());
        SubServiceRepo subs = subsAlwaysMissing();
        init(services, stylers, subs, availabilityAlwaysEmpty()).run();
        ArgumentCaptor<SubServiceEntity> saved = ArgumentCaptor.forClass(SubServiceEntity.class);
        verify(subs, times(25)).save(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(row -> row.getName().toLowerCase().contains("makeup")));
    }

    @Test
    void everySeededProfileFitsTheDatabaseColumns() {
        DemoContentInitializer initializer = init(mock(ServiceRepo.class), mock(StylerRepo.class), subsAlwaysMissing(), availabilityAlwaysEmpty());
        for (String category : List.of("Barber", "Eyelash Technician", "Makeup Artist", "Nail Technician", "Hairstylist")) {
            for (long type = 1; type <= 5; type++) {
                for (int ordinal = 1; ordinal <= 5; ordinal++) {
                    StylerEntity demo = initializer.demoStylerFor(serviceType(type, category), ordinal);
                    // Every one of these columns is varchar(255) in the live schema.
                    for (String value : List.of(demo.getDescription(), demo.getBusinessName(), demo.getBusinessAddress(),
                            demo.getEmailAddress(), demo.getProfileImageUrl())) {
                        assertTrue(value.length() <= 255, value.length() + ": " + value);
                    }
                }
            }
        }
    }

    @Test
    void duplicateSeededProfilesInACategoryAreSplitOntoTheirOwnSeats() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        ServiceEntity barber = serviceType(3, "Barber");
        when(services.findAll()).thenReturn(List.of(barber));
        DemoContentInitializer seeder = init(services, stylers, subsAlwaysMissing(), availabilityAlwaysEmpty());
        // What the live demo held: two rows both seeded as seat 5 ("Naomi").
        List<StylerEntity> rows = new ArrayList<>();
        int[] claimed = {5, 5, 4, 2, 3};
        String[] ids = {"DEMO1495", "DEMO2103", "DEMO1846", "DEMO5009", "DEMO5874"};
        for (int i = 0; i < ids.length; i++) {
            StylerEntity row = seeder.demoStylerFor(barber, claimed[i]);
            row.setStylerId(ids[i]);
            rows.add(row);
        }
        StylerEntity operatorEdited = rows.get(2);
        operatorEdited.setBusinessName("Fresh Cuts YYC");
        when(stylers.findByServiceTypeId("3")).thenReturn(rows);

        seeder.run();

        Set<String> names = new HashSet<>();
        Set<String> photos = new HashSet<>();
        Set<String> addresses = new HashSet<>();
        for (StylerEntity row : rows) {
            names.add(row.getFirstname());
            photos.add(row.getProfileImageUrl());
            addresses.add(row.getBusinessAddress());
        }
        assertEquals(5, names.size(), names.toString());
        assertEquals(5, photos.size(), photos.toString());
        assertEquals(5, addresses.size(), addresses.toString());
        // The earlier row by id keeps its seat; only the clash moves.
        assertEquals("Aspen Barber by Andre", rows.get(0).getBusinessName());
        assertNotEquals("Aspen Barber by Andre", rows.get(1).getBusinessName());
        assertEquals("Fresh Cuts YYC", operatorEdited.getBusinessName(), "an operator's rename is kept");
    }

    @Test
    void seededReviewsFollowARenamedProfileButRealReviewsDoNot() {
        assertTrue(DemoSeedContent.isSeedReviewText(DemoSeedContent.reviewMessage("barber", 5, 3, "Naomi")));
        assertTrue(DemoSeedContent.isSeedReviewText(DemoSeedContent.reviewMessage("hair", 4, 1, null)));
        assertFalse(DemoSeedContent.isSeedReviewText("Loved my cut, will be back next month!"));
        ReviewRepo reviews = mock(ReviewRepo.class);
        ReviewEntity seeded = new ReviewEntity();
        seeded.setBookingId("DEMOB2103-0");
        seeded.setRatingScore(5);
        String key = "DEMO2103";
        int seed = Math.floorMod(key.hashCode(), 10_000);
        seeded.setMessage(DemoSeedContent.reviewMessage("barber", 5, seed, "Naomi"));
        when(reviews.findByStylerId(key)).thenReturn(List.of(seeded));
        DemoContentInitializer seeder = init(mock(ServiceRepo.class), mock(StylerRepo.class), subsAlwaysMissing(),
                availabilityAlwaysEmpty(), appointmentsEchoingSave(), reviews, usersEchoingSave());
        StylerEntity renamed = approvedStyler("3");
        renamed.setStylerId(key);
        renamed.setFirstname("Andre");
        seeder.refreshSeededReviews(renamed, serviceType(3, "Barber"));
        assertEquals(DemoSeedContent.reviewMessage("barber", 5, seed, "Andre"), seeded.getMessage());
        assertFalse(seeded.getMessage().contains("Naomi"));
    }

    @Test
    void aHandRegisteredDemoProfileIsDressedButKeepsItsAccount() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        ServiceEntity nails = serviceType(1, "Nail Technician");
        when(services.findAll()).thenReturn(List.of(nails));
        AvailabilityRepo availability = availabilityAlwaysEmpty();
        BookAppointmentRepo appointments = appointmentsEchoingSave();
        DemoContentInitializer seeder = init(services, stylers, subsAlwaysMissing(), availability,
                appointments, reviewsEchoingSave(), usersEchoingSave());
        List<StylerEntity> rows = new ArrayList<>();
        int[] seats = {2, 3, 4, 5};
        for (int i = 0; i < seats.length; i++) {
            StylerEntity row = seeder.demoStylerFor(nails, seats[i]);
            row.setStylerId("DEMO900" + i);
            rows.add(row);
        }
        StylerEntity qa = approvedStyler("1");
        qa.setStylerId("DS5713");
        qa.setFirstname("Demo");
        qa.setLastname("Account");
        qa.setBusinessName("Demo Beauty Co");
        qa.setEmailAddress("qa.pro@example.com");
        qa.setPassword("$2a$10$realhash");
        qa.setBusinessAddress("700 2 St SW");
        qa.setStripeConnectAccountId("acct_test123");
        rows.add(qa);
        when(stylers.findByServiceTypeId("1")).thenReturn(rows);

        seeder.run();

        assertEquals("Willow Nail Technician by Nia", qa.getBusinessName(), "takes the one free seat");
        assertEquals("Nia", qa.getFirstname());
        assertNotNull(qa.getProfileImageUrl());
        assertEquals("America/Edmonton", qa.getTimeZone());
        // The account itself is untouched.
        assertEquals("qa.pro@example.com", qa.getEmailAddress());
        assertEquals("$2a$10$realhash", qa.getPassword());
        assertEquals("700 2 St SW", qa.getBusinessAddress());
        assertEquals("acct_test123", qa.getStripeConnectAccountId());
        // Open all week, and no invented history on a real login.
        verify(availability, times(5)).saveAll(any());
        verify(appointments, never()).save(org.mockito.ArgumentMatchers.argThat(a -> "DS5713".equals(a.getStylerId())));
        // Already dressed: a second run leaves it alone.
        assertFalse(seeder.refreshDemoIdentity(qa, nails, 1));
    }

    @Test
    void aRefreshThatChangesRowsDropsTheCachedProfilesAndSearchLists() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(4, "Hairstylist")));
        when(stylers.findByServiceTypeId("4")).thenReturn(List.of());
        DemoContentInitializer seeder = init(services, stylers, subsAlwaysMissing(), availabilityAlwaysEmpty());
        com.macrotel.rapidstylers.common.service.ReadCacheService cache =
                mock(com.macrotel.rapidstylers.common.service.ReadCacheService.class);
        seeder.setReadCacheService(cache);

        seeder.run();

        verify(cache).evictPrefix("styler:");
        verify(cache).evictPrefix("search:");
    }

    @Test
    void namesFollowTheCategoryNotTheDatabaseId() {
        DemoContentInitializer seeder = init(mock(ServiceRepo.class), mock(StylerRepo.class), subsAlwaysMissing(), availabilityAlwaysEmpty());
        Set<String> barberNames = new HashSet<>();
        for (int ordinal = 1; ordinal <= 5; ordinal++) {
            barberNames.add(seeder.demoStylerFor(serviceType(9, "Barber"), ordinal).getFirstname());
            assertEquals(seeder.demoStylerFor(serviceType(3, "Barber"), ordinal).getFirstname(),
                    seeder.demoStylerFor(serviceType(9, "Barber"), ordinal).getFirstname());
        }
        assertEquals(Set.of("Marcus", "Daniel", "Kwame", "Ethan", "Andre"), barberNames);
    }

    @Test
    void locationsAreDistinctAndSpreadAcrossTheCity() {
        Set<String> addresses = new HashSet<>();
        for (DemoSeedContent.DemoLocation location : DemoSeedContent.LOCATIONS) {
            assertTrue(addresses.add(location.address()));
            // Inside Calgary's city limits.
            assertTrue(location.latitude() > 50.84 && location.latitude() < 51.21, location.address());
            assertTrue(location.longitude() > -114.32 && location.longitude() < -113.86, location.address());
        }
        assertEquals(25, addresses.size());
        DemoContentInitializer initializer = init(mock(ServiceRepo.class), mock(StylerRepo.class), subsAlwaysMissing(), availabilityAlwaysEmpty());
        for (long type = 1; type <= 5; type++) {
            Set<String> quadrants = new HashSet<>();
            for (int ordinal = 1; ordinal <= 5; ordinal++) {
                String address = initializer.demoStylerFor(serviceType(type, "Barber"), ordinal).getBusinessAddress();
                quadrants.add(address.substring(address.lastIndexOf(' ') + 1));
            }
            // Each category reaches at least NW, NE, SW and SE.
            assertTrue(quadrants.containsAll(Set.of("NW", "NE", "SW", "SE")), "type " + type + " " + quadrants);
        }
    }

    @Test
    void legacyDowntownPinMovesToItsOwnAddressButCustomAddressesStay() {
        DemoContentInitializer initializer = init(mock(ServiceRepo.class), mock(StylerRepo.class), subsAlwaysMissing(), availabilityAlwaysEmpty());
        ServiceEntity type = serviceType(2, "Eyelash Technician");
        StylerEntity legacy = initializer.demoStylerFor(type, 3);
        legacy.setBusinessAddress("Suite 210, 102 8 Ave SW");
        legacy.setLatitude(51.0447);
        legacy.setLongitude(-114.0719);
        legacy.setTimeZone(null);
        assertTrue(initializer.refreshDemoLocation(legacy, type));
        assertEquals(DemoContentInitializer.locationFor(type, 3).address(), legacy.getBusinessAddress());
        assertNotEquals(51.0447, legacy.getLatitude());
        assertEquals("America/Edmonton", legacy.getTimeZone());
        assertFalse(initializer.refreshDemoLocation(legacy, type), "a second run changes nothing");

        legacy.setBusinessAddress("Suite 300, 1 Operator Edit St SW");
        assertFalse(initializer.refreshDemoLocation(legacy, type));
        assertEquals("Suite 300, 1 Operator Edit St SW", legacy.getBusinessAddress());

        StylerEntity qa = approvedStyler("2");
        qa.setStylerId("DS1234");
        qa.setBusinessAddress("Suite 210, 102 8 Ave SW");
        assertFalse(initializer.refreshDemoLocation(qa, type));
    }

    @Test
    void legacyMakeupHairServicesAreRenamedInPlaceSoBookingsStayValid() {
        SubServiceRepo subs = mock(SubServiceRepo.class);
        List<SubServiceEntity> rows = new ArrayList<>();
        for (String name : List.of("Silk press", "Blowout", "Day makeup", "My custom look")) {
            SubServiceEntity row = new SubServiceEntity();
            row.setId((long) rows.size() + 1);
            row.setStylerId("DEMO5555");
            row.setName(name);
            rows.add(row);
        }
        when(subs.findByStylerId("DEMO5555")).thenReturn(rows);
        DemoContentInitializer initializer = init(mock(ServiceRepo.class), mock(StylerRepo.class), subs, availabilityAlwaysEmpty());
        StylerEntity artist = approvedStyler("5");
        artist.setStylerId("DEMO5555");

        // Silk press would become Day makeup, which exists, so it is skipped; Blowout becomes Bridal makeup.
        assertEquals(1, initializer.retargetMakeupCatalogue(artist, serviceType(5, "Makeup Artist")));
        assertEquals("Silk press", rows.get(0).getName());
        assertEquals("Bridal makeup", rows.get(1).getName());
        assertEquals(2L, rows.get(1).getId());
        assertEquals("My custom look", rows.get(3).getName());
        assertEquals(0, initializer.retargetMakeupCatalogue(artist, serviceType(1, "Nail Technician")));
    }

    @Test
    void oldSeededReviewTextIsRewrittenWithoutTouchingScoresOrRealReviews() {
        ReviewRepo reviews = mock(ReviewRepo.class);
        ReviewEntity seeded = new ReviewEntity();
        seeded.setBookingId("DEMOB4321-3");
        seeded.setRatingScore(4);
        seeded.setCreatedAt("01 02, 2026 20:05:00");
        seeded.setMessage("Really happy with the result. Not a five only because parking downtown cost me fifteen minutes.");
        ReviewEntity real = new ReviewEntity();
        real.setBookingId("BK100");
        real.setRatingScore(5);
        real.setMessage("Booked late and still got her full attention. Nothing felt rushed and the finish held up for weeks.");
        when(reviews.findByStylerId("DEMO4321")).thenReturn(List.of(seeded, real));
        DemoContentInitializer initializer = init(mock(ServiceRepo.class), mock(StylerRepo.class), subsAlwaysMissing(),
                availabilityAlwaysEmpty(), appointmentsEchoingSave(), reviews, usersEchoingSave());
        StylerEntity barber = approvedStyler("3");
        barber.setStylerId("DEMO4321");
        barber.setFirstname("Marcus");

        assertEquals(1, initializer.refreshSeededReviews(barber, serviceType(3, "Barber")));
        assertFalse(DemoSeedContent.LEGACY_REVIEW_MESSAGES.contains(seeded.getMessage()));
        assertFalse(seeded.getMessage().contains("downtown"));
        assertEquals(4, seeded.getRatingScore());
        assertEquals("01 02, 2026 20:05:00", seeded.getCreatedAt());
        assertTrue(real.getMessage().contains("her full attention"), "a real review is never rewritten");
        assertEquals(0, initializer.refreshSeededReviews(barber, serviceType(3, "Barber")));
    }

    @Test
    void reviewTextIsGenderNeutralNamesTheProfessionalAndFitsTheColumn() {
        for (String category : List.of("nail", "lash", "barber", "makeup", "hair")) {
            for (int rating = 3; rating <= 5; rating++) {
                for (int key = 0; key < 40; key++) {
                    String named = DemoSeedContent.reviewMessage(category, rating, key, "Kwame");
                    String unnamed = DemoSeedContent.reviewMessage(category, rating, key, null);
                    for (String text : List.of(named, unnamed)) {
                        String padded = " " + text.toLowerCase() + " ";
                        assertFalse(padded.matches(".*\\b(she|her|he|him|his)\\b.*"), text);
                        assertFalse(text.contains("%s"), text);
                        assertFalse(text.contains("—"), text);
                        assertTrue(text.length() <= 255, text);
                    }
                }
            }
        }
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
        assertTrue(names.contains("Clover Nail Technician by Amara"));
        assertTrue(names.contains("Aspen Nail Technician by Jade"));
        assertFalse(names.contains("Willow Nail Technician by Nia"));
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
        assertTrue(savedSlots.getAllValues().stream().allMatch(slots -> slots.size() == 7));
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
        real.setStylerId("DS4242");
        when(stylers.findByServiceTypeId("1")).thenReturn(List.of(legacy, real, approvedStyler("1"), approvedStyler("1"), approvedStyler("1")));
        SubServiceRepo subs = subsAlwaysMissing();
        AvailabilityRepo availability = availabilityAlwaysEmpty();

        init(services, stylers, subs, availability).run();

        // The legacy demo row is backfilled (2 services + a 7-day week)...
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
        real.setStylerId("DS4242");
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
        Set<String> addresses = new HashSet<>();
        for (StylerEntity demo : saved.getAllValues()) {
            assertTrue(demo.getBusinessAddress() != null && !demo.getBusinessAddress().isEmpty());
            assertTrue(addresses.add(demo.getBusinessAddress()), "each professional needs their own address");
            DemoSeedContent.DemoLocation location = java.util.Arrays.stream(DemoSeedContent.LOCATIONS)
                    .filter(l -> l.address().equals(demo.getBusinessAddress())).findFirst().orElseThrow();
            // The map pin matches the street address, not one shared downtown point.
            assertEquals(location.latitude(), demo.getLatitude());
            assertEquals(location.longitude(), demo.getLongitude());
            assertEquals("America/Edmonton", demo.getTimeZone());
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

    /* ── seeded work history ───────────────────────────────────────────────── */

    @Test
    void everySeededStylistGetsFinishedAppointmentsAndApprovedReviews() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(3, "Barber")));
        when(stylers.findByServiceTypeId("3")).thenReturn(List.of());
        SubServiceRepo subs = subsWithCatalogue(false);
        BookAppointmentRepo appointments = appointmentsEchoingSave();
        ReviewRepo reviews = reviewsEchoingSave();

        init(services, stylers, subs, availabilityAlwaysEmpty(), appointments, reviews, usersEchoingSave()).run();

        ArgumentCaptor<BookAppointmentEntity> savedWork = ArgumentCaptor.forClass(BookAppointmentEntity.class);
        verify(appointments, org.mockito.Mockito.atLeastOnce()).save(savedWork.capture());
        ArgumentCaptor<ReviewEntity> savedReviews = ArgumentCaptor.forClass(ReviewEntity.class);
        verify(reviews, org.mockito.Mockito.atLeastOnce()).save(savedReviews.capture());

        // Five stylists, each with a believable run of finished work.
        Map<String, Long> perStylist = savedWork.getAllValues().stream()
                .collect(java.util.stream.Collectors.groupingBy(BookAppointmentEntity::getStylerId, java.util.stream.Collectors.counting()));
        assertEquals(5, perStylist.size());
        assertTrue(perStylist.values().stream().allMatch(n -> n >= 4 && n <= 16),
                "each stylist should carry 4-16 finished appointments, got " + perStylist.values());

        Set<String> bookingIds = new HashSet<>();
        for (BookAppointmentEntity appointment : savedWork.getAllValues()) {
            assertEquals("0", appointment.getStatus(), "history must be completed work (status 0)");
            assertNotNull(appointment.getAppointmentId());
            assertNotNull(appointment.getUserId());
            assertNotNull(appointment.getSubServiceId(), "a finish must reference a real service");
            assertNotNull(appointment.getServicePrice());
            assertNotNull(appointment.getDurationMinutes());
            assertNotNull(appointment.getCompletedAt());
            bookingIds.add(appointment.getAppointmentId());
        }
        assertEquals(savedWork.getAllValues().size(), bookingIds.size(), "appointment ids must be unique");

        // Every review is public, scored, and hangs off one of those appointments.
        Set<String> reviewedBookings = new HashSet<>();
        for (ReviewEntity review : savedReviews.getAllValues()) {
            assertEquals("APPROVED", review.getModerationStatus(), "only approved reviews are public");
            assertTrue(review.getRatingScore() >= 1 && review.getRatingScore() <= 5);
            assertTrue(bookingIds.contains(review.getBookingId()),
                    "a review must reference a completed booking from this history");
            assertTrue(reviewedBookings.add(review.getBookingId()), "one review per booking");
            assertNotNull(review.getUserName());
            assertNotNull(review.getCreatedAt());
            assertNotNull(review.getMessage());
            // The column is varchar(255); a longer message would be truncated or rejected.
            assertTrue(review.getMessage().length() <= 255, "review text must fit the column");
            assertFalse(review.getMessage().contains("—"), "house style keeps em dashes out of copy");
        }
        // Some finished work is deliberately left unreviewed, as on a real listing.
        assertTrue(savedReviews.getAllValues().size() < savedWork.getAllValues().size());
    }

    @Test
    void theHistoryNeverOccupiesAFutureSlot() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(3, "Barber")));
        when(stylers.findByServiceTypeId("3")).thenReturn(List.of());
        BookAppointmentRepo appointments = appointmentsEchoingSave();

        init(services, stylers, subsWithCatalogue(false), availabilityAlwaysEmpty(),
                appointments, reviewsEchoingSave(), usersEchoingSave()).run();

        ArgumentCaptor<BookAppointmentEntity> savedWork = ArgumentCaptor.forClass(BookAppointmentEntity.class);
        verify(appointments, org.mockito.Mockito.atLeastOnce()).save(savedWork.capture());
        for (BookAppointmentEntity appointment : savedWork.getAllValues()) {
            assertTrue(appointment.getAppointmentDateValue().isBefore(LocalDate.now()),
                    "seeded history is past work, so it must never block a bookable slot");
        }
    }

    @Test
    void ratingsReadLikeReviewsRatherThanMarketing() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(3, "Barber")));
        when(stylers.findByServiceTypeId("3")).thenReturn(List.of());
        ReviewRepo reviews = reviewsEchoingSave();

        init(services, stylers, subsWithCatalogue(false), availabilityAlwaysEmpty(),
                appointmentsEchoingSave(), reviews, usersEchoingSave()).run();

        ArgumentCaptor<ReviewEntity> savedReviews = ArgumentCaptor.forClass(ReviewEntity.class);
        verify(reviews, org.mockito.Mockito.atLeastOnce()).save(savedReviews.capture());
        Map<String, List<ReviewEntity>> perStylist = savedReviews.getAllValues().stream()
                .collect(java.util.stream.Collectors.groupingBy(ReviewEntity::getStylerId));

        List<Double> averages = new ArrayList<>();
        for (Map.Entry<String, List<ReviewEntity>> entry : perStylist.entrySet()) {
            List<ReviewEntity> rows = entry.getValue();
            double average = rows.stream().mapToInt(ReviewEntity::getRatingScore).average().orElse(0);
            averages.add(average);
            // Nobody in the showroom reads as discredited, and nobody reads as flawless.
            assertTrue(average >= 4.0,
                    entry.getKey() + " should still look worth booking, got " + average);
            if (rows.size() >= 6) {
                assertTrue(rows.stream().anyMatch(r -> r.getRatingScore() < 5),
                        entry.getKey() + " must not read as a perfect 5.0 wall");
            }
            // A run of reviews that is all one score reads as seeded; the mix is the point.
            long distinct = rows.stream().map(ReviewEntity::getRatingScore).distinct().count();
            assertTrue(distinct >= 2 || rows.size() < 3, entry.getKey() + " reviews are all the same score");
        }
        // The tiers have to actually differ, or every profile looks copy-pasted.
        assertTrue(averages.stream().mapToDouble(Double::doubleValue).min().orElse(0)
                        < averages.stream().mapToDouble(Double::doubleValue).max().orElse(0),
                "profiles should not all carry the same average: " + averages);
    }

    @Test
    void theScoreMixesLandInTheIntendedBands() {
        DemoContentInitializer initializer = init(mock(ServiceRepo.class), mock(StylerRepo.class),
                subsAlwaysMissing(), availabilityAlwaysEmpty());

        for (int seed = 0; seed < 400; seed++) {
            for (int count = 2; count <= 16; count++) {
                int[] scores = initializer.demoScores(seed, count);
                assertEquals(count, scores.length);
                double average = java.util.Arrays.stream(scores).average().orElse(0);
                assertTrue(average >= 4.0,
                        "seed " + seed + " with " + count + " reviews averaged " + average);
                for (int score : scores) {
                    assertTrue(score >= 3 && score <= 5, "scores stay in the believable band, got " + score);
                }
            }
        }
    }

    @Test
    void aLongRunAlwaysContainsSomeLowerMarksAndSomePerfectOnes() {
        DemoContentInitializer initializer = init(mock(ServiceRepo.class), mock(StylerRepo.class),
                subsAlwaysMissing(), availabilityAlwaysEmpty());

        for (int seed = 0; seed < 200; seed++) {
            int[] scores = initializer.demoScores(seed, 14);
            assertTrue(java.util.Arrays.stream(scores).anyMatch(s -> s == 5), "seed " + seed + " has no five");
            assertTrue(java.util.Arrays.stream(scores).anyMatch(s -> s < 5), "seed " + seed + " has no lower mark");
            assertTrue(java.util.Arrays.stream(scores).anyMatch(s -> s == 3), "seed " + seed + " has no three");
        }
    }

    @Test
    void seededClientsAreInertAndCarryTheReviewerNames() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(3, "Barber")));
        when(stylers.findByServiceTypeId("3")).thenReturn(List.of());
        UserRepo users = usersPersisting();
        ReviewRepo reviews = reviewsEchoingSave();

        init(services, stylers, subsWithCatalogue(false), availabilityAlwaysEmpty(),
                appointmentsEchoingSave(), reviews, users).run();

        ArgumentCaptor<UserEntity> savedClients = ArgumentCaptor.forClass(UserEntity.class);
        verify(users, times(DemoContentInitializer.DEMO_CLIENTS.length)).save(savedClients.capture());
        Set<String> ids = new HashSet<>();
        for (UserEntity client : savedClients.getAllValues()) {
            assertTrue(client.getEmailAddress().endsWith("@" + DemoContentInitializer.DEMO_CLIENT_EMAIL_DOMAIN));
            assertTrue(client.getPassword().startsWith("$2"));
            assertEquals(DemoContentInitializer.UNMATCHABLE_PASSWORD_HASH, client.getPassword());
            // Nothing here can contact a real person.
            assertNull(client.getPhoneNumber());
            assertTrue(ids.add(client.getUserId()), "each client needs its own id");
        }

        // The name on a review is the client's full name, the way a real one is recorded.
        ArgumentCaptor<ReviewEntity> savedReviews = ArgumentCaptor.forClass(ReviewEntity.class);
        verify(reviews, org.mockito.Mockito.atLeastOnce()).save(savedReviews.capture());
        Set<String> clientNames = new HashSet<>();
        for (UserEntity client : savedClients.getAllValues()) {
            clientNames.add((client.getFirstname() + " " + client.getLastname()).trim());
        }
        assertTrue(savedReviews.getAllValues().stream().allMatch(r -> clientNames.contains(r.getUserName())));
    }

    @Test
    void theHistoryIsDeterministicForAGivenStylist() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(3, "Barber")));

        List<ReviewEntity> first = reviewsSeededForLegacyRow(services, stylers);
        List<ReviewEntity> second = reviewsSeededForLegacyRow(services, stylers);

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getRatingScore(), second.get(i).getRatingScore());
            assertEquals(first.get(i).getMessage(), second.get(i).getMessage());
            assertEquals(first.get(i).getCreatedAt(), second.get(i).getCreatedAt());
            assertEquals(first.get(i).getBookingId(), second.get(i).getBookingId());
        }
    }

    /** Runs the seeder over one legacy DEMO row and returns the reviews it wrote. */
    private List<ReviewEntity> reviewsSeededForLegacyRow(ServiceRepo services, StylerRepo stylers) {
        StylerEntity legacy = approvedStyler("3");
        legacy.setStylerId("DEMO7777");
        when(stylers.findByServiceTypeId("3")).thenReturn(List.of(legacy,
                approvedStyler("3"), approvedStyler("3"), approvedStyler("3"), approvedStyler("3")));
        ReviewRepo reviews = reviewsEchoingSave();

        init(services, stylers, existingCatalogue("DEMO7777", 2), availabilityAlwaysEmpty(),
                appointmentsEchoingSave(), reviews, usersEchoingSave()).run();

        ArgumentCaptor<ReviewEntity> saved = ArgumentCaptor.forClass(ReviewEntity.class);
        verify(reviews, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getAllValues();
    }

    @Test
    void aStylistThatAlreadyHasHistoryIsLeftAlone() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(3, "Barber")));
        StylerEntity legacy = approvedStyler("3");
        legacy.setStylerId("DEMO7777");
        when(stylers.findByServiceTypeId("3")).thenReturn(List.of(legacy,
                approvedStyler("3"), approvedStyler("3"), approvedStyler("3"), approvedStyler("3")));
        BookAppointmentRepo appointments = mock(BookAppointmentRepo.class);
        when(appointments.findByStylerId("DEMO7777")).thenReturn(List.of(new BookAppointmentEntity()));
        ReviewRepo reviews = reviewsEchoingSave();

        init(services, stylers, existingCatalogue("DEMO7777", 2), availabilityAlwaysEmpty(),
                appointments, reviews, usersEchoingSave()).run();

        // Real (or already seeded) work is never added to, so a reputation cannot
        // be rewritten by a restart.
        verify(appointments, never()).save(any());
        verify(reviews, never()).save(any());
    }

    @Test
    void aRealProfessionalsHistoryIsNeverSeeded() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(3, "Barber")));
        StylerEntity real = approvedStyler("3");
        real.setStylerId("DS4242");
        when(stylers.findByServiceTypeId("3")).thenReturn(List.of(real,
                approvedStyler("3"), approvedStyler("3"), approvedStyler("3"), approvedStyler("3")));
        BookAppointmentRepo appointments = appointmentsEchoingSave();
        ReviewRepo reviews = reviewsEchoingSave();

        init(services, stylers, existingCatalogue("DS4242", 2), availabilityAlwaysEmpty(),
                appointments, reviews, usersEchoingSave()).run();

        verify(appointments, never()).save(any());
        verify(reviews, never()).save(any());
    }

    @Test
    void aStylistWithNothingToBookGetsNoHistory() {
        ServiceRepo services = mock(ServiceRepo.class);
        StylerRepo stylers = mock(StylerRepo.class);
        when(services.findAll()).thenReturn(List.of(serviceType(3, "Barber")));
        when(stylers.findByServiceTypeId("3")).thenReturn(List.of());
        BookAppointmentRepo appointments = appointmentsEchoingSave();
        ReviewRepo reviews = reviewsEchoingSave();

        // No catalogue: an appointment would reference a service that does not exist.
        init(services, stylers, subsAlwaysMissing(), availabilityAlwaysEmpty(),
                appointments, reviews, usersEchoingSave()).run();

        verify(appointments, never()).save(any());
        verify(reviews, never()).save(any());
    }

    @Test
    void idsAreUniqueAcrossCalls() {
        DemoContentInitializer initializer = init(mock(ServiceRepo.class), mock(StylerRepo.class),
                subsAlwaysMissing(), availabilityAlwaysEmpty());
        StylerEntity a = initializer.demoStylerFor(serviceType(1, "Nail Technician"));
        StylerEntity b = initializer.demoStylerFor(serviceType(1, "Nail Technician"));
        assertNotEquals(a.getStylerId(), b.getStylerId());
    }
}
