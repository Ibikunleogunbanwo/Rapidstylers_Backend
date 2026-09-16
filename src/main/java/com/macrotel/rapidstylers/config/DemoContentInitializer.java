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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /** Written from a client's side, about the appointment rather than the marketing. */
    private static final String[] DEMO_REVIEWS_FIVE = {
        "Booked late and still got her full attention. Nothing felt rushed and the finish held up for weeks.",
        "She asked what I actually wanted before touching anything, then said honestly what would work and what would not.",
        "Second visit. Same care as the first, and she remembered exactly how I like it finished.",
        "Calm space, no upselling, and I left with something I can actually keep up at home.",
        "Walked in with a photo and a bad experience from another place. She fixed it and explained every step.",
        "Ran a little over because she was fixing something nobody else had noticed. Worth the extra ten minutes.",
        "My daughter is picky about everything and she left happy. That is the whole review.",
        "First time I have booked the next appointment before leaving instead of thinking about it for a week."
    };

    private static final String[] DEMO_REVIEWS_FOUR = {
        "Really happy with the result. Not a five only because parking downtown cost me fifteen minutes.",
        "Good work at a fair price. We started about ten minutes late, which she warned me about by text.",
        "She clearly knows the craft and took her time. I would have liked a bit more guidance on aftercare.",
        "Solid visit start to finish. The space is small, so it gets warm in the afternoon."
    };

    private static final String[] DEMO_REVIEWS_THREE = {
        "The work was good, but we ran well past the time I booked, so I was late getting back to work.",
        "Neat work and friendly, though I asked for a shorter finish and it came out longer than we agreed."
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
        new String[]{"Braids", "120.00", "180"},
        new String[]{"Loc retwist", "80.00", "90"},
        new String[]{"Natural hair styling", "90.00", "90"}
    );

    /**
     * Where the sample professionals practise. Real Calgary commercial blocks
     * with invented suites, so a booking that says "visit the stylist" can name
     * a place. Picked per row from the row's own id, so a restart leaves every
     * address exactly where it was rather than shuffling them.
     */
    static final String[] DEMO_ADDRESSES = {
        "Suite 210, 102 8 Ave SW",
        "Unit 4, 1414 8 St SW",
        "Suite 320, 1100 1 St SE",
        "Unit 12, 3510 17 Ave SW",
        "Suite 200, 1301 16 Ave NW",
        "Unit 8, 404 10 St NW",
        "Suite 410, 888 3 St SW",
        "Unit 3, 1935 37 St SW",
        "Suite 150, 6005 3 St SW",
        "Unit 20, 4800 16 Ave NW",
        "Suite 220, 222 7 St SW",
        "Unit 14, 2116 33 Ave SW",
        "Suite 305, 924 17 Ave SW",
        "Unit 9, 5009 16 Ave NW",
        "Suite 180, 110 9 Ave SW",
        "Unit 6, 1010 1 Ave NE"
    };

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
            for (ServiceEntity serviceType : serviceTypes) {
                String typeId = String.valueOf(serviceType.getId());
                List<StylerEntity> approved = stylerRepo.findByServiceTypeId(typeId).stream()
                        .filter(s -> "APPROVED".equals(s.getVerificationStatus()))
                        .toList();
                int missing = SHOWROOM_PER_TYPE - approved.size();
                for (int i = 0; i < missing; i++) {
                    StylerEntity demo = stylerRepo.save(
                            demoStylerFor(serviceType, approved.size() + i + 1));
                    createdStylists++;
                    createdServices += seedCatalogue(demo, serviceType);
                    createdSlots += seedAvailability(demo);
                    History history = seedHistory(demo);
                    seededAppointments += history.appointments();
                    seededReviews += history.reviews();
                }
                // Stylists seeded by the earlier one-per-type version predate the
                // catalogue feature and would show "No services available yet"
                // forever. Backfill them — DEMO-prefixed rows only, so a real
                // professional's profile is never touched by this seeder.
                for (StylerEntity legacy : approved) {
                    if (legacy.getStylerId() != null && legacy.getStylerId().startsWith("DEMO")) {
                        createdServices += seedCatalogue(legacy, serviceType);
                        createdSlots += seedAvailability(legacy);
                        // Rows seeded before addresses existed would keep showing
                        // "no address yet" on every booking made against them.
                        filledAddresses += seedAddress(legacy);
                        // Same idea for the history: a sample profile with no
                        // reviews reads as an abandoned listing rather than a
                        // professional anyone has hired.
                        History history = seedHistory(legacy);
                        seededAppointments += history.appointments();
                        seededReviews += history.reviews();
                    }
                }
            }
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
        demo.setFirstname("Demo");
        demo.setLastname(name);
        demo.setBusinessName("Demo " + name + " Studio " + ordinal);
        demo.setEmailAddress("demo." + name.toLowerCase().replaceAll("[^a-z0-9]+", "") + ordinal
                + "@" + DEMO_EMAIL_DOMAIN);
        demo.setPassword(UNMATCHABLE_PASSWORD_HASH);
        demo.setServiceTypeId(String.valueOf(serviceType.getId()));
        demo.setCountry("Canada");
        demo.setProvince("Alberta");
        // Demo vendors model Calgary businesses; their hours must read in the
        // vendor's zone like real rows do.
        demo.setTimeZone("America/Edmonton");
        demo.setCity("Calgary");
        // Downtown Calgary coordinates: with them, the seeded rows also answer
        // nearby searches, not just category tabs. Relevant only when the
        // operator enables APP_DEMO_SEED.
        demo.setLatitude(51.0447);
        demo.setLongitude(-114.0719);
        demo.setDescription("Sample professional so this category always shows a full row. "
                + "Book a real professional for an actual appointment.");
        demo.setInsertedDt(String.valueOf(LocalDate.now()));
        demo.setStatus("0");
        demo.setIsOnline("0"); // the app's "Online" flag (0 = online)
        String stylerId = mintDemoStylerId();
        demo.setStylerId(stylerId);
        demo.setBusinessAddress(demoAddressFor(stylerId));
        demo.setVerificationStatus("APPROVED");
        // Completed-profile mimicry: public search shows only bookable
        // professionals (approved + Connect COMPLETE when payments are live),
        // so a seeded row must carry both or it would never be visible.
        demo.setConnectOnboardingStatus("COMPLETE");
        demo.setIncludedTravelKm(15.0);
        demo.setBaseTravelFee("0.00");
        return demo;
    }

    /**
     * The sample address for a row, chosen from its id. Deterministic on
     * purpose: the same row keeps the same address across restarts, so a
     * customer who saved a booking never finds it moved.
     */
    static String demoAddressFor(String stylerId) {
        String key = stylerId == null ? "" : stylerId;
        return DEMO_ADDRESSES[Math.floorMod(key.hashCode(), DEMO_ADDRESSES.length)];
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
        styler.setBusinessAddress(demoAddressFor(styler.getStylerId()));
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
    private History seedHistory(StylerEntity styler) {
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
                reviewRepo.save(reviewFor(styler, client, appointment, date, seed + i, scores[i - unreviewed]));
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
    private ReviewEntity reviewFor(StylerEntity styler, UserEntity client,
                                   BookAppointmentEntity appointment, LocalDate date, int key, int rating) {
        ReviewEntity review = new ReviewEntity();
        review.setStylerId(styler.getStylerId());
        review.setUserId(client.getUserId());
        review.setBookingId(appointment.getAppointmentId());
        // Full name, the way createStylerReview records a real reviewer.
        review.setUserName((client.getFirstname() + " " + client.getLastname()).trim());
        review.setRatingScore(rating);
        review.setMessage(reviewMessage(rating, key));
        review.setModerationStatus("APPROVED");
        review.setCreatedAt(date.plusDays(1L + Math.floorMod(key, 3)).atTime(20, 5).format(DEMO_TIMESTAMP));
        return review;
    }

    private String reviewMessage(int rating, int key) {
        String[] pool = rating >= 5 ? DEMO_REVIEWS_FIVE
                : rating == 4 ? DEMO_REVIEWS_FOUR
                : DEMO_REVIEWS_THREE;
        return pool[Math.floorMod(key, pool.length)];
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
            case "barber": case "buzz": case "fade": case "clipper":
                          return lower.contains("fade") || lower.contains("beard") || lower.contains("haircut");
            default:      return lower.contains("press") || lower.contains("blowout")
                                  || lower.contains("braid") || lower.contains("loc")
                                  || lower.contains("natural");
        }
    }

    /**
     * Gives a fresh demo stylist two weekly windows (Tue 10:00-17:00,
     * Sat 09:00-16:00) so the booking modal shows open days. Idempotent via the
     * per-styler read before insert.
     */
    private int seedAvailability(StylerEntity styler) {
        if (!availabilityRepo.findByStylerId(styler.getStylerId()).isEmpty()) {
            return 0;
        }
        String today = String.valueOf(LocalDate.now());
        AvailabilityEntity tuesday = new AvailabilityEntity();
        tuesday.setStylerId(styler.getStylerId());
        tuesday.setDayOfWeek("2");
        tuesday.setStartTime("10:00");
        tuesday.setEndTime("17:00");
        tuesday.setCreatedAt(today);
        AvailabilityEntity saturday = new AvailabilityEntity();
        saturday.setStylerId(styler.getStylerId());
        saturday.setDayOfWeek("6");
        saturday.setStartTime("09:00");
        saturday.setEndTime("16:00");
        saturday.setCreatedAt(today);
        availabilityRepo.saveAll(List.of(tuesday, saturday));
        return 2;
    }

    /** DEMO + 4 random digits — same shape/length as organic DS-prefixed ids, but greppable as seed data. */
    private String mintDemoStylerId() {
        String digits = String.valueOf(1000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(9000));
        return "DEMO" + digits;
    }
}
