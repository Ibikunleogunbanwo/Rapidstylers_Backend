-- A stylist's cover photo: the wide banner on their public profile, separate
-- from the profile photo shown in search results. Nullable: none until uploaded.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stylers' AND COLUMN_NAME = 'cover_image_url'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE stylers ADD COLUMN cover_image_url VARCHAR(500) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
