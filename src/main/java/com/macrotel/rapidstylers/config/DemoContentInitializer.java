package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.entity.AvailabilityEntity;
import com.macrotel.rapidstylers.entity.ServiceEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.SubServiceEntity;
import com.macrotel.rapidstylers.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.repo.ServiceRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

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
 *  - no phone/address, so nothing contacts a real person.
 *
 * Idempotent: the per-type count includes every approved stylist, seeded or
 * real, so a restart tops up only what is missing and never fights live data.
 */
@Component
public class DemoContentInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoContentInitializer.class);

    /** Approved stylists per service type after seeding — enough for a full homepage grid row. */
    static final int SHOWROOM_PER_TYPE = 5;

    /** Unmatchable bcrypt digest of a discarded random string — no password verifies against it. */
    static final String UNMATCHABLE_PASSWORD_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5B0C1V3m6GkQj0F0S8dEmVn2rKzQO";

    static final String DEMO_EMAIL_DOMAIN = "demo-stylist.rapidstylers.ca";

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

    @Value("${app.demo.seed:false}")
    private String demoSeed;

    public DemoContentInitializer(ServiceRepo serviceRepo, StylerRepo stylerRepo,
                                  SubServiceRepo subServiceRepo, AvailabilityRepo availabilityRepo) {
        this.serviceRepo = serviceRepo;
        this.stylerRepo = stylerRepo;
        this.subServiceRepo = subServiceRepo;
        this.availabilityRepo = availabilityRepo;
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
                }
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
        demo.setStylerId(mintDemoStylerId());
        demo.setVerificationStatus("APPROVED");
        // Completed-profile mimicry: public search shows only bookable
        // professionals (approved + Connect COMPLETE when payments are live),
        // so a seeded row must carry both or it would never be visible.
        demo.setConnectOnboardingStatus("COMPLETE");
        demo.setIncludedTravelKm(15.0);
        demo.setBaseTravelFee("0.00");
        return demo;
    }

    /**Kept for the earlier test's single-argument call shape. */
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
