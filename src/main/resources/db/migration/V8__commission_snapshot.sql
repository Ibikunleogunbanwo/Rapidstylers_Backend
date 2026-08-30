-- V8: Snapshot the platform commission onto each booking and record the fee
-- breakdown at completion (gross / commission / net-to-stylist).
-- The guarded ALTER statements are safe for databases already updated by JPA
-- and for fresh databases (table may not exist yet — Hibernate creates it after
-- Flyway with these columns already included).

SET @table_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments');

SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'commission_percent');
SET @sql := IF(@table_exists > 0 AND @column_exists = 0, 'ALTER TABLE appointments ADD COLUMN commission_percent DOUBLE NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'platform_fee_cents');
SET @sql := IF(@table_exists > 0 AND @column_exists = 0, 'ALTER TABLE appointments ADD COLUMN platform_fee_cents BIGINT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'stylist_share_cents');
SET @sql := IF(@table_exists > 0 AND @column_exists = 0, 'ALTER TABLE appointments ADD COLUMN stylist_share_cents BIGINT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Raise the platform commission default from 10% to 12%. Only touches rows that
-- still carry the legacy default (10), so an admin-set custom value is preserved.
SET @settings_table := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_settings');
SET @legacy_row := (SELECT COUNT(*) FROM platform_settings
                WHERE setting_key = 'commission_percent' AND setting_value = '10');
SET @sql2 := IF(@settings_table > 0 AND @legacy_row > 0,
                "UPDATE platform_settings SET setting_value='12' WHERE setting_key='commission_percent' AND setting_value='10'",
                'SELECT 1');
PREPARE stmt2 FROM @sql2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;
