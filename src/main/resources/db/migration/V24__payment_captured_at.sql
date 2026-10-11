-- When the customer's card was actually charged (Stripe capture), in UTC.
-- Admin revenue reports count a booking's money on this moment. Nullable:
-- bookings that were never charged have none.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'payment_captured_at'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE appointments ADD COLUMN payment_captured_at DATETIME NULL, ADD INDEX idx_appointments_payment_captured_at (payment_captured_at)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
-- Bookings charged before this column existed get their best known moment:
-- completion, else the appointment start. Approximate (within hours), and
-- only for rows whose payment status shows the card was charged.
UPDATE appointments
SET payment_captured_at = COALESCE(completed_at, TIMESTAMP(appointment_date_value, appointment_start_time))
WHERE payment_captured_at IS NULL
  AND payment_status IN ('CAPTURED', 'REFUNDED', 'DISPUTED', 'DISPUTE_LOST');
