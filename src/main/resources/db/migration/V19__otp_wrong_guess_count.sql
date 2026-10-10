-- Each emailed code counts its own wrong guesses. After too many, the code is
-- closed (is_used = '2') and only a newly emailed code can verify the address,
-- however many IP addresses the guesses come from.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'otp_codes' AND COLUMN_NAME = 'failed_attempts'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE otp_codes ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
