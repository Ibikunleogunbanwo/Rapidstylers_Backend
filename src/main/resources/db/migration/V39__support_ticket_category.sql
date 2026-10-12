-- What a support ticket is about, picked by the sender: BOOKING, PAYMENT,
-- ACCOUNT, TECHNICAL or OTHER. Every ticket written before the choice existed,
-- and any ticket sent by a client that does not send one, is OTHER.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'support_tickets' AND COLUMN_NAME = 'category'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE support_tickets ADD COLUMN category VARCHAR(16) NOT NULL DEFAULT ''OTHER''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE support_tickets SET category = 'OTHER' WHERE category IS NULL OR category = '';
