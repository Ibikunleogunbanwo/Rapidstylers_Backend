package com.macrotel.rapidstylers.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ensures the core transactional tables use InnoDB so that @Transactional
 * rollback, unique-constraint enforcement and SELECT ... FOR UPDATE actually
 * work. The legacy schema was created with MyISAM, which silently auto-commits
 * every statement: a booking whose slot-lock insert collides would leave its
 * appointment row committed (a phantom booking) because nothing can be rolled
 * back, and pessimistic locks are ignored. The same MyISAM hazard undermines
 * refund-exactly-once (refunds, payout_reversals), transactional outbox writes
 * (outbox_events), refresh-token revocation (refresh_tokens) and the stylist
 * row lock (stylers). Fresh databases already create InnoDB tables (Hibernate's
 * dialect default), so on those this is a no-op; only legacy MyISAM tables are
 * converted. Never fails startup.
 */
@Component
public class InnoDbReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InnoDbReconciler.class);

    /**
     * Tables that participate in transactional flows and must be InnoDB for
     * rollback, row locking, and unique-constraint enforcement to be trustworthy.
     * Appointments and slot locks guard the booking race; refresh tokens, refunds,
     * payout reversals, outbox events and stylers carry the other write-critical
     * flows. (The wider legacy schema is still MyISAM; this list is the
     * transaction-critical set.)
     */
    private static final List<String> TRANSACTIONAL_TABLES = List.of(
            "appointments",
            "booking_slot_locks",
            "refresh_tokens",
            "refunds",
            "payout_reversals",
            "outbox_events",
            "stylers");

    private final JdbcTemplate jdbcTemplate;

    public InnoDbReconciler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (String table : TRANSACTIONAL_TABLES) {
                ensureInnoDb(table);
            }
        } catch (Exception ex) {
            // Best-effort reconciliation only; never block or fail startup.
            log.warn("InnoDB reconciliation skipped — some booking tables may stay on "
                    + "MyISAM, where transactions and row locks do not work. Error: {}", ex.getMessage());
        }
    }

    private void ensureInnoDb(String table) {
        String engine = jdbcTemplate.queryForObject(
                "SELECT engine FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                String.class, table);
        if (engine == null) {
            // Table not yet created (fresh database): Hibernate creates it as InnoDB.
            return;
        }
        if ("InnoDB".equalsIgnoreCase(engine)) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE `" + table + "` ENGINE=InnoDB");
        log.info("Converted table {} from {} to InnoDB so transactional rollback, "
                + "row locks and unique constraints work correctly.", table, engine);
    }
}