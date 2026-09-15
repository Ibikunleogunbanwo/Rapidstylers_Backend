package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.entity.ServiceEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.repo.ServiceRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Guarantees one APPROVED demo stylist per service type at boot, so a freshly
 * provisioned (or wiped) environment never renders an all-empty Discover
 * professionals section to visitors. The five public categories are fetched
 * from the database, not hardcoded — a service type added later is seeded
 * automatically, and one that already has any approved stylist is left alone.
 *
 * Gated behind APP_DEMO_SEED=true so production operators opt in explicitly;
 * default is off. Seeded rows are inert by construction:
 *  - email under demo-stylist+.rapidstylers.ca, password an unmatchable
 *    bcrypt hash (no login path);
 *  - verificationStatus APPROVED, isOnline "0" (the app's "Online" flag) and
 *    Connect onboarding COMPLETE: they mimic fully finished profiles, which is
 *    what public search now shows (approved + bookable) — a customer is never
 *    shown a professional they cannot actually book;
 *  - no Stripe Connect ACCOUNT id, so nothing payment-real exists behind the
 *    COMPLETE marker — booking attempts would fail at payment setup, which is
 *    acceptable for inert demo rows on empty environments;
 *  - no phone/address, so nothing contacts a real person.
 *
 * Idempotent: a service type with an approved stylist — seeded or real — is
 * never touched, so the seeder cannot fight live data on restart.
 */
@Component
public class DemoContentInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoContentInitializer.class);

    /** Unmatchable bcrypt digest of a discarded random string — no password verifies against it. */
    static final String UNMATCHABLE_PASSWORD_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5B0C1V3m6GkQj0F0S8dEmVn2rKzQO";

    static final String DEMO_EMAIL_DOMAIN = "demo-stylist.rapidstylers.ca";

    private final ServiceRepo serviceRepo;
    private final StylerRepo stylerRepo;

    @Value("${app.demo.seed:false}")
    private String demoSeed;

    public DemoContentInitializer(ServiceRepo serviceRepo, StylerRepo stylerRepo) {
        this.serviceRepo = serviceRepo;
        this.stylerRepo = stylerRepo;
    }

    @Override
    public void run(String... args) {
        if (!"true".equalsIgnoreCase(demoSeed == null ? "" : demoSeed.trim())) {
            return;
        }
        try {
            List<ServiceEntity> serviceTypes = serviceRepo.findAll();
            int created = 0;
            for (ServiceEntity serviceType : serviceTypes) {
                String typeId = String.valueOf(serviceType.getId());
                if (!stylerRepo.findByServiceTypeId(typeId).stream()
                        .anyMatch(s -> "APPROVED".equals(s.getVerificationStatus()))) {
                    stylerRepo.save(demoStylerFor(serviceType));
                    created++;
                }
            }
            if (created > 0) {
                log.info("Seeded {} demo stylist(s) so every service type has an approved professional", created);
                // Stale search-list caches self-heal on their short TTL; on the
                // wiped environments this seeder targets, the caches are empty
                // anyway. No eviction needed at this layer.
            }
        } catch (Exception ex) {
            // A seeding failure must never block application boot.
            log.warn("Demo content seeding skipped: {}", ex.getMessage());
        }
    }

    StylerEntity demoStylerFor(ServiceEntity serviceType) {
        String name = serviceType.getServiceName() == null ? "Professional" : serviceType.getServiceName();
        StylerEntity demo = new StylerEntity();
        demo.setFirstname("Demo");
        demo.setLastname(name);
        demo.setBusinessName("Demo " + name + " Studio");
        demo.setEmailAddress("demo." + name.toLowerCase().replaceAll("[^a-z0-9]+", "") + "@" + DEMO_EMAIL_DOMAIN);
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
        demo.setDescription("Sample professional so this category is never empty. Book a real professional for an actual appointment.");
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

    /** DEMO + 4 random digits — same shape/length as organic DS-prefixed ids, but greppable as seed data. */
    private String mintDemoStylerId() {
        String digits = String.valueOf(1000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(9000));
        return "DEMO" + digits;
    }
}
