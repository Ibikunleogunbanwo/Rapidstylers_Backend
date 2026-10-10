-- Why a completed booking's stylist payout is waiting instead of leaving:
-- NO_PAYOUT_ACCOUNT while the stylist has not finished Stripe payout setup.
-- It is cleared, and the payout sent, once their account can receive payouts.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'payout_hold_reason'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE appointments ADD COLUMN payout_hold_reason VARCHAR(30) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
