package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.service.GoogleTimezoneService;
import com.macrotel.rapidstylers.service.VendorZoneResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Gives every vendor with a geocoded address a Google-exact time zone.
 *
 * <p>Availability rows hold bare local times, so the zone on the styler record
 * decides which clock those hours are read on — for the "open now" gate, the
 * booking modal, appointment cards and payouts. Rows written before the lookup
 * existed (or written while the Google key was missing, rate-limited or
 * unreachable) carry only a province-map answer, which is wrong for any province
 * with more than one zone and therefore worth improving for every vendor, not
 * only the ones in an obviously different province.
 *
 * <p>Which rows need work is decided by provenance, not by value: an Alberta
 * address resolves to 'America/Edmonton' whether Google answered or the province
 * map guessed, so comparing the stored zone against the province answer could not
 * tell a real lookup from a fallback. Rows already stamped
 * {@link VendorZoneResolver#SOURCE_GOOGLE} are skipped outright, which is what
 * keeps a restart from spending a Google call per vendor forever.
 *
 * <p>Idempotent and fail-soft by construction:
 *  - no coordinates → nothing to resolve, left alone;
 *  - already Google-derived → no API call at all;
 *  - Google cannot answer (no key, quota, network) → the stored zone and its
 *    marker are left untouched, so the row is retried on the next boot rather
 *    than being stamped with a guess;
 *  - any exception is logged, never allowed to block application boot.
 *
 * <p>Runs after the seeders so a row created in this boot is also considered,
 * and its zone is also what makes the seeded Alberta demo rows carry the same
 * exact answer a real Calgary signup would get.
 */
@Component
@Order(3)
public class VendorZoneBackfill implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(VendorZoneBackfill.class);

    private final StylerRepo stylerRepo;
    private final GoogleTimezoneService googleTimezoneService;

    public VendorZoneBackfill(StylerRepo stylerRepo, GoogleTimezoneService googleTimezoneService) {
        this.stylerRepo = stylerRepo;
        this.googleTimezoneService = googleTimezoneService;
    }

    @Override
    public void run(String... args) {
        try {
            List<StylerEntity> stylers = stylerRepo.findAll();
            int candidates = 0;
            int corrected = 0;
            int alreadyExact = 0;
            int unresolved = 0;

            for (StylerEntity styler : stylers) {
                if (styler.getLatitude() == null || styler.getLongitude() == null) {
                    continue; // nothing to resolve against
                }
                if (VendorZoneResolver.isGoogleDerived(styler.getTimeZoneSource())) {
                    alreadyExact++;
                    continue; // already resolved from this address — never re-query
                }
                candidates++;

                String resolved = googleTimezoneService.timeZoneId(
                        styler.getLatitude(), styler.getLongitude());
                if (resolved == null) {
                    // No answer: keep what the row has (a province fallback beats
                    // nothing) and leave the marker alone so the next boot retries.
                    unresolved++;
                    continue;
                }

                String previous = styler.getTimeZone();
                boolean changed = !resolved.equals(previous);
                styler.setTimeZone(resolved);
                styler.setTimeZoneSource(VendorZoneResolver.SOURCE_GOOGLE);
                stylerRepo.save(styler);
                if (changed) {
                    corrected++;
                    log.info("Vendor {} time zone corrected from {} to {} (derived from its address)",
                            styler.getStylerId(), previous == null ? "(unset)" : previous, resolved);
                }
            }

            if (candidates > 0 || corrected > 0) {
                log.info("Vendor time zone backfill: {} row(s) with coordinates needed a lookup, {} corrected, "
                                + "{} left unchanged but now marked exact, {} unresolved and retried next boot",
                        candidates, corrected, candidates - corrected - unresolved, unresolved);
            } else if (alreadyExact > 0) {
                log.info("Vendor time zone backfill: all {} vendor(s) with coordinates already carry a Google-exact zone",
                        alreadyExact);
            }
        } catch (Exception ex) {
            // A backfill failure must never block application boot.
            log.warn("Vendor time zone backfill skipped: {}", ex.getMessage());
        }
    }
}
