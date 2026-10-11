-- Gallery moderation: each portfolio photo is PENDING until an admin approves
-- or rejects it, with the admin's note and when and by whom it was decided.
-- Photos already on the site were public before moderation existed, so they
-- start APPROVED rather than vanishing from the gallery.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'portfolios' AND COLUMN_NAME = 'moderation_status'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE portfolios ADD COLUMN moderation_status VARCHAR(20) NULL, ADD COLUMN moderation_note VARCHAR(500) NULL, ADD COLUMN moderated_by VARCHAR(100) NULL, ADD COLUMN moderated_at DATETIME NULL, ADD INDEX idx_portfolios_moderation_status (moderation_status)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
UPDATE portfolios SET moderation_status = 'APPROVED' WHERE moderation_status IS NULL;
