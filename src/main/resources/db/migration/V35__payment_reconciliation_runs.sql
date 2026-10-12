-- Every payment reconciliation run (the scheduled one and the admin's "run
-- now") keeps its report, so the admin Reconciliation tab can show the latest
-- result instead of "No reconciliation report yet" after an automatic run.
-- report_json is the same object the run returns.
CREATE TABLE IF NOT EXISTS payment_reconciliation_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ran_at DATETIME NOT NULL,
    trigger_source VARCHAR(16) NOT NULL,
    ok TINYINT(1) NOT NULL,
    issue_count INT NOT NULL,
    report_json LONGTEXT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_payment_reconciliation_runs_ran_at (ran_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
