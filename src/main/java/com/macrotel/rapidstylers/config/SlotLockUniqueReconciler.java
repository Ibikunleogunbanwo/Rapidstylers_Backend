package com.macrotel.rapidstylers.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Guarantees the database-level race guard on booking_slot_locks exists.
 *
 * The entity declares a unique constraint on (styler_id, appointment_date,
 * slot_start) — the "final database-level race guard" that stops two different
 * customers double-booking the same slot — but that guard only materializes when
 * Hibernate *creates* the table. Because schema migrations are managed by Flyway
 * (which runs before Hibernate) and booking_slot_locks is Hibernate-created,
 * ddl-auto=update never adds a *new* unique constraint to an already-existing
 * table. Pre-existing databases therefore silently lack the index, allowing
 * genuine double-booking. A Flyway migration would break on fresh databases
 * (table not yet created when migrations run), so this runner reconciles at
 * startup instead. It dedupes any historical duplicate lock rows, then creates
 * the unique index. Never fails startup: if reconciliation cannot run it logs a
 * warning; fresh databases already create the table with the constraint so this
 * is a no-op there.
 */
@Component
public class SlotLockUniqueReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SlotLockUniqueReconciler.class);

    private static final String TABLE = "booking_slot_locks";
    private static final String INDEX = "uk_booking_slot_styler_date_start";

    private final JdbcTemplate jdbcTemplate;

    public SlotLockUniqueReconciler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureRaceGuard();
        } catch (Exception ex) {
            // Best-effort reconciliation only; never block or fail startup.
            log.warn("Slot-lock unique guard reconciliation skipped — the booking_slot_locks "
                    + "race guard (unique index uk_booking_slot_styler_date_start) may be missing. "
                    + "Without it, two customers could book the same slot. Error: {}", ex.getMessage());
        }
    }

    private void ensureRaceGuard() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, TABLE);
        if (tableCount == null || tableCount == 0) {
            // Table not yet created (fresh database): Hibernate ddl-auto=update
            // will create it WITH the unique constraint, so nothing to do.
            return;
        }

        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? "
                        + "AND index_name = ? AND non_unique = 0",
                Integer.class, TABLE, INDEX);
        if (indexCount != null && indexCount > 0) {
            log.info("Slot-lock race guard present: unique index {} on {}", INDEX, TABLE);
            return;
        }

        // Historical duplicate lock rows (two appointments claiming the same slot)
        // would block index creation — drop the duplicates, keeping the oldest id.
        Integer removed = jdbcTemplate.update(
                "DELETE l1 FROM " + TABLE + " l1 JOIN " + TABLE + " l2 "
                        + "ON l1.styler_id = l2.styler_id "
                        + "AND l1.appointment_date = l2.appointment_date "
                        + "AND l1.slot_start = l2.slot_start AND l1.id < l2.id");

        // styler_id is VARCHAR(255) utf8mb4 (up to 1020 bytes) — a full-column
        // unique index would exceed MySQL's 1000-byte MyISAM key limit (this is why
        // Hibernate's @UniqueConstraint never materialized, even on fresh databases).
        // Bound the prefix instead: 200 chars = 800 bytes, +date(3) +time(3) = 806,
        // safely under every MySQL key limit and effectively unique for these ids.
        jdbcTemplate.execute("CREATE UNIQUE INDEX " + INDEX + " ON " + TABLE
                + " (styler_id(200), appointment_date, slot_start)");

        log.info("Created slot-lock race guard: unique index {} on {} "
                + "(deduped {} historical duplicate lock row(s)). Two customers can no longer "
                + "book the same slot.", INDEX, TABLE, removed == null ? 0 : removed);
    }
}