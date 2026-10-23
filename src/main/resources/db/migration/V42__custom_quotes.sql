-- Custom quotes: a customer describes a job that has no fixed price, the
-- stylist answers with a price and duration, and accepting books it at that
-- price. Money is kept in cents; the booking it becomes stores the usual
-- price strings.
CREATE TABLE IF NOT EXISTS quotes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    quote_ref VARCHAR(16) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    styler_id VARCHAR(64) NOT NULL,
    sub_service_id VARCHAR(32) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    preferred_dates VARCHAR(200) NULL,
    service_time VARCHAR(32) NULL,
    no_of_people INT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL,
    price_cents BIGINT NULL,
    duration_minutes INT NULL,
    stylist_note VARCHAR(500) NULL,
    valid_until DATETIME NULL,
    created_at DATETIME NOT NULL,
    quoted_at DATETIME NULL,
    closed_at DATETIME NULL,
    appointment_id VARCHAR(32) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quotes_quote_ref (quote_ref),
    KEY idx_quotes_user (user_id, status),
    KEY idx_quotes_styler (styler_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The booking an accepted quote became, so both sides can see where its price came from.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'quote_ref'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE appointments ADD COLUMN quote_ref VARCHAR(16) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
