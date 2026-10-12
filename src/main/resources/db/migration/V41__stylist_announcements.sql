-- Admin announcements can go to stylists as well as customers.
--
-- user_notifications.recipient_role says whose inbox a row is in. CUSTOMER rows
-- (every row before this change) belong to user_id, and styler_id only names
-- the stylist the notice is about. STYLIST rows belong to styler_id and carry an
-- empty user_id, so no customer inbox query can ever match them.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_notifications' AND COLUMN_NAME = 'recipient_role'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE user_notifications ADD COLUMN recipient_role VARCHAR(16) NOT NULL DEFAULT ''CUSTOMER''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- The stylist inbox reads by (styler_id, recipient_role).
SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_notifications' AND INDEX_NAME = 'idx_user_notifications_styler_inbox'
);
SET @sql := IF(@index_exists = 0,
    'CREATE INDEX idx_user_notifications_styler_inbox ON user_notifications (styler_id, recipient_role)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Who an announcement went to: CUSTOMERS (every row before this change) or STYLISTS.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'announcements' AND COLUMN_NAME = 'audience'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE announcements ADD COLUMN audience VARCHAR(16) NOT NULL DEFAULT ''CUSTOMERS''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
