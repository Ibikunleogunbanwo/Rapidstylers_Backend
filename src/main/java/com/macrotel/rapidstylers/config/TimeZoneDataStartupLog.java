package com.macrotel.rapidstylers.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRulesProvider;

/**
 * Logs which IANA tz data release the JVM is using, plus the business zone's
 * current offset, once at startup. Canada changed its rules in 2026 (BC, Alberta,
 * NWT and Manitoba stay on daylight time from 2026-11-01), and a JRE with older
 * rules silently shows times an hour off — the production image swaps in newer
 * rules (see Dockerfile), and this line makes the version on a running server
 * checkable with one grep. Never fails startup.
 */
@Component
public class TimeZoneDataStartupLog implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TimeZoneDataStartupLog.class);

    @Value("${app.time-zone:America/Edmonton}")
    private String appTimeZone;

    @Override
    public void run(ApplicationArguments args) {
        log.info(describe(appTimeZone, Instant.now()));
    }

    static String describe(String zoneId, Instant now) {
        String version;
        try {
            version = ZoneRulesProvider.getVersions("UTC").lastKey();
        } catch (RuntimeException ex) {
            version = "unknown";
        }
        String offset;
        try {
            offset = ZoneId.of(zoneId).getRules().getOffset(now).getId();
        } catch (RuntimeException ex) {
            offset = "invalid zone";
        }
        return "Time zone data: tzdb " + version + " (java " + System.getProperty("java.version")
                + "); app.time-zone=" + zoneId + " offset now " + offset;
    }
}
