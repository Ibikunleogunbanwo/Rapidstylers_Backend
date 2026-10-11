-- Newsletter: who agreed to receive it, addresses that must never be emailed,
-- the campaigns an admin writes, and one row per campaign per recipient so a
-- send can be paced, resumed and counted. Times are UTC.
CREATE TABLE IF NOT EXISTS newsletter_subscribers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    status VARCHAR(20) NOT NULL,
    role VARCHAR(20) NOT NULL,
    account_id VARCHAR(100) NULL,
    province VARCHAR(60) NULL,
    source VARCHAR(40) NULL,
    consent_text VARCHAR(500) NULL,
    consent_at DATETIME NULL,
    confirm_token VARCHAR(64) NULL,
    confirm_sent_at DATETIME NULL,
    unsubscribe_token VARCHAR(64) NOT NULL,
    unsubscribed_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_newsletter_subscribers_email (email),
    UNIQUE KEY uq_newsletter_subscribers_unsub (unsubscribe_token),
    UNIQUE KEY uq_newsletter_subscribers_confirm (confirm_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS email_suppressions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    reason VARCHAR(40) NOT NULL,
    note VARCHAR(300) NULL,
    created_by VARCHAR(100) NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_email_suppressions_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS newsletter_campaigns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    subject VARCHAR(150) NOT NULL,
    body MEDIUMTEXT NOT NULL,
    audience_role VARCHAR(20) NOT NULL,
    provinces VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL,
    scheduled_at DATETIME NULL,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    created_by VARCHAR(100) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_newsletter_campaigns_status (status, scheduled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS newsletter_deliveries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT NOT NULL,
    subscriber_id BIGINT NOT NULL,
    email VARCHAR(254) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error VARCHAR(500) NULL,
    queued_at DATETIME NULL,
    sent_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_newsletter_deliveries (campaign_id, subscriber_id),
    KEY idx_newsletter_deliveries_status (status, campaign_id),
    KEY idx_newsletter_deliveries_queued (queued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
