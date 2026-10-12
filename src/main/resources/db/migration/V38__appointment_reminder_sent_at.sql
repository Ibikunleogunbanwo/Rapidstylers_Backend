-- When the automatic "your appointment is tomorrow" email was claimed for this
-- booking. NULL = no reminder yet. The reminder job sets it with a conditional
-- UPDATE (... WHERE reminder_sent_at IS NULL), so two app instances or a retry
-- can never both send. Nullable and additive: existing rows stay untouched.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'reminder_sent_at'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE appointments ADD COLUMN reminder_sent_at DATETIME NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- The job's scan: accepted bookings, not yet reminded, in a two-to-three day window.
SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND INDEX_NAME = 'idx_appointments_reminder_scan'
);
SET @sql := IF(@index_exists = 0,
    'CREATE INDEX idx_appointments_reminder_scan ON appointments (status, appointment_date_value)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
