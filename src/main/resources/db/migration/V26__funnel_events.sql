-- Sign-up funnel: one row per person per step (visit, sign-up started, email
-- verified, account created, first booking). The person is a one-way hash of
-- a browser id, email or account id, never the raw value. dedupe_key makes a
-- repeat of the same step by the same person (same day for visits and
-- sign-up starts, ever for the rest) a no-op.
CREATE TABLE IF NOT EXISTS funnel_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    step VARCHAR(30) NOT NULL,
    role VARCHAR(20) NULL,
    subject VARCHAR(64) NOT NULL,
    day_key VARCHAR(10) NOT NULL,
    occurred_at DATETIME NOT NULL,
    dedupe_key VARCHAR(120) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_funnel_events_dedupe (dedupe_key),
    KEY idx_funnel_events_day (day_key, step)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
