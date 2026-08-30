-- V10: Stylist decision note recorded at accept/decline (e.g. why a far booking
-- was accepted anyway or declined) for later review of their radius preference.
-- Guarded ALTER is safe for databases already updated by JPA and for fresh
-- databases (table may not exist yet — Hibernate creates it after Flyway).

SET @table_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments');
SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'styler_note');
SET @sql := IF(@table_exists > 0 AND @column_exists = 0, 'ALTER TABLE appointments ADD COLUMN styler_note VARCHAR(500) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;