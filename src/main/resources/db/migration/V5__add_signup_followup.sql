-- V5: signup-follow-up marker for abandoned registrations.
-- When a customer requests a sign-up OTP but never finishes creating the
-- account, the scheduled SignupFollowUpService emails a reminder. This column
-- records when that reminder was emitted so it is only sent once per signup
-- attempt. The guarded ALTER is safe on databases already updated by Hibernate
-- (ddl-auto=update); on a fresh database the table is created by Hibernate
-- after Flyway, so the column already exists there.

SET @table_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'otp_codes');
SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'otp_codes' AND COLUMN_NAME = 'followup_sent_at');
SET @sql := IF(@table_exists > 0 AND @column_exists = 0,
    'ALTER TABLE otp_codes ADD COLUMN followup_sent_at DATETIME NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;