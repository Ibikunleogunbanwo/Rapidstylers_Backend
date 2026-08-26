-- V2: Record when an account accepted the RapidStylers Terms and Conditions.
-- Existing accounts remain valid and receive NULL until they explicitly accept again.
--
-- Each ALTER is guarded by a TABLE-existence check first: on a fresh database the
-- tables do not exist yet (Hibernate ddl-auto=update creates them after Flyway,
-- already including these columns), so the migration must be a no-op there.

SET @table_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_accounts');
SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_accounts' AND COLUMN_NAME = 'terms_accepted_at');
SET @sql := IF(@table_exists > 0 AND @column_exists = 0, 'ALTER TABLE user_accounts ADD COLUMN terms_accepted_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @table_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stylers');
SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stylers' AND COLUMN_NAME = 'terms_accepted_at');
SET @sql := IF(@table_exists > 0 AND @column_exists = 0, 'ALTER TABLE stylers ADD COLUMN terms_accepted_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
