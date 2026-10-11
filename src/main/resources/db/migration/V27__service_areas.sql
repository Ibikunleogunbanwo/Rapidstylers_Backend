-- The provinces and cities RapidStylers serves. A row with no city covers the
-- whole province. While the table has no active rows, everywhere is served,
-- so this changes nothing until an admin adds the first area.
CREATE TABLE IF NOT EXISTS service_areas (
    id BIGINT NOT NULL AUTO_INCREMENT,
    province VARCHAR(60) NOT NULL,
    city VARCHAR(100) NULL,
    area_key VARCHAR(170) NOT NULL,
    active BIT(1) NOT NULL,
    note VARCHAR(500) NULL,
    updated_by VARCHAR(100) NULL,
    updated_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_service_areas_area_key (area_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
