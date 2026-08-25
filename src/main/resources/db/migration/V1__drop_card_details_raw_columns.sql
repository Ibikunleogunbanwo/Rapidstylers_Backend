-- V1: Remove raw card data columns that pre-date the Stripe tokenization migration.
--
-- The app now stores only Stripe customer/payment-method references plus masked
-- display data (last4, brand, expiry) on card_details. The legacy card_number,
-- cvv and expiry_date columns must never come back.
--
-- Each drop is guarded by an information_schema existence check because the
-- column set differs per environment:
--   * live DB:      already dropped manually, this is a no-op
--   * fresh schema: JPA (ddl-auto=update) creates card_details from the entity,
--                   which has no raw columns, so this is a no-op
--   * legacy env:   columns still present, this drops them

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'card_details' AND COLUMN_NAME = 'card_number');
SET @sql := IF(@exists > 0, 'ALTER TABLE card_details DROP COLUMN card_number', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'card_details' AND COLUMN_NAME = 'cvv');
SET @sql := IF(@exists > 0, 'ALTER TABLE card_details DROP COLUMN cvv', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'card_details' AND COLUMN_NAME = 'expiry_date');
SET @sql := IF(@exists > 0, 'ALTER TABLE card_details DROP COLUMN expiry_date', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
