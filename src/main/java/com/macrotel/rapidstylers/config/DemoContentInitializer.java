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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The public showroom: guarantees every service type carries a floor of
 * {@link #SHOWROOM_PER_TYPE} APPROVED demo stylists at boot, so the Discover
 * professionals section reads as a populated marketplace rather than an empty
 * catalogue. Each seeded stylist also gets priced services and weekly
 * availability, so their public profile shows a real bookable funnel — cards,
 * profile, "Book service", the date/time modal — instead of stopping at
 * "No services available yet".
 *
 * The five public categories are fetched from the database, not hardcoded — a
 * service type added later is seeded automatically, and one that already has
 * the floor of approved stylists is left alone.
 *
 * Each seeded stylist also gets a believable working history: a run of past
 * completed appointments and the approved reviews that came out of them, so
 * their profile shows a real rating and a real appointment tally instead of
 * zeroes everywhere. The history is shaped by the platform's own rules rather
 * than written around them — every review references a completed appointment
 * owned by a seeded client, which is the only kind of review this app accepts
 * ({@code createStylerReview}). A profile showing "4.6 from 11 reviews" is then
 * backed by 11 rows a reviewer could open one by one.
 *
 * What that does NOT do is invent money. The seeded appointments carry no
 * Stripe payment state, no captured amount, no fee split and no transfer, so no
 * payout, refund or reconciliation surface sees them. The one figure derived
 * from them is the per-stylist revenue on the business summary, which sums the
 * price of completed appointments by design; it is consistent with the rows it
 * is computed from, and it exists only while APP_DEMO_SEED is on.
 *
 * Gated behind APP_DEMO_SEED=true so production operators opt in explicitly;
 * default is off. Seeded rows are inert by construction:
 *  - email under demo-stylist.rapidstylers.ca, password an unmatchable
 *    bcrypt hash (no login path);
 *  - verificationStatus APPROVED, isOnline "0" (the app's "Online" flag) and
 *    Connect onboarding COMPLETE: they mimic fully finished profiles, which is
 *    what public search now shows (approved + bookable) — a customer is never
 *    shown a professional they cannot actually book;
 *  - no Stripe Connect ACCOUNT id, so nothing payment-real exists behind the
 *    COMPLETE marker — booking attempts fail at payment setup, which is
 *    acceptable for inert demo rows on empty environments;
 *  - no phone number, so nothing contacts a real person. Each row does carry a
 *    demo street address (invented suites on real Calgary commercial blocks),
 *    because a "visit the stylist" booking is useless without a destination and
 *    these rows would otherwise show an empty address on the profile and the
 *    customer's dashboard.
 *
 * Idempotent: the per-type count includes every approved stylist, seeded or
 * real, so a restart tops up only what is missing and never fights live data.
 */
@Component
@Order(2)
public class DemoContentInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoContentInitializer.class);

    /** Approved stylists per service type after seeding — enough for a full homepage grid row. */
    static final int SHOWROOM_PER_TYPE = 5;

    /** Unmatchable bcrypt digest of a discarded random string — no password verifies against it. */
    static final String UNMATCHABLE_PASSWORD_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5B0C1V3m6GkQj0F0S8dEmVn2rKzQO";

    static final String DEMO_EMAIL_DOMAIN = "demo-stylist.rapidstylers.ca";

    /** Where the seeded clients' accounts live. Same inert treatment as the stylists. */
    static final String DEMO_CLIENT_EMAIL_DOMAIN = "demo-client.rapidstylers.ca";

    /**
     * The clients behind the sample reviews. Modelled on real accounts because
     * the data requires it: a review is only valid against a completed booking
     * that belongs to an existing customer, and the name shown on it is that
     * customer's name. They can never sign in — unmatchable password hash, demo
     * email domain, no phone number — so they are faces, not accounts.
     */
    static final String[][] DEMO_CLIENTS = {
        {"Adaeze", "Okafor"},
        {"Megan", "Tremblay"},
        {"Priya", "Sandhu"},
        {"Jordan", "Fischer"},
        {"Amara", "Bennett"},
        {"Simran", "Dhaliwal"},
        {"Renee", "MacLeod"},
        {"Tunde", "Adeyemi"}
    };

    /** Appointment start times a working day actually contains. */
    private static final String[] DEMO_START_TIMES = {"09:30", "11:00", "13:30", "15:00", "16:30"};

    /**
     * What a profile's scores are made of, as percentages: five-star share,
     * four-star share, three-star share. Three tiers, so the showroom reads like
     * a market rather than one business copied twenty-five times — some
     * professionals are plainly excellent, most are solid, and a couple have a
     * mixed record that is still worth booking.
     *
     * <p>The lowest tier still averages 4.2, and the three-star share is what
     * stops any of them reading as a wall of fives. Building the run to an exact
     * mix rather than slicing a fixed pattern means a profile with only two
     * reviews cannot accidentally land at 3.0.</p>
     */
    private static final int[][] DEMO_SCORE_MIX = {
        {70, 26, 4},   // top of the market
        {56, 34, 10},  // the middle of it
        {38, 44, 18}   // mixed record, still worth booking
    };

    /** "MM dd, yyyy HH:mm:ss" — the format every other row in the app is stamped with. */
    private static final DateTimeFormatter DEMO_TIMESTAMP = DateTimeFormatter.ofPattern("MM dd, yyyy HH:mm:ss");

    /**
     * Catalogue of priced services the seeder gives each demo stylist, keyed by
     * recognisable words in the service type's name so an admin-added type
     * still gets a sensible catalogue. Prices are demo-realistic CAD; durations
     * in minutes. Every stylist gets the full list of two or three services,
     * which is what their public profile's Services section renders.
     */
    private static final List<String[]> DEMO_SERVICES = List.of(
        new String[]{"Manicure", "35.00", "45"},
        new String[]{"Pedicure", "45.00", "60"},
        new String[]{"Classic lash set", "85.00", "90"},
        new String[]{"Lash refill", "55.00", "60"},
        new String[]{"Skin fade", "40.00", "45"},
        new String[]{"Beard trim", "25.00", "30"},
        new String[]{"Signature haircut", "55.00", "60"},
        new String[]{"Silk press", "85.00", "90"},
        new String[]{"Blowout", "65.00", "60"},
        new String[]{"Day makeup", "75.00", "60"},
        new String[]{"Bridal makeup", "150.00", "120"},
        new String[]{"Soft glam makeup", "95.00", "75"},
        new String[]{"Event makeup", "110.00", "90"},
        new String[]{"Makeup lesson", "90.00", "90"},
        new String[]{"Braids", "120.00", "180"},
        new String[]{"Loc retwist", "80.00", "90"},
        new String[]{"Natural hair styling", "90.00", "90"}
    );

    /**
     * The scores for one profile's reviews: a believable mix, deterministic per
     * stylist, with the lower marks spread through the run instead of bunched at
     * one end of the calendar.
     */
    int[] demoScores(int seed, int count) {
        int bucket = Math.floorMod(seed, 10);
        int[] mix = DEMO_SCORE_MIX[bucket < 3 ? 0 : (bucket < 8 ? 1 : 2)];

        int[] scores = new int[count];
        java.util.Arrays.fill(scores, 5);
        int threes = Math.min(count, Math.round(count * mix[2] / 100f));
        int fours = Math.min(count - threes, Math.round(count * mix[1] / 100f));
        place(scores, 3, threes, seed);
        place(scores, 4, fours, seed);
        return scores;
    }

    /** Drops `howMany` of one score into the run at even intervals, never over a lower score. */
    private void place(int[] scores, int score, int howMany, int seed) {
        int count = scores.length;
        for (int k = 0; k < howMany; k++) {
            int index = Math.floorMod((int) Math.floor(((k + 0.5) * count) / howMany) + seed, count);
            while (scores[index] < 5) {
                index = (index + 1) % count;
            }
            scores[index] = score;
        }
    }

    /** A stylist's catalogue: the words that pick their list, in service-name order. */
    private static final String[][] CATALOGUE_KEYS = {
        {"nail"},
        {"lash"},
        {"barber", "buzz", "fade", "clipper"},
        {"makeup"},
        {"hairstylist", "hair", "braid", "loc", "dreadlock", "cornrow", "natural"}
    };

    private final ServiceRepo serviceRepo;
    private final StylerRepo stylerRepo;
    private final SubServiceRepo subServiceRepo;
    private final AvailabilityRepo availabilityRepo;
    private final BookAppointmentRepo bookAppointmentRepo;
    private final ReviewRepo reviewRepo;
    private final UserRepo userRepo;

    @Value("${app.demo.seed:false}")
    private String demoSeed;

    /** Hand-registered demo-database profiles to dress like the seeded ones (comma-separated ids). */
    @Value("${app.demo.showroom-extra-ids:DS5713}")
    private String showroomExtraIds = "DS5713";

    /** Seeded clients for this run; null until a profile actually needs history. */
    private List<UserEntity> demoClientCache;

    public DemoContentInitializer(ServiceRepo serviceRepo, StylerRepo stylerRepo,
                                  SubServiceRepo subServiceRepo, AvailabilityRepo availabilityRepo,
                                  BookAppointmentRepo bookAppointmentRepo, ReviewRepo reviewRepo,
                                  UserRepo userRepo) {
        this.serviceRepo = serviceRepo;
        this.stylerRepo = stylerRepo;
        this.subServiceRepo = subServiceRepo;
        this.availabilityRepo = availabilityRepo;
        this.bookAppointmentRepo = bookAppointmentRepo;
        this.reviewRepo = reviewRepo;
        this.userRepo = userRepo;
    }

    @Override
    public void run(String... args) {
        if (!"true".equalsIgnoreCase(demoSeed == null ? "" : demoSeed.trim())) {
            return;
        }
        try {
            List<ServiceEntity> serviceTypes = serviceRepo.findAll();
            int createdStylists = 0;
            int createdServices = 0;
            int createdSlots = 0;
            int filledAddresses = 0;
            int seededAppointments = 0;
            int seededReviews = 0;
            int refreshedProfiles = 0;
            int rewrittenReviews = 0;
            for (ServiceEntity serviceType : serviceTypes) {
                String typeId = String.valueOf(serviceType.getId());
                List<StylerEntity> approved = stylerRepo.findByServiceTypeId(typeId).stream()
                        .filter(s -> "APPROVED".equals(s.getVerificationStatus()))
                        .toList();
                // Every seeded row in a category gets its own seat (1-5), so no two
                // sample professionals share a name, photo or address.
                java.util.Map<String, Integer> seats = assignSeats(approved);
                java.util.Deque<Integer> freeSeats = freeSeats(seats);
                // Real professionals in the category stand in for the lowest
                // free seats, so new rows continue the count instead of reusing it.
                for (int skip = approved.size() - seats.size(); skip > 0 && !freeSeats.isEmpty(); skip--) freeSeats.poll();
                int missing = SHOWROOM_PER_TYPE - approved.size();
                for (int i = 0; i < missing; i++) {
                    int seat = freeSeats.isEmpty() ? approved.size() + i + 1 : freeSeats.poll();
                    StylerEntity demo = stylerRepo.save(demoStylerFor(serviceType, seat));
                    createdStylists++;
                    createdServices += seedCatalogue(demo, serviceType);
                    createdSlots += seedAvailability(demo);
                    History history = seedHistory(demo, serviceType);
                    seededAppointments += history.appointments();
                    seededReviews += history.reviews();
                }
                // Stylists seeded by the earlier one-per-type version predate the
                // catalogue feature and would show "No services available yet"
                // forever. Backfill them — DEMO-prefixed rows only, so a real
                // professional's profile is never touched by this seeder.
                for (StylerEntity legacy : approved) {
                    if (legacy.getStylerId() != null && legacy.getStylerId().startsWith("DEMO")) {
                        Integer seat = seats.get(legacy.getStylerId());
                        int ordinal = seat == null ? legacyOrdinal(legacy) : seat;
                        boolean identityChanged = refreshDemoIdentity(legacy, serviceType, ordinal);
                        boolean photoChanged = refreshDemoPhoto(legacy, serviceType, ordinal);
                        boolean locationChanged = refreshDemoLocation(legacy, serviceType, ordinal);
                        if (identityChanged || photoChanged || locationChanged) {
                            stylerRepo.save(legacy);
                            refreshedProfiles++;
                        }
                        // A makeup artist seeded before makeup had its own menu was
                        // given hair services; rename those in place so existing
                        // bookings keep pointing at a valid service.
                        retargetMakeupCatalogue(legacy, serviceType);
                        createdServices += seedCatalogue(legacy, serviceType);
                        createdSlots += seedAvailability(legacy);
                        // Rows seeded before addresses existed would keep showing
                        // "no address yet" on every booking made against them.
                        filledAddresses += seedAddress(legacy);
                        // Same idea for the history: a sample profile with no
                        // reviews reads as an abandoned listing rather than a
                        // professional anyone has hired.
                        rewrittenReviews += refreshSeededReviews(legacy, serviceType);
                        History history = seedHistory(legacy, serviceType);
                        seededAppointments += history.appointments();
                        seededReviews += history.reviews();
                    } else if (isShowroomPlaceholder(legacy)) {
                        // A hand-registered "Demo ..." profile: dress it like the
                        // others, but no invented history on a real login.
                        Integer seat = seats.get(legacy.getStylerId());
                        int ordinal = seat == null ? legacyOrdinal(legacy) : seat;
                        boolean identityChanged = refreshDemoIdentity(legacy, serviceType, ordinal);
                        boolean photoChanged = refreshDemoPhoto(legacy, serviceType, ordinal);
                        boolean zoneChanged = !"America/Edmonton".equals(legacy.getTimeZone());
                        if (zoneChanged) legacy.setTimeZone("America/Edmonton");
                        if (identityChanged || photoChanged || zoneChanged) {
                            stylerRepo.save(legacy);
                            refreshedProfiles++;
                        }
                        if (subServiceRepo.findByStylerId(legacy.getStylerId()).isEmpty()) {
                            createdServices += seedCatalogue(legacy, serviceType);
                        }
                        createdSlots += seedAvailability(legacy);
                    }
                }
            }
            // Always logged on an enabled run, so the operator can see it worked.
            log.info("Demo seed refresh done: {} profile(s) updated, {} review(s) reworded, {} availability slot(s) written",
                    refreshedProfiles, rewrittenReviews, createdSlots);
            if (filledAddresses > 0) {
                log.info("Filled in a demo street address for {} sample stylist(s) that had none", filledAddresses);
            }
            if (seededReviews > 0) {
                log.info("Seeded {} past appointment(s) and {} approved review(s) across the sample profiles",
                        seededAppointments, seededReviews);
            }
            if (createdStylists > 0) {
                log.info("Seeded {} demo stylist(s), {} service(s) and {} availability slot(s) to keep every service type at {} approved professionals",
                        createdStylists, createdServices, createdSlots, SHOWROOM_PER_TYPE);
                // Stale search-list caches self-heal on their short TTL; on the
                // wiped environments this seeder targets, the caches are empty
                // anyway. No eviction needed at this layer.
            }
        } catch (Exception ex) {
            // A seeding failure must never block application boot.
            log.warn("Demo content seeding skipped: {}", ex.getMessage());
        }
    }

    StylerEntity demoStylerFor(ServiceEntity serviceType, int ordinal) {
        String name = serviceType.getServiceName() == null ? "Professional" : serviceType.getServiceName();
        StylerEntity demo = new StylerEntity();
        applyDemoIdentity(demo, serviceType, ordinal);
        demo.setEmailAddress("demo." + name.toLowerCase().replaceAll("[^a-z0-9]+", "") + ordinal
                + "@" + DEMO_EMAIL_DOMAIN);
        demo.setProfileImageUrl(demoPhotoFor(serviceType, ordinal));
        demo.setPassword(UNMATCHABLE_PASSWORD_HASH);
        demo.setServiceTypeId(String.valueOf(serviceType.getId()));
        demo.setCountry("Canada");
        demo.setProvince("Alberta");
        // Demo vendors model Calgary businesses; their hours must read in the
        // vendor's zone like real rows do.
        demo.setTimeZone("America/Edmonton");
        demo.setCity("Calgary");
        demo.setInsertedDt(String.valueOf(LocalDate.now()));
        demo.setStatus("0");
        demo.setIsOnline("0"); // the app's "Online" flag (0 = online)
        String stylerId = mintDemoStylerId();
        demo.setStylerId(stylerId);
        // Each profile gets its own spot in the city, so nearby search and
        // travel fees behave like a real spread-out marketplace.
        applyLocation(demo, locationFor(serviceType, ordinal));
        demo.setVerificationStatus("APPROVED");
        // Completed-profile mimicry: public search shows only bookable
        // professionals (approved + Connect COMPLETE when payments are live),
        // so a seeded row must carry both or it would never be visible.
        demo.setConnectOnboardingStatus("COMPLETE");
        demo.setIncludedTravelKm(15.0);
        demo.setBaseTravelFee("0.00");
        return demo;
    }

    private static final String[][] PROFESSIONAL_NAMES = {
        {"Nia", "Campbell"}, {"Priya", "Mehta"}, {"Amara", "Cole"},
        {"Sophie", "Laurent"}, {"Jade", "Williams"}, {"Marcus", "Bennett"},
        {"Daniel", "Reyes"}, {"Kwame", "Mensah"}, {"Ethan", "Brooks"},
        {"Andre", "Clarke"}, {"Zara", "Ahmed"}, {"Chloe", "Martin"},
        {"Imani", "Reed"}, {"Leila", "Hassan"}, {"Naomi", "Chen"},
        {"Aisha", "Okafor"}, {"Maya", "Patel"}, {"Elena", "Rossi"},
        {"Simone", "James"}, {"Tessa", "Nguyen"}, {"Olivia", "Park"},
        {"Gabrielle", "Dubois"}, {"Farah", "Ali"}, {"Keisha", "Morgan"},
        {"Isabelle", "Tran"}
    };
    /** Width of the stylers.description column. */
    static final int DESCRIPTION_MAX = 255;
    private static final String[] STUDIO_NAMES = {"Willow", "Juniper", "Clover", "Sage", "Aspen"};
    private static final String[] APPROACHES = {
        "A relaxed appointment with time to talk through your preferred look and daily routine.",
        "Thoughtful preparation, careful finishing and practical aftercare tips for your next visit.",
        "Personal appointments shaped around your style, with a consultation before getting started.",
        "An unhurried service with attention to detail, comfort and a finish that suits you.",
        "A welcoming space to explore a fresh look or maintain a style you already love."
    };

    private void applyDemoIdentity(StylerEntity styler, ServiceEntity type, int ordinal) {
        String category = type.getServiceName() == null ? "Beauty" : type.getServiceName();
        int slot = Math.floorMod(ordinal - 1, SHOWROOM_PER_TYPE);
        int categoryIndex = Math.floorMod(type.getId() == null ? 0 : type.getId().intValue() - 1, 5);
        String[] name = PROFESSIONAL_NAMES[categoryIndex * SHOWROOM_PER_TYPE + slot];
        styler.setFirstname(name[0]);
        styler.setLastname(name[1]);
        styler.setBusinessName(STUDIO_NAMES[slot] + " " + category + " by " + name[0]);
        // stylers.description is varchar(255); a longer bio fails the whole seed run.
        String bio = "I'm " + name[0] + ", a Calgary " + category.toLowerCase(java.util.Locale.ROOT) + ". "
                + APPROACHES[slot] + " Fictional demo profile, not a real business.";
        styler.setDescription(bio.length() <= DESCRIPTION_MAX ? bio : bio.substring(0, DESCRIPTION_MAX));
    }

    /** Refresh only untouched seed identities, never customer-created accounts. */
    boolean refreshDemoIdentity(StylerEntity styler, ServiceEntity type) {
        return refreshDemoIdentity(styler, type, legacyOrdinal(styler));
    }

    /**
     * Puts a seed-owned profile on the identity for its seat. Only when the
     * current identity is one this seeder wrote (the old "Demo ..." names or a
     * generated "Studio Category by Name"), so an operator's rename is kept.
     */
    boolean refreshDemoIdentity(StylerEntity styler, ServiceEntity type, int ordinal) {
        if (!isShowroomRow(styler) || !hasSeedIdentity(styler)) return false;
        StylerEntity target = new StylerEntity();
        applyDemoIdentity(target, type, ordinal);
        if (java.util.Objects.equals(target.getFirstname(), styler.getFirstname())
                && java.util.Objects.equals(target.getLastname(), styler.getLastname())
                && java.util.Objects.equals(target.getBusinessName(), styler.getBusinessName())
                && java.util.Objects.equals(target.getDescription(), styler.getDescription())) return false;
        applyDemoIdentity(styler, type, ordinal);
        return true;
    }

    private static boolean hasSeedIdentity(StylerEntity styler) {
        String business = styler.getBusinessName();
        if (business == null) return false;
        if ("Demo".equals(styler.getFirstname()) && business.startsWith("Demo ")) return true;
        return studioSeat(business) > 0 && business.contains(" by ");
    }

    /** 1-5 when the business name starts with one of the seeded studio names, else 0. */
    private static int studioSeat(String businessName) {
        if (businessName == null) return 0;
        for (int k = 0; k < STUDIO_NAMES.length; k++) {
            if (businessName.startsWith(STUDIO_NAMES[k] + " ")) return k + 1;
        }
        return 0;
    }

    /** The seat a row claims today: its studio name, the number on an old "Demo ... Studio N" name, or its email. */
    private static int legacyOrdinal(StylerEntity styler) {
        String business = styler.getBusinessName();
        int studio = studioSeat(business);
        if (studio > 0 && business.contains(" by ")) return studio;
        if (business != null && business.startsWith("Demo ")) {
            try { return Integer.parseInt(business.substring(business.lastIndexOf(' ') + 1)); }
            catch (NumberFormatException ignored) { /* fall through to the email */ }
        }
        return seedOrdinal(styler);
    }

    /**
     * Gives each seed-owned row in a category its own seat. A row keeps the seat
     * it already claims when no earlier row (by id) holds it; clashing rows move
     * to the lowest free seat. That is what stops two profiles in one category
     * sharing a name, photo and address.
     */
    java.util.Map<String, Integer> assignSeats(List<StylerEntity> approved) {
        List<StylerEntity> owned = approved.stream()
                .filter(this::isShowroomRow)
                .sorted(java.util.Comparator.comparing(StylerEntity::getStylerId))
                .toList();
        java.util.Map<String, Integer> seats = new java.util.LinkedHashMap<>();
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        List<StylerEntity> clashing = new ArrayList<>();
        for (StylerEntity row : owned) {
            int seat = Math.floorMod(legacyOrdinal(row) - 1, SHOWROOM_PER_TYPE) + 1;
            if (taken.add(seat)) seats.put(row.getStylerId(), seat);
            else clashing.add(row);
        }
        for (StylerEntity row : clashing) {
            int seat = 1;
            while (taken.contains(seat) && seat <= SHOWROOM_PER_TYPE) seat++;
            if (seat > SHOWROOM_PER_TYPE) seat = Math.floorMod(seats.size(), SHOWROOM_PER_TYPE) + 1;
            taken.add(seat);
            seats.put(row.getStylerId(), seat);
        }
        return seats;
    }

    private static java.util.Deque<Integer> freeSeats(java.util.Map<String, Integer> seats) {
        java.util.Deque<Integer> free = new java.util.ArrayDeque<>();
        for (int seat = 1; seat <= SHOWROOM_PER_TYPE; seat++) {
            if (!seats.containsValue(seat)) free.add(seat);
        }
        return free;
    }

    /** Relative static URLs work on both frontend hosts; samples are not uploaded portfolios. */
    private String demoPhotoFor(ServiceEntity type, int ordinal) {
        return demoPhotoFor(type.getServiceName(), ordinal);
    }

    private static String demoPhotoFor(String serviceName, int ordinal) {
        String category = serviceName == null ? "" : serviceName.toLowerCase(java.util.Locale.ROOT);
        String[] photos;
        if (category.contains("nail")) photos = new String[]{"demo/nails-1.webp", "demo/nails-2.webp", "gallery/g-nails-6.jpg", "demo/nails-3.webp", "demo/nails-4.webp"};
        else if (category.contains("lash")) photos = new String[]{"demo/lashes-1.webp", "demo/lashes-2.webp", "demo/lashes-3.webp", "demo/lashes-4.webp", "demo/lashes-5.webp"};
        else if (category.contains("barber")) photos = new String[]{"gallery/g-buzz-cut-1.jpg", "gallery/g-buzz-cut-2.jpg", "gallery/g-buzz-cut-3.jpg", "demo/barber-4.webp", "demo/barber-5.webp"};
        else if (category.contains("makeup")) photos = new String[]{"gallery/g-makeup-1.jpg", "gallery/g-makeup-2.jpg", "gallery/g-makeup-3.jpg", "demo/makeup-4.webp", "demo/makeup-5.webp"};
        else photos = new String[]{"gallery/g-natural-hair-4.jpg", "gallery/g-natural-hair-2.jpg", "gallery/g-natural-hair-3.jpg", "gallery/g-locs-2.jpg", "gallery/g-braids-5.jpg"};
        return "/images/" + photos[Math.floorMod(ordinal - 1, photos.length)];
    }

    /** Every sample photo this seeder hands out, across all categories. */
    private static final java.util.Set<String> SEED_PHOTOS = seedPhotos();

    private static java.util.Set<String> seedPhotos() {
        java.util.Set<String> photos = new java.util.HashSet<>(List.of("/images/gallery/g-nails-1.jpg",
                "/images/gallery/g-nails-3.jpg", "/images/gallery/g-nails-2.jpg", "/images/gallery/g-nails-5.jpg",
                "/images/gallery/g-lashes-2.jpg", "/images/gallery/g-lashes-4.jpg", "/images/gallery/g-lashes-5.jpg",
                "/images/gallery/g-natural-hair-1.jpg"));
        for (String category : List.of("nail", "lash", "barber", "makeup", "hair")) {
            for (int ordinal = 1; ordinal <= SHOWROOM_PER_TYPE; ordinal++) photos.add(demoPhotoFor(category, ordinal));
        }
        return java.util.Set.copyOf(photos);
    }

    boolean refreshDemoPhoto(StylerEntity styler, ServiceEntity type) {
        return refreshDemoPhoto(styler, type, seedOrdinal(styler));
    }

    /** Sets the seat's sample photo when the current one is empty or another seeded sample; uploads are kept. */
    boolean refreshDemoPhoto(StylerEntity styler, ServiceEntity type, int ordinal) {
        if (!isShowroomRow(styler)) return false;
        String currentPhoto = styler.getProfileImageUrl();
        boolean seedPhoto = currentPhoto == null || currentPhoto.isBlank() || SEED_PHOTOS.contains(currentPhoto);
        String target = demoPhotoFor(type, ordinal);
        if (!seedPhoto || target.equals(currentPhoto)) return false;
        styler.setProfileImageUrl(target);
        return true;
    }

    /**
     * A hand-registered profile the owner asked to show like the seeded ones,
     * listed by id in app.demo.showroom-extra-ids (for example the QA
     * professional "Demo Beauty Co"). The refresh dresses it like the seeded
     * rows (name, bio, photo, weekly hours) but never touches its login, email,
     * address, payment setup or bookings. Only reachable when APP_DEMO_SEED is
     * on, which the live site never sets.
     */
    boolean isShowroomPlaceholder(StylerEntity styler) {
        String id = styler.getStylerId();
        if (id == null || id.startsWith("DEMO") || showroomExtraIds == null) return false;
        return java.util.Arrays.stream(showroomExtraIds.split(",")).map(String::trim).anyMatch(id::equals);
    }

    /** Seeded rows plus showroom placeholders: every profile the showroom refresh may dress. */
    private boolean isShowroomRow(StylerEntity styler) {
        return isSeedOwned(styler) || isShowroomPlaceholder(styler);
    }

    /** True only for rows this seeder created: DEMO id and the seed-only email domain. */
    private static boolean isSeedOwned(StylerEntity styler) {
        return styler.getStylerId() != null && styler.getStylerId().startsWith("DEMO")
                && styler.getEmailAddress() != null && styler.getEmailAddress().endsWith("@" + DEMO_EMAIL_DOMAIN);
    }

    /** The seed position (1-5) recorded in a seeded email such as demo.barber3@...; id-based if absent. */
    private static int seedOrdinal(StylerEntity styler) {
        String local = styler.getEmailAddress() == null ? "" : styler.getEmailAddress().split("@", 2)[0];
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)$").matcher(local);
        if (matcher.find()) {
            try { return Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException ignored) { /* Older malformed seeds keep a stable ID-based sample. */ }
        }
        return Math.floorMod(String.valueOf(styler.getStylerId()).hashCode(), SHOWROOM_PER_TYPE) + 1;
    }

    /** One spot per category and seed position, so the five in a category sit in different parts of the city. */
    static DemoSeedContent.DemoLocation locationFor(ServiceEntity type, int ordinal) {
        int slot = Math.floorMod(ordinal - 1, SHOWROOM_PER_TYPE);
        int categoryIndex = Math.floorMod(type.getId() == null ? 0 : type.getId().intValue() - 1, 5);
        return DemoSeedContent.LOCATIONS[categoryIndex * SHOWROOM_PER_TYPE + slot];
    }

    private static void applyLocation(StylerEntity styler, DemoSeedContent.DemoLocation location) {
        styler.setBusinessAddress(location.address());
        styler.setLatitude(location.latitude());
        styler.setLongitude(location.longitude());
    }

    /**
     * Moves a seeded profile off the old shared downtown pin onto its own
     * address and map point, and makes sure it reads Calgary hours. Only
     * touches seed-owned rows whose address is blank or still one the earlier
     * seeder handed out, so an operator's own edit is never overwritten.
     */
    boolean refreshDemoLocation(StylerEntity styler, ServiceEntity type) {
        return refreshDemoLocation(styler, type, seedOrdinal(styler));
    }

    boolean refreshDemoLocation(StylerEntity styler, ServiceEntity type, int ordinal) {
        if (!isSeedOwned(styler)) return false;
        boolean changed = false;
        if (!"America/Edmonton".equals(styler.getTimeZone())) {
            styler.setTimeZone("America/Edmonton");
            changed = true;
        }
        String current = styler.getBusinessAddress();
        boolean seedAddress = current == null || current.isBlank() || DemoSeedContent.LEGACY_ADDRESSES.contains(current)
                || java.util.Arrays.stream(DemoSeedContent.LOCATIONS).anyMatch(l -> l.address().equals(current));
        if (!seedAddress) return changed;
        DemoSeedContent.DemoLocation target = locationFor(type, ordinal);
        if (target.address().equals(current)
                && Double.valueOf(target.latitude()).equals(styler.getLatitude())
                && Double.valueOf(target.longitude()).equals(styler.getLongitude())) return changed;
        applyLocation(styler, target);
        return true;
    }

    /**
     * Fallback spot for a DEMO row that carries no seed email to place it by,
     * chosen from its id. Deterministic on purpose: the same row keeps the same
     * address across restarts, so a customer who saved a booking never finds it moved.
     */
    static DemoSeedContent.DemoLocation demoLocationFor(String stylerId) {
        String key = stylerId == null ? "" : stylerId;
        return DemoSeedContent.LOCATIONS[Math.floorMod(key.hashCode(), DemoSeedContent.LOCATIONS.length)];
    }

    static String demoAddressFor(String stylerId) {
        return demoLocationFor(stylerId).address();
    }

    /**
     * Gives a sample row a street address when it has none. Never overwrites one
     * that is already set, so an operator's own edit — or a real professional's
     * address — is safe from this seeder.
     */
    private int seedAddress(StylerEntity styler) {
        String existing = styler.getBusinessAddress();
        if (existing != null && !existing.trim().isEmpty()) {
            return 0;
        }
        applyLocation(styler, demoLocationFor(styler.getStylerId()));
        stylerRepo.save(styler);
        return 1;
    }

    /** How much history a seeding pass wrote. */
    record History(int appointments, int reviews) {
        static final History NONE = new History(0, 0);
    }

    /**
     * Gives a sample profile the working history a visitor expects to find:
     * a run of finished appointments and the approved reviews that came out of
     * some of them, so the profile shows a real rating and a real tally instead
     * of zeroes everywhere.
     *
     * <p>Every row obeys the platform's own rules instead of being written
     * around them: each appointment is completed (status "0") with a past date,
     * belongs to a seeded client, and points at one of the stylist's own priced
     * services; each review hangs off one of those appointments by booking id and
     * is APPROVED, because only approved reviews are public. The reviews that do
     * not exist are the newest bookings, which is how a real listing looks —
     * people review what they have had done, not what happened last week.</p>
     *
     * <p>Deterministic from the stylist id, so a restart leaves every rating,
     * date and name exactly where it was rather than reshuffling a professional's
     * reputation. Idempotent: a row that already has any appointment or review is
     * left completely alone, so real history is never added to or rewritten.</p>
     */
    private History seedHistory(StylerEntity styler, ServiceEntity serviceType) {
        String stylerId = styler.getStylerId();
        if (stylerId == null) {
            return History.NONE;
        }
        List<SubServiceEntity> catalogue = subServiceRepo.findByStylerId(stylerId);
        if (catalogue == null || catalogue.isEmpty()) {
            // Nothing to book, so a completed appointment would reference a
            // service that does not exist. The catalogue is seeded first.
            return History.NONE;
        }
        if (!bookAppointmentRepo.findByStylerId(stylerId).isEmpty()
                || !reviewRepo.findByStylerId(stylerId).isEmpty()) {
            return History.NONE;
        }

        int seed = Math.floorMod(stylerId.hashCode(), 10_000);
        int finished = 4 + (seed % 13);          // 4 to 16 finished appointments
        int unreviewed = (seed / 7) % 3;         // the newest 0 to 2 have no review yet
        // Scores are built once for the whole run, so the mix is the tier's
        // mix rather than whatever a slice of a pattern happened to contain.
        int[] scores = demoScores(seed, finished - unreviewed);
        List<UserEntity> clients = demoClients();

        int reviews = 0;
        for (int i = 0; i < finished; i++) {
            SubServiceEntity service = catalogue.get(Math.floorMod(seed + i, catalogue.size()));
            UserEntity client = clients.get(Math.floorMod(seed / 3 + i, clients.size()));
            // Work spread back over the past year or so, always in the past so it
            // can never occupy a slot a real customer wants to book.
            LocalDate date = LocalDate.now().minusDays(17L + (long) i * 13 + Math.floorMod(seed + i * 7, 9));
            String start = DEMO_START_TIMES[Math.floorMod(seed + i, DEMO_START_TIMES.length)];

            BookAppointmentEntity appointment = completedAppointment(stylerId, client, service, date, start, i);
            bookAppointmentRepo.save(appointment);

            if (i >= unreviewed) {
                reviewRepo.save(reviewFor(styler, serviceType, client, appointment, date, seed + i, scores[i - unreviewed]));
                reviews++;
            }
        }
        return new History(finished, reviews);
    }

    /** A finished visit: the row a real completed booking would have left behind. */
    private BookAppointmentEntity completedAppointment(String stylerId, UserEntity client,
                                                       SubServiceEntity service, LocalDate date,
                                                       String start, int index) {
        int duration = service.getDurationMinutes() == null
                ? com.macrotel.rapidstylers.config.AppConstants.DEFAULT_SERVICE_DURATION_MINUTES
                : service.getDurationMinutes();
        LocalTime startTime = LocalTime.parse(start);

        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setAppointmentId("DEMOB" + stylerId.replace("DEMO", "") + "-" + index);
        appointment.setUserId(client.getUserId());
        appointment.setStylerId(stylerId);
        appointment.setSubServiceId(String.valueOf(service.getId()));
        appointment.setAppointmentDate(String.valueOf(date));
        appointment.setAppointmentDateValue(date);
        appointment.setArrivalTime(start);
        appointment.setAppointmentStartTime(startTime);
        appointment.setDurationMinutes(duration);
        appointment.setAppointmentEndTime(startTime.plusMinutes(duration));
        appointment.setServiceTime(String.valueOf(duration));
        appointment.setNoOfPeople("1");
        appointment.setServicePrice(service.getPrice());
        appointment.setPrice(service.getPrice());
        appointment.setTravelFee("0.00");
        appointment.setIncludedTravelKm(15.0);
        appointment.setTravelDistanceKm(0.0);
        appointment.setBillableTravelKm(0.0);
        appointment.setBaseTravelFee("0.00");
        // Completed: status "0" is what the app reads as finished, and what a
        // review is allowed to attach to.
        appointment.setStatus("0");
        appointment.setCompletedAt(date.atTime(startTime).plusMinutes(duration));
        // Booked a few days before it happened, the way a real one is.
        appointment.setCreatedAt(date.minusDays(3L + index % 5).atTime(9, 15).format(DEMO_TIMESTAMP));
        // Payment state is deliberately absent: nothing here is Stripe-backed,
        // so no payout, refund or reconciliation surface can see these rows.
        return appointment;
    }

    /** The review a client left after that visit. */
    private ReviewEntity reviewFor(StylerEntity styler, ServiceEntity serviceType, UserEntity client,
                                   BookAppointmentEntity appointment, LocalDate date, int key, int rating) {
        ReviewEntity review = new ReviewEntity();
        review.setStylerId(styler.getStylerId());
        review.setUserId(client.getUserId());
        review.setBookingId(appointment.getAppointmentId());
        // Full name, the way createStylerReview records a real reviewer.
        review.setUserName((client.getFirstname() + " " + client.getLastname()).trim());
        review.setRatingScore(rating);
        review.setMessage(DemoSeedContent.reviewMessage(
                DemoSeedContent.categoryOf(serviceType.getServiceName()), rating, key, styler.getFirstname()));
        review.setModerationStatus("APPROVED");
        review.setCreatedAt(date.plusDays(1L + Math.floorMod(key, 3)).atTime(20, 5).format(DEMO_TIMESTAMP));
        return review;
    }

    /**
     * Rewrites seeded reviews that still carry the earlier seeder's text, which
     * assumed every professional was a woman working downtown. Keeps the score,
     * dates, reviewer and booking; only the words change. Real reviews, and
     * seeded ones already rewritten, never match and are left alone.
     */
    int refreshSeededReviews(StylerEntity styler, ServiceEntity serviceType) {
        String stylerId = styler.getStylerId();
        if (stylerId == null || !stylerId.startsWith("DEMO")) return 0;
        int seed = Math.floorMod(stylerId.hashCode(), 10_000);
        String category = DemoSeedContent.categoryOf(serviceType.getServiceName());
        int rewritten = 0;
        for (ReviewEntity review : reviewRepo.findByStylerId(stylerId)) {
            String bookingId = review.getBookingId();
            if (bookingId == null || !bookingId.startsWith("DEMOB")
                    || !DemoSeedContent.isSeedReviewText(review.getMessage())) continue;
            int index;
            try { index = Integer.parseInt(bookingId.substring(bookingId.lastIndexOf('-') + 1)); }
            catch (NumberFormatException ex) { index = Math.floorMod(bookingId.hashCode(), 100); }
            String message = DemoSeedContent.reviewMessage(category, review.getRatingScore(), seed + index,
                    styler.getFirstname());
            if (message.equals(review.getMessage())) continue;
            review.setMessage(message);
            reviewRepo.save(review);
            rewritten++;
        }
        return rewritten;
    }

    /**
     * The seeded clients, created once and reused on every later start. Found by
     * email rather than by id so a restart adopts the rows it made last time
     * instead of minting a second set of people.
     *
     * <p>Built at most once per boot, and only if some profile actually needs a
     * history, so an environment that is already fully seeded does no work and
     * creates no one.</p>
     */
    private List<UserEntity> demoClients() {
        if (demoClientCache == null) {
            demoClientCache = buildDemoClients();
        }
        return demoClientCache;
    }

    private List<UserEntity> buildDemoClients() {
        List<UserEntity> clients = new ArrayList<>();
        for (int i = 0; i < DEMO_CLIENTS.length; i++) {
            String email = "client" + (i + 1) + "@" + DEMO_CLIENT_EMAIL_DOMAIN;
            Optional<UserEntity> existing = userRepo.findByEmailAddress(email);
            if (existing.isPresent()) {
                clients.add(existing.get());
                continue;
            }
            UserEntity client = new UserEntity();
            client.setFirstname(DEMO_CLIENTS[i][0]);
            client.setLastname(DEMO_CLIENTS[i][1]);
            client.setEmailAddress(email);
            client.setPassword(UNMATCHABLE_PASSWORD_HASH);
            client.setStatus("0");
            client.setUserId("DEMOC" + (1001 + i));
            client.setCountry("Canada");
            client.setState("Alberta");
            client.setRegistrationMethod("EMAIL");
            // Deliberately not today: a client who has been booking for months
            // should not look like an account created this morning.
            client.setInsertedDt(String.valueOf(LocalDate.now().minusDays(200L + i * 24L)));
            userRepo.save(client);
            clients.add(client);
        }
        return clients;
    }

    /** Kept for the earlier test's single-argument call shape. */
    StylerEntity demoStylerFor(ServiceEntity serviceType) {
        return demoStylerFor(serviceType, 1);
    }

    /**
     * Gives a fresh demo stylist the priced service catalogue their field's
     * words select. Idempotent via the repo's per-styler existence check, so a
     * restart can never duplicate rows even if a previous run died mid-way.
     */
    private int seedCatalogue(StylerEntity styler, ServiceEntity serviceType) {
        String catalogueKey = catalogueKeyFor(serviceType.getServiceName());
        List<String[]> catalogue = DEMO_SERVICES.stream()
                .filter((String[] entry) -> matches(catalogueKey, entry[0]))
                .toList();
        int created = 0;
        for (String[] entry : catalogue) {
            if (subServiceRepo.isServiceExist(styler.getStylerId(), entry[0]).isEmpty()) {
                SubServiceEntity service = new SubServiceEntity();
                service.setStylerId(styler.getStylerId());
                service.setName(entry[0]);
                service.setPrice(entry[1]);
                service.setDurationMinutes(Integer.valueOf(entry[2]));
                service.setStatus("0"); // the app's active flag
                service.setCreatedAt(String.valueOf(LocalDate.now()));
                subServiceRepo.save(service);
                created++;
            }
        }
        return created;
    }

    /**
     * Renames the hair services an earlier seed gave a makeup artist into makeup
     * services, in place: same row and id, so every booking and review that
     * points at it stays valid. Skips a rename that would duplicate a service
     * the profile already has. Only for DEMO rows in a makeup category.
     */
    int retargetMakeupCatalogue(StylerEntity styler, ServiceEntity serviceType) {
        String stylerId = styler.getStylerId();
        if (stylerId == null || !stylerId.startsWith("DEMO")
                || !"makeup".equals(catalogueKeyFor(serviceType.getServiceName()))) return 0;
        List<SubServiceEntity> rows = subServiceRepo.findByStylerId(stylerId);
        if (rows == null || rows.isEmpty()) return 0;
        List<String> hairNames = DEMO_SERVICES.stream().filter(e -> matches("hairstylist", e[0])).map(e -> e[0]).toList();
        List<String[]> makeup = DEMO_SERVICES.stream().filter(e -> matches("makeup", e[0])).toList();
        java.util.Set<String> names = new java.util.HashSet<>();
        rows.forEach(row -> names.add(row.getName()));
        int renamed = 0;
        for (SubServiceEntity row : rows) {
            int index = hairNames.indexOf(row.getName());
            if (index < 0) continue;
            String[] target = makeup.get(index % makeup.size());
            if (names.contains(target[0])) continue;
            names.add(target[0]);
            row.setName(target[0]);
            row.setPrice(target[1]);
            row.setDurationMinutes(Integer.valueOf(target[2]));
            subServiceRepo.save(row);
            renamed++;
        }
        return renamed;
    }

    /** Picks which catalogue words apply by matching the type name's words. */
    private String catalogueKeyFor(String serviceTypeName) {
        String name = String.valueOf(serviceTypeName == null ? "" : serviceTypeName).toLowerCase();
        for (String[] keys : CATALOGUE_KEYS) {
            for (String key : keys) {
                if (name.contains(key)) {
                    return key;
                }
            }
        }
        // Unknown type: fall back to the generic hair catalogue so the type is
        // still bookable rather than profile-complete-but-service-less.
        return "hairstylist";
    }

    private boolean matches(String catalogueKey, String serviceName) {
        String lower = serviceName.toLowerCase();
        switch (catalogueKey) {
            case "nail":  return lower.contains("manicure") || lower.contains("pedicure");
            case "lash":  return lower.contains("lash");
            case "makeup": return lower.contains("makeup");
            case "barber": case "buzz": case "fade": case "clipper":
                          return lower.contains("fade") || lower.contains("beard") || lower.contains("haircut");
            default:      return lower.contains("press") || lower.contains("blowout")
                                  || lower.contains("braid") || lower.contains("loc")
                                  || lower.contains("natural");
        }
    }

    /**
     * Opens a demo stylist's calendar Monday to Sunday with broad hours that
     * differ from one stylist to the next (see {@link DemoSeedContent#hoursFor}).
     * Only fills days that have no hours yet, and widens the two short windows
     * the earlier seeder wrote (Tue 10-5, Sat 9-4); any other shift, such as one
     * set up for QA, is kept exactly as it is. Bookings are untouched. DEMO rows
     * only, and idempotent: a fully open week writes nothing.
     */
    int seedAvailability(StylerEntity styler) {
        String stylerId = styler.getStylerId();
        if (stylerId == null || !(stylerId.startsWith("DEMO") || isShowroomPlaceholder(styler))) {
            return 0;
        }
        List<AvailabilityEntity> changed = new ArrayList<>();
        java.util.Set<String> coveredDays = new java.util.HashSet<>();
        for (AvailabilityEntity row : availabilityRepo.findByStylerId(stylerId)) {
            String day = row.getDayOfWeek();
            if (day == null) continue;
            coveredDays.add(day);
            if (DemoSeedContent.isLegacySeedWindow(day, row.getStartTime(), row.getEndTime())) {
                String[] hours = DemoSeedContent.hoursFor(stylerId, Integer.parseInt(day));
                row.setStartTime(hours[0]);
                row.setEndTime(hours[1]);
                changed.add(row);
            }
        }
        String today = String.valueOf(LocalDate.now());
        for (int day = 0; day <= 6; day++) {
            if (coveredDays.contains(String.valueOf(day))) continue;
            String[] hours = DemoSeedContent.hoursFor(stylerId, day);
            AvailabilityEntity slot = new AvailabilityEntity();
            slot.setStylerId(stylerId);
            slot.setDayOfWeek(String.valueOf(day));
            slot.setStartTime(hours[0]);
            slot.setEndTime(hours[1]);
            slot.setCreatedAt(today);
            changed.add(slot);
        }
        if (!changed.isEmpty()) {
            availabilityRepo.saveAll(changed);
        }
        return changed.size();
    }

    /** DEMO + 4 random digits — same shape/length as organic DS-prefixed ids, but greppable as seed data. */
    private String mintDemoStylerId() {
        String digits = String.valueOf(1000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(9000));
        return "DEMO" + digits;
    }
}
