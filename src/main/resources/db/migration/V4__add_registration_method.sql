-- V4: Registration method (EMAIL | GOOGLE) so each customer account is bound
-- to the surface it was created with:
--   - EMAIL  -> email/password sign-up only; cannot sign in with Google
--   - GOOGLE -> Google identity only; cannot sign in with email/password
-- The column is nullable: rows inserted without a method (legacy accounts, or
-- any create path that predates the column) are treated as EMAIL-compatible and
-- allowed on both sign-in surfaces. Existing rows are backfilled to 'EMAIL'
-- because they predate Google sign-in. The guarded ALTER is safe on databases
-- already updated by Hibernate (ddl-auto=update); on a fresh database the table
-- is created by Hibernate after Flyway, so the column already exists there.

SET @table_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_accounts');
SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_accounts' AND COLUMN_NAME = 'registration_method');
SET @sql := IF(@table_exists > 0 AND @column_exists = 0,
    'ALTER TABLE user_accounts ADD COLUMN registration_method VARCHAR(16) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Backfill legacy rows (they predate Google sign-in, so they are email users).
SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_accounts' AND COLUMN_NAME = 'registration_method');
SET @sql := IF(@table_exists > 0 AND @column_exists > 0,
    'UPDATE user_accounts SET registration_method = ''EMAIL'' WHERE registration_method IS NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;