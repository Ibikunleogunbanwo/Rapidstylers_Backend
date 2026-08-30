-- V9: Flat home-visit fee (free radius + flat fee beyond) replaces per-km pricing.
-- Adds the base_travel_fee snapshot column; the legacy extra_travel_rate_per_km /
-- max_service_distance_km columns are left in place (no longer mapped by the app)
-- to avoid destructive DDL.
-- Guarded ALTERs are safe for databases already updated by JPA and for fresh
-- databases (tables may not exist yet — Hibernate creates them after Flyway).

SET @table_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stylers');
SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stylers' AND COLUMN_NAME = 'base_travel_fee');
SET @sql := IF(@table_exists > 0 AND @column_exists = 0, 'ALTER TABLE stylers ADD COLUMN base_travel_fee VARCHAR(64) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @table_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments');
SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'base_travel_fee');
SET @sql := IF(@table_exists > 0 AND @column_exists = 0, 'ALTER TABLE appointments ADD COLUMN base_travel_fee VARCHAR(64) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
