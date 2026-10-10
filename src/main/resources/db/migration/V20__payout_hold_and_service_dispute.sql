-- The stylist's payout now waits a day after completion instead of leaving at once.
-- In that day the customer can confirm the appointment happened (which pays the
-- stylist straight away) or dispute it (which holds the payout for an admin).
-- All nullable: older bookings were paid at completion and have none of this.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'payout_due_at'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE appointments ADD COLUMN payout_due_at DATETIME NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'service_confirmed_at'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE appointments ADD COLUMN service_confirmed_at DATETIME NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'service_dispute_status'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE appointments ADD COLUMN service_dispute_status VARCHAR(20) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'service_dispute_reason'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE appointments ADD COLUMN service_dispute_reason VARCHAR(1000) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'service_disputed_at'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE appointments ADD COLUMN service_disputed_at DATETIME NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND INDEX_NAME = 'idx_appointments_payout_due_at'
);
SET @sql := IF(@index_exists = 0,
    'CREATE INDEX idx_appointments_payout_due_at ON appointments (payout_due_at)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
