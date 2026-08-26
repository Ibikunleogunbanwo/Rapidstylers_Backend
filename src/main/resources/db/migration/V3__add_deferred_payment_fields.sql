-- V3: Deferred payment authorization state for future appointments.
-- The guarded ALTER statements keep this migration safe for databases already
-- updated by JPA in development.

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'payment_authorization_due_at');
SET @sql := IF(@exists = 0, 'ALTER TABLE appointments ADD COLUMN payment_authorization_due_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'payment_failure_code');
SET @sql := IF(@exists = 0, 'ALTER TABLE appointments ADD COLUMN payment_failure_code VARCHAR(64) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'stripe_transfer_id');
SET @sql := IF(@exists = 0, 'ALTER TABLE appointments ADD COLUMN stripe_transfer_id VARCHAR(255) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
