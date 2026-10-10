-- V15: record how each vendor's time zone was derived.
--
-- V14 stored the zone but not its provenance, so once written a province-map
-- guess and a Google-exact lookup are indistinguishable by value: for an Alberta
-- address both are 'America/Edmonton'. "The value equals the province answer" is
-- therefore not evidence of a guess, and a backfill cannot use it to decide what
-- still needs a real lookup without re-querying every row on every boot.
--
-- NULL means provenance is unknown (rows written before this column existed);
-- the startup backfill re-derives those from their lat/lng exactly once and
-- stamps 'google'. After that they are never queried again.
--
-- Values: 'google'   — resolved from the geocoded address via the Google Time
--                      Zone API (exact, DST-correct for the real place).
--         'province' — only the province map could answer (a fallback; the
--                      startup backfill retries these while coordinates exist).
--         NULL       — unknown provenance.
ALTER TABLE stylers
    ADD COLUMN time_zone_source VARCHAR(16) NULL AFTER time_zone;
