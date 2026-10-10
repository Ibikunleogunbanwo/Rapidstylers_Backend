-- Home-visit bookings now carry the address the professional travels to.
-- The server geocodes it and measures the trip itself, instead of trusting a
-- distance sent by the browser. Nullable: salon bookings have no visit address,
-- and older bookings predate the column.
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'service_address'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE appointments ADD COLUMN service_address VARCHAR(500) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
