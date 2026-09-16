-- V10: store each vendor's IANA time zone on the record.
--
-- Availability rows hold bare local times ("09:00") with no zone, so every
-- surface that judges "is this vendor open right now" needs to know which
-- clock those times refer to. Until now the app assumed one zone (Alberta) —
-- correct there, silently wrong in any other province. The zone is derived
-- from the signup geocode's lat/lng where available; existing rows are
-- backfilled from their province. NULL keeps the app default everywhere, so
-- rows that predate geocoding degrade to the old behaviour, never crash.

ALTER TABLE stylers
    ADD COLUMN time_zone VARCHAR(64) NULL AFTER province;

-- Province-based backfill. Provinces not listed here stay NULL and inherit
-- the application default at read time — the same fallback VendorZoneResolver
-- applies to stored values.
UPDATE stylers SET time_zone = 'America/Edmonton'   WHERE province = 'Alberta';
UPDATE stylers SET time_zone = 'America/Vancouver'  WHERE province = 'British Columbia';
UPDATE stylers SET time_zone = 'America/Winnipeg'   WHERE province = 'Manitoba';
UPDATE stylers SET time_zone = 'America/Moncton'    WHERE province IN ('New Brunswick');
UPDATE stylers SET time_zone = 'America/St_Johns'   WHERE province = 'Newfoundland and Labrador';
UPDATE stylers SET time_zone = 'America/Halifax'    WHERE province IN ('Nova Scotia', 'Prince Edward Island');
UPDATE stylers SET time_zone = 'America/Toronto'    WHERE province IN ('Ontario', 'Quebec');
UPDATE stylers SET time_zone = 'America/Regina'     WHERE province = 'Saskatchewan';
UPDATE stylers SET time_zone = 'America/Yellowknife' WHERE province = 'Northwest Territories';
UPDATE stylers SET time_zone = 'America/Iqaluit'    WHERE province = 'Nunavut';
UPDATE stylers SET time_zone = 'America/Whitehorse' WHERE province = 'Yukon';
