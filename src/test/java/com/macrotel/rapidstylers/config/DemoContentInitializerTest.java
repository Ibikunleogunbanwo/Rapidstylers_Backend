package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.entity.AvailabilityEntity;
import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.entity.ReviewEntity;
import com.macrotel.rapidstylers.entity.ServiceEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.SubServiceEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.ReviewRepo;
import com.macrotel.rapidstylers.repo.ServiceRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        real.setStylerId("DS5713");
        when(stylers.findByServiceTypeId("3")).thenReturn(List.of(real,
                approvedStyler("3"), approvedStyler("3"), approvedStyler("3"), approvedStyler("3")));
        BookAppointmentRepo appointments = appointmentsEchoingSave();
        ReviewRepo reviews = reviewsEchoingSave();

        init(services, stylers, existingCatalogue("DS5713", 2), availabilityAlwaysEmpty(),
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
