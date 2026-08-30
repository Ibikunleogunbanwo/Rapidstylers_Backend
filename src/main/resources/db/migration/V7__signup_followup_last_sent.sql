-- V7: last-sent timestamp for the abandoned-signup recovery campaign, so the
-- admin "Recovery campaigns" view can show when each stage's email went out.
-- Guarded ALTER, safe on fresh databases (table created by Hibernate after
-- Flyway) and on databases already updated by ddl-auto=update.

SET @table_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'otp_codes');
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'otp_codes' AND COLUMN_NAME = 'followup_updated_at');
SET @sql := IF(@table_exists > 0 AND @col_exists = 0,
    'ALTER TABLE otp_codes ADD COLUMN followup_updated_at DATETIME NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;