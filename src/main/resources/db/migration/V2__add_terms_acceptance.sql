-- V2: Record when an account accepted the RapidStylers Terms and Conditions.
-- Existing accounts remain valid and receive NULL until they explicitly accept again.

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_accounts' AND COLUMN_NAME = 'terms_accepted_at');
SET @sql := IF(@exists = 0, 'ALTER TABLE user_accounts ADD COLUMN terms_accepted_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stylers' AND COLUMN_NAME = 'terms_accepted_at');
SET @sql := IF(@exists = 0, 'ALTER TABLE stylers ADD COLUMN terms_accepted_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
