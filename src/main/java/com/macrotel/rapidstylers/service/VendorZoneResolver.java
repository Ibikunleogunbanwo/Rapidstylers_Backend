package com.macrotel.rapidstylers.service;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the time zone a vendor's weekly hours live in, from the business
 * address's province. Availability rows store bare local times with no zone,
 * so every surface that judges "is the vendor available right now" must
 * interpret them in the vendor's zone, not the server's or the visitor's.
 *
 * Canada is the service area today, so the map is explicit (most provinces
 * have a single dominant zone); unknown or missing provinces fall back to the
 * app default (America/Edmonton, Alberta) so nothing crashes and existing
 * behaviour is preserved. If the platform expands to multi-zone countries
 * with province-internal splits, replace this with per-vendor lat/lng zone
 * lookup — the call sites should not change.
 */
public final class VendorZoneResolver {

    private VendorZoneResolver() {}

    /** Default when a vendor has no usable province: the app's home zone. */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Edmonton");

    /**
     * How a stored zone was derived. A zone resolved from the vendor's geocoded
     * address is exact for the real place; one that came from the province map is
     * a fallback that a later lookup can improve. Stored alongside the zone
     * because the two are often the same string (an Alberta address resolves to
     * 'America/Edmonton' either way), so the value alone cannot say which it is.
     */
    public static final String SOURCE_GOOGLE = "google";
    public static final String SOURCE_PROVINCE = "province";

    private static final Map<String, ZoneId> BY_PROVINCE = Map.ofEntries(
            // Province names exactly as the signup flow and address autocomplete write them.
            Map.entry("alberta", ZoneId.of("America/Edmonton")),
            Map.entry("british columbia", ZoneId.of("America/Vancouver")),
            Map.entry("manitoba", ZoneId.of("America/Winnipeg")),
            Map.entry("new brunswick", ZoneId.of("America/Moncton")),
            Map.entry("newfoundland and labrador", ZoneId.of("America/St_Johns")),
            Map.entry("nova scotia", ZoneId.of("America/Halifax")),
            Map.entry("ontario", ZoneId.of("America/Toronto")),
            Map.entry("prince edward island", ZoneId.of("America/Halifax")),
            Map.entry("quebec", ZoneId.of("America/Toronto")),
            Map.entry("saskatchewan", ZoneId.of("America/Regina")),
            Map.entry("northwest territories", ZoneId.of("America/Yellowknife")),
            Map.entry("nunavut", ZoneId.of("America/Iqaluit")),
            Map.entry("yukon", ZoneId.of("America/Whitehorse")),
            // Abbreviations, in case data arrives that way.
            Map.entry("ab", ZoneId.of("America/Edmonton")),
            Map.entry("bc", ZoneId.of("America/Vancouver")),
            Map.entry("mb", ZoneId.of("America/Winnipeg")),
            Map.entry("nb", ZoneId.of("America/Moncton")),
            Map.entry("nl", ZoneId.of("America/St_Johns")),
            Map.entry("ns", ZoneId.of("America/Halifax")),
            Map.entry("on", ZoneId.of("America/Toronto")),
            Map.entry("pe", ZoneId.of("America/Halifax")),
            Map.entry("qc", ZoneId.of("America/Toronto")),
            Map.entry("sk", ZoneId.of("America/Regina")),
            Map.entry("nt", ZoneId.of("America/Yellowknife")),
            Map.entry("nu", ZoneId.of("America/Iqaluit")),
            Map.entry("yt", ZoneId.of("America/Whitehorse")),
            // US states for future expansion (safe defaults; split zones use
            // the metro area the platform would realistically serve first).
            Map.entry("california", ZoneId.of("America/Los_Angeles")),
            Map.entry("ca", ZoneId.of("America/Los_Angeles")),
            Map.entry("new york", ZoneId.of("America/New_York")),
            Map.entry("ny", ZoneId.of("America/New_York")),
            Map.entry("texas", ZoneId.of("America/Chicago")),
            Map.entry("tx", ZoneId.of("America/Chicago")),
            Map.entry("florida", ZoneId.of("America/New_York")),
            Map.entry("fl", ZoneId.of("America/New_York")),
            Map.entry("illinois", ZoneId.of("America/Chicago")),
            Map.entry("il", ZoneId.of("America/Chicago")),
            Map.entry("washington", ZoneId.of("America/Los_Angeles")),
            Map.entry("wa", ZoneId.of("America/Los_Angeles")),
            Map.entry("georgia", ZoneId.of("America/New_York")),
            Map.entry("ga", ZoneId.of("America/New_York")),
            Map.entry("arizona", ZoneId.of("America/Phoenix")),
            Map.entry("az", ZoneId.of("America/Phoenix")),
            Map.entry("colorado", ZoneId.of("America/Denver")),
            Map.entry("co", ZoneId.of("America/Denver")),
            Map.entry("nevada", ZoneId.of("America/Los_Angeles")),
            Map.entry("nv", ZoneId.of("America/Los_Angeles"))
    );

    /** True when the stored zone is already exact for the vendor's address. */
    public static boolean isGoogleDerived(String timeZoneSource) {
        return SOURCE_GOOGLE.equalsIgnoreCase(timeZoneSource == null ? "" : timeZoneSource.trim());
    }

    /**
     * Vendor zone for a province label. Case-insensitive, trims whitespace,
     * and never returns null — unknown values fall back to the app default.
     */
    public static ZoneId zoneForProvince(String province) {
        if (province == null) return DEFAULT_ZONE;
        String key = province.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return DEFAULT_ZONE;
        return BY_PROVINCE.getOrDefault(key, DEFAULT_ZONE);
    }
}
