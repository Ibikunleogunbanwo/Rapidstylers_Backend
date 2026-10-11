-- The admin's decision on a customer's dispute, so the customer (and stylist)
-- can read it next to the booking. Both nullable: undisputed and older
-- bookings have none.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'service_dispute_resolution_note'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE appointments ADD COLUMN service_dispute_resolution_note VARCHAR(1000) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'service_dispute_resolved_at'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE appointments ADD COLUMN service_dispute_resolved_at DATETIME NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
