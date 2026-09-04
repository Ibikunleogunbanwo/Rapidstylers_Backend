package com.macrotel.rapidstylers.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ensures every table uses InnoDB so that @Transactional rollback,
 * unique-constraint enforcement and SELECT ... FOR UPDATE actually work.
 * The legacy schema was created with MyISAM, which silently auto-commits
 * every statement: a booking whose slot-lock insert collides would leave its
 * appointment row committed (a phantom booking) because nothing can be rolled
 * back, and pessimistic locks are ignored. The same MyISAM hazard undermines
 * refund-exactly-once (refunds, payout_reversals), transactional outbox writes
 * (outbox_events), refresh-token revocation (refresh_tokens), the stylist row
 * lock (stylers), and — beyond the transaction-critical set — account creation,
 * OTP verification, login attempts, audit and notification writes.
 *
 * <p>Instead of a fixed list, the reconciler discovers every non-InnoDB base
 * table in the current schema at startup and converts it, so legacy tables are
 * caught now and any that appear later are handled without a code change. Fresh
 * databases already create InnoDB tables (Hibernate's dialect default), so on
 * those this is a no-op. Never fails startup.
 *
 * <p>Logging is deliberately quiet: a fresh CI/test database boot converts the
 * whole legacy set in one burst, so the per-table detail is DEBUG and the only
 * INFO line is a single summary with the converted count (the ops-relevant
 * signal). Repeated contexts on the same schema convert nothing and stay
 * silent.
 */
@Component
public class InnoDbReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InnoDbReconciler.class);

    private final JdbcTemplate jdbcTemplate;

    public InnoDbReconciler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> legacyTables = jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() "
                            + "AND table_type = 'BASE TABLE' "
                            + "AND engine IS NOT NULL "
                            + "AND lower(engine) <> 'innodb' "
                            + "ORDER BY table_name",
                    String.class);
            for (String table : legacyTables) {
                ensureInnoDb(table);
            }
            if (!legacyTables.isEmpty()) {
                log.info("InnoDB reconciler converted {} legacy table(s) to InnoDB.", legacyTables.size());
            }
        } catch (Exception ex) {
            // Best-effort reconciliation only; never block or fail startup.
            log.warn("InnoDB reconciliation skipped — some tables may stay on MyISAM, "
                    + "where transactions and row locks do not work. Error: {}", ex.getMessage());
        }
    }

    private void ensureInnoDb(String table) {
        String engine = jdbcTemplate.queryForObject(
                "SELECT engine FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                String.class, table);
        if (engine == null || "InnoDB".equalsIgnoreCase(engine)) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE `" + table + "` ENGINE=InnoDB");
        log.debug("Converted table {} from {} to InnoDB so transactional rollback, "
                + "row locks and unique constraints work correctly.", table, engine);
    }
}
