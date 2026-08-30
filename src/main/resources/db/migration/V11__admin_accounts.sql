-- V11: Database-backed admin accounts. Replaces the env-only admin identity
-- with rows in admin_accounts (BCrypt password hashes, per-account enable/disable).
-- The initial admin is seeded from ADMIN_EMAIL/ADMIN_PASSWORD at startup when the
-- table is empty, so existing environments keep working with no data migration.
-- Guarded CREATE + ALTERs are safe for fresh databases and databases where
-- Hibernate already produced this table via ddl-auto=update.

SET @table_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_accounts');

SET @sql := IF(@table_exists = 0,
    'CREATE TABLE admin_accounts (
        id BIGINT NOT NULL AUTO_INCREMENT,
        email VARCHAR(190) NOT NULL,
        password_hash VARCHAR(255) NOT NULL,
        enabled TINYINT(1) NOT NULL DEFAULT 1,
        role VARCHAR(32) NOT NULL DEFAULT ''ADMIN'',
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id),
        UNIQUE KEY uq_admin_email (email)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;