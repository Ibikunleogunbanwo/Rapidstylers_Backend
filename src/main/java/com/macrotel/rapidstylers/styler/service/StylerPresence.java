package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.styler.dto.StylerAccountDTO;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The "Online" badge on a stylist's card: open for business right now.
 *
 * <p>It follows the weekly hours the stylist set and their days off, read on
 * the stylist's own clock (the zone of their business address, else their
 * province). Being signed in does not matter: a stylist can be signed in and
 * closed, or signed out and open for bookings. This is the same rule the
 * "Open now" search filter uses, so a card and the filter never disagree.
 */
@Component
public class StylerPresence {

    public static final String ONLINE = "Online";
    public static final String OFFLINE = "Offline";

    @Autowired
    AvailabilityService availabilityService;

    Clock clock = Clock.systemUTC();

    /** Online when "now" on the stylist's clock falls inside their set hours. */
    public String status(String stylerId, String timeZone, String province) {
        if (stylerId == null) {
            return OFFLINE;
        }
        ZoneId zone = zoneOf(timeZone, province);
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        try {
            return availabilityService.isWithinHours(stylerId, now.toLocalDate(), now.toLocalTime()) ? ONLINE : OFFLINE;
        } catch (Exception ex) {
            return OFFLINE;
        }
    }

    /** Sets the badge on a DTO the caller owns (never on a shared cached one). */
    public void stamp(StylerAccountDTO dto) {
        if (dto != null) {
            dto.setVisibilityStatus(status(dto.getStylerId(), dto.getTimeZone(), dto.getProvince()));
        }
    }

    /** The stylist's clock: stored zone, else province, else the app default. */
    public static ZoneId zoneOf(String timeZone, String province) {
        ZoneId zone = VendorZoneResolver.zoneForStyler(timeZone, province);
        return zone != null ? zone : VendorZoneResolver.zoneForProvince(null);
    }
}
