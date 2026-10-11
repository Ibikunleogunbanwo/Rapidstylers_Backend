-- Editable website content: FAQs, the homepage, SEO tags per page and email
-- templates. Each piece has a draft the admin edits and a published copy the
-- site reads; every publish is kept in site_content_versions so an older
-- version can be brought back. Bodies are JSON written by the server after
-- validation. Times are UTC.
CREATE TABLE IF NOT EXISTS site_content (
    id BIGINT NOT NULL AUTO_INCREMENT,
    content_key VARCHAR(120) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    draft_body MEDIUMTEXT NULL,
    published_body MEDIUMTEXT NULL,
    version INT NOT NULL,
    draft_updated_by VARCHAR(100) NULL,
    draft_updated_at DATETIME NULL,
    published_by VARCHAR(100) NULL,
    published_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_site_content_key (content_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS site_content_versions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    content_key VARCHAR(120) NOT NULL,
    version INT NOT NULL,
    body MEDIUMTEXT NOT NULL,
    published_by VARCHAR(100) NULL,
    published_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_site_content_versions (content_key, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
