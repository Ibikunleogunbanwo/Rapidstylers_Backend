-- V16: make a database built by Flyway match production.
--
-- Production was built by ddl-auto=update, then V1..V15. A fresh database (CI,
-- a new laptop, a rebuilt VPS) gets its tables from V13 instead. Comparing the
-- two on 2026-10-10 (live dump vs. a Flyway-only build) found that V13 left out:
--   * four unique keys the entities declare, so on a fresh database the same
--     booking could get two reviews, a user two loyalty accounts, and so on;
--   * the column sizes and default that V4, V6, V9 and V10 chose, because those
--     migrations skip tables that do not exist yet and V13 then used 255.
--
-- Every statement checks first, so on production (which already has all of
-- this) V16 changes nothing. The legacy columns production still carries
-- (extra_travel_rate_per_km, max_service_distance_km) stay: no destructive DDL.

-- Unique keys, named as in the entities' @UniqueConstraint.
SET @missing := (SELECT COUNT(*) = 0 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'uk_review_booking');
SET @sql := IF(@missing, 'ALTER TABLE reviews ADD CONSTRAINT uk_review_booking UNIQUE (booking_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @missing := (SELECT COUNT(*) = 0 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'loyalty_accounts' AND INDEX_NAME = 'uk_loyalty_user');
SET @sql := IF(@missing, 'ALTER TABLE loyalty_accounts ADD CONSTRAINT uk_loyalty_user UNIQUE (user_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @missing := (SELECT COUNT(*) = 0 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'referrals' AND INDEX_NAME = 'uk_referral_referred_user');
SET @sql := IF(@missing, 'ALTER TABLE referrals ADD CONSTRAINT uk_referral_referred_user UNIQUE (referred_user_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @missing := (SELECT COUNT(*) = 0 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'saved_stylists' AND INDEX_NAME = 'uk_saved_stylist_user_styler');
SET @sql := IF(@missing, 'ALTER TABLE saved_stylists ADD CONSTRAINT uk_saved_stylist_user_styler UNIQUE (user_id, styler_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Column sizes and default, as V4, V6, V9 and V10 define them.
SET @differs := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_accounts' AND COLUMN_NAME = 'registration_method'
                   AND COLUMN_TYPE <> 'varchar(16)');
SET @sql := IF(@differs > 0, 'ALTER TABLE user_accounts MODIFY registration_method VARCHAR(16) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @differs := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'otp_codes' AND COLUMN_NAME = 'followup_stage'
                   AND NOT (COLUMN_DEFAULT <=> '0'));
SET @sql := IF(@differs > 0, 'ALTER TABLE otp_codes MODIFY followup_stage INT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @differs := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stylers' AND COLUMN_NAME = 'base_travel_fee'
                   AND COLUMN_TYPE <> 'varchar(64)');
SET @sql := IF(@differs > 0, 'ALTER TABLE stylers MODIFY base_travel_fee VARCHAR(64) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @differs := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'base_travel_fee'
                   AND COLUMN_TYPE <> 'varchar(64)');
SET @sql := IF(@differs > 0, 'ALTER TABLE appointments MODIFY base_travel_fee VARCHAR(64) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @differs := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'styler_note'
                   AND COLUMN_TYPE <> 'varchar(500)');
SET @sql := IF(@differs > 0, 'ALTER TABLE appointments MODIFY styler_note VARCHAR(500) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
