-- Which appointment locations each stylist offers: clients visiting their
-- salon or studio, home visits, or both, and how far they will travel for a
-- home visit (NULL: no limit). Existing stylists keep offering both, as today.
SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stylers' AND COLUMN_NAME = 'offers_salon_visits');
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE stylers ADD COLUMN offers_salon_visits BIT(1) NOT NULL DEFAULT b''1'', ADD COLUMN offers_home_visits BIT(1) NOT NULL DEFAULT b''1'', ADD COLUMN max_travel_km DOUBLE NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
