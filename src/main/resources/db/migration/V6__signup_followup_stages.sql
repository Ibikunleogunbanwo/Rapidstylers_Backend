-- V6: stage the abandoned-signup recovery into a multi-email cron campaign
-- (24h reminder -> 7d corporate -> 14d nudge -> 1 month final), instead of a
-- single boolean follow-up flag. Tracks how far each signup attempt has been
-- emailed via followup_stage (0 = none, 1..4 = milestone reached).
-- The old followup_sent_at marker (V5, unshipped) is dropped.
-- Each guarded ALTER is safe on fresh databases (Hibernate creates the table
-- after Flyway) and on databases already updated by ddl-auto=update.

SET @table_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'otp_codes');
SET @col_sent := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'otp_codes' AND COLUMN_NAME = 'followup_sent_at');
SET @sql := IF(@table_exists > 0 AND @col_sent > 0,
    'ALTER TABLE otp_codes DROP COLUMN followup_sent_at', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_stage := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'otp_codes' AND COLUMN_NAME = 'followup_stage');
SET @sql := IF(@table_exists > 0 AND @col_stage = 0,
    'ALTER TABLE otp_codes ADD COLUMN followup_stage INT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;