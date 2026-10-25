-- Quote extras: reference photos on a request, a short message thread on each
-- quote, and services a stylist prices only by quote.

-- Up to three Cloudinary URLs the customer uploaded, one per line.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quotes' AND COLUMN_NAME = 'reference_photos'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE quotes ADD COLUMN reference_photos VARCHAR(1500) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- A service whose listed price is only a starting point: it is booked through a quote.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sub_services' AND COLUMN_NAME = 'price_on_quote'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE sub_services ADD COLUMN price_on_quote TINYINT(1) NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS quote_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    quote_ref VARCHAR(16) NOT NULL,
    sender_role VARCHAR(16) NOT NULL,
    body VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_quote_messages_quote (quote_ref, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
