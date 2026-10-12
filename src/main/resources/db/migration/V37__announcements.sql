-- Sent admin announcements, newest first on the admin Announcements screen.
-- recipient_email is NULL for an announcement to every active customer.
CREATE TABLE IF NOT EXISTS announcements (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(120) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    recipient_count INT NOT NULL,
    recipient_email VARCHAR(190) NULL,
    sent_at DATETIME NOT NULL,
    sent_by VARCHAR(190) NULL,
    PRIMARY KEY (id),
    KEY idx_announcements_sent_at (sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
