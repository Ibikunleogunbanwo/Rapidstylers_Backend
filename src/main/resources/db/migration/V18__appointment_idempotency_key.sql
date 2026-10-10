-- A booking remembers the Idempotency-Key it was created with, plus a
-- fingerprint of what was asked for. Stored on the booking row itself, the key
-- is committed or rolled back together with the booking, so a retry can always
-- be answered with the original booking. The unique index makes a second
-- request with the same key wait for, and then lose to, the first.
-- Nullable: bookings made without a key (and every older booking) have none,
-- and MySQL lets any number of NULLs share a unique index.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'idempotency_key'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE appointments ADD COLUMN idempotency_key VARCHAR(100) NULL, ADD COLUMN idempotency_fingerprint VARCHAR(64) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND INDEX_NAME = 'uk_appointments_idempotency_key'
);
SET @sql := IF(@index_exists = 0,
    'CREATE UNIQUE INDEX uk_appointments_idempotency_key ON appointments (idempotency_key)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
