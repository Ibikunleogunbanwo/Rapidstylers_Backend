-- Promo codes and the bookings that used them. Amounts are in cents (CAD);
-- times are UTC. A redemption is APPLIED while its booking stands and
-- RELEASED when the booking is declined or cancelled, so the code's limits
-- count only bookings that went ahead.
CREATE TABLE IF NOT EXISTS promo_codes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(40) NOT NULL,
    description VARCHAR(300) NULL,
    discount_type VARCHAR(10) NOT NULL,
    discount_value BIGINT NOT NULL,
    max_discount_cents BIGINT NULL,
    min_booking_cents BIGINT NULL,
    starts_at DATETIME NULL,
    expires_at DATETIME NULL,
    max_redemptions INT NULL,
    max_per_customer INT NOT NULL,
    first_booking_only BIT(1) NOT NULL,
    active BIT(1) NOT NULL,
    created_by VARCHAR(100) NULL,
    created_at DATETIME NULL,
    updated_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_promo_codes_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS promo_redemptions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    promo_id BIGINT NOT NULL,
    code VARCHAR(40) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    appointment_id VARCHAR(100) NOT NULL,
    discount_cents BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_promo_redemptions_appointment (appointment_id),
    KEY idx_promo_redemptions_promo (promo_id, status),
    KEY idx_promo_redemptions_user (user_id, promo_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The code a booking used and what it took off, kept on the booking itself so
-- payouts and receipts read it without a join. The platform pays for the
-- discount: the stylist is paid on the full price.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'promo_discount_cents'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE appointments ADD COLUMN promo_code VARCHAR(40) NULL, ADD COLUMN promo_discount_cents BIGINT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
