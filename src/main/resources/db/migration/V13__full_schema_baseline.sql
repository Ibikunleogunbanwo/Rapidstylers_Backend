-- V13 — full schema baseline (Flyway-only schema authority)
--
-- Purpose: with ddl-auto flipped from 'update' to 'validate', Flyway becomes the
-- single schema owner. This migration defines the complete entity schema (28
-- tables) exactly as Hibernate produced it under ddl-auto=update, so that:
--   * fresh databases (CI's MySQL container, new dev machines, re-provisioned VPS)
--     get the full schema from Flyway before Hibernate validates it;
--   * existing databases (VPS, local) already have every table (Hibernate created
--     them), so every statement is IF NOT EXISTS and V13 applies as a no-op;
--   * booking_slot_locks carries its race-guard unique index
--     uk_booking_slot_styler_date_start inline — the guarantee the deleted
--     SlotLockUniqueReconciler enforced at boot.
--
-- Source of truth: entities under src/main/java/com/macrotel/rapidstylers/entity/,
-- materialized into a scratch database and dumped. Any future entity change must
-- ship as the next numbered migration — ddl-auto no longer patches the schema.
--
-- NOTE: integrity is enforced in the service layer (no FK constraints exist
-- between tables — Hibernate created none and none are added here).

-- admin_accounts
CREATE TABLE IF NOT EXISTS `admin_accounts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(190) NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `role` varchar(32) NOT NULL DEFAULT 'ADMIN',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_admin_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- appointments
CREATE TABLE IF NOT EXISTS `appointments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `appointment_date` varchar(255) DEFAULT NULL,
  `appointment_date_value` date DEFAULT NULL,
  `appointment_end_time` time DEFAULT NULL,
  `appointment_id` varchar(255) DEFAULT NULL,
  `appointment_start_time` time DEFAULT NULL,
  `arrival_time` varchar(255) DEFAULT NULL,
  `base_travel_fee` varchar(255) DEFAULT NULL,
  `billable_travel_km` double DEFAULT NULL,
  `commission_percent` double DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `created_at` varchar(255) DEFAULT NULL,
  `duration_minutes` int DEFAULT NULL,
  `included_travel_km` double DEFAULT NULL,
  `no_of_people` varchar(255) DEFAULT NULL,
  `payment_amount` varchar(255) DEFAULT NULL,
  `payment_authorization_due_at` datetime DEFAULT NULL,
  `payment_failure_code` varchar(255) DEFAULT NULL,
  `payment_intent_id` varchar(255) DEFAULT NULL,
  `payment_status` varchar(255) DEFAULT NULL,
  `platform_fee_cents` bigint DEFAULT NULL,
  `price` varchar(255) DEFAULT NULL,
  `service_price` varchar(255) DEFAULT NULL,
  `service_time` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `stripe_transfer_id` varchar(255) DEFAULT NULL,
  `styler_id` varchar(255) DEFAULT NULL,
  `styler_note` varchar(255) DEFAULT NULL,
  `stylist_share_cents` bigint DEFAULT NULL,
  `sub_service_id` varchar(255) DEFAULT NULL,
  `travel_distance_km` double DEFAULT NULL,
  `travel_fee` varchar(255) DEFAULT NULL,
  `user_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- audit_logs
CREATE TABLE IF NOT EXISTS `audit_logs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action` varchar(255) NOT NULL,
  `actor_id` varchar(255) NOT NULL,
  `actor_role` varchar(255) NOT NULL,
  `created_at` varchar(255) NOT NULL,
  `details` varchar(2000) DEFAULT NULL,
  `resource_id` varchar(255) NOT NULL,
  `resource_type` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- availability
CREATE TABLE IF NOT EXISTS `availability` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` varchar(255) DEFAULT NULL,
  `day_of_week` varchar(255) DEFAULT NULL,
  `end_time` varchar(255) DEFAULT NULL,
  `start_time` varchar(255) DEFAULT NULL,
  `styler_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- availability_exceptions
CREATE TABLE IF NOT EXISTS `availability_exceptions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `blocked_date` varchar(255) DEFAULT NULL,
  `created_at` varchar(255) DEFAULT NULL,
  `reason` varchar(255) DEFAULT NULL,
  `styler_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- blog_posts
CREATE TABLE IF NOT EXISTS `blog_posts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `author` varchar(255) DEFAULT NULL,
  `category` varchar(255) DEFAULT NULL,
  `content` text,
  `image_url` varchar(2048) DEFAULT NULL,
  `inserted_dt` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `title` varchar(255) DEFAULT NULL,
  `updated_dt` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- booking_slot_locks
CREATE TABLE IF NOT EXISTS `booking_slot_locks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `appointment_date` date NOT NULL,
  `appointment_id` varchar(255) NOT NULL,
  `slot_start` time NOT NULL,
  `styler_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_booking_slot_styler_date_start` (`styler_id`(200),`appointment_date`,`slot_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- card_details
CREATE TABLE IF NOT EXISTS `card_details` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `brand` varchar(255) DEFAULT NULL,
  `card_name` varchar(255) DEFAULT NULL,
  `exp_month` bigint DEFAULT NULL,
  `exp_year` bigint DEFAULT NULL,
  `last4` varchar(255) DEFAULT NULL,
  `stripe_customer_id` varchar(255) DEFAULT NULL,
  `stripe_payment_method_id` varchar(255) DEFAULT NULL,
  `updated_date` varchar(255) DEFAULT NULL,
  `user_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- identifications
CREATE TABLE IF NOT EXISTS `identifications` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `identification_name` varchar(255) DEFAULT NULL,
  `inserted_date` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- login_attempts
CREATE TABLE IF NOT EXISTS `login_attempts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_id` varchar(100) DEFAULT NULL,
  `account_type` varchar(30) NOT NULL,
  `created_at` datetime NOT NULL,
  `email_address` varchar(255) NOT NULL,
  `failure_reason` varchar(100) DEFAULT NULL,
  `ip_address` varchar(100) DEFAULT NULL,
  `success` bit(1) NOT NULL,
  `user_agent` varchar(512) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- loyalty_accounts
CREATE TABLE IF NOT EXISTS `loyalty_accounts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` varchar(255) NOT NULL,
  `points` int NOT NULL,
  `referral_code` varchar(255) NOT NULL,
  `updated_at` varchar(255) NOT NULL,
  `user_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- otp_codes
CREATE TABLE IF NOT EXISTS `otp_codes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(255) DEFAULT NULL,
  `email_address` varchar(255) DEFAULT NULL,
  `followup_stage` int DEFAULT NULL,
  `followup_updated_at` datetime DEFAULT NULL,
  `inserted_dt` varchar(255) DEFAULT NULL,
  `is_used` varchar(255) DEFAULT NULL,
  `purpose` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- outbox_events
CREATE TABLE IF NOT EXISTS `outbox_events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `aggregate_id` varchar(100) NOT NULL,
  `aggregate_type` varchar(50) NOT NULL,
  `attempts` int NOT NULL,
  `created_at` datetime NOT NULL,
  `event_id` varchar(36) NOT NULL,
  `event_type` varchar(64) NOT NULL,
  `last_error` longtext,
  `next_attempt_at` datetime NOT NULL,
  `payload` longtext NOT NULL,
  `published_at` datetime DEFAULT NULL,
  `status` varchar(20) NOT NULL,
  `topic` varchar(120) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_7ba1uqwbn85u1g6jg4ja1tk6k` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- payout_reversals
CREATE TABLE IF NOT EXISTS `payout_reversals` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` varchar(20) DEFAULT NULL,
  `appointment_id` varchar(100) NOT NULL,
  `attempts` int NOT NULL,
  `created_at` varchar(30) NOT NULL,
  `last_error` varchar(2000) DEFAULT NULL,
  `next_attempt_at` datetime NOT NULL,
  `reversal_id` varchar(32) NOT NULL,
  `reversed_at` varchar(30) DEFAULT NULL,
  `status` varchar(20) NOT NULL,
  `stripe_reversal_id` varchar(255) DEFAULT NULL,
  `transfer_id` varchar(100) NOT NULL,
  `version` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_8v52u6v5v5y8c7g3eterktven` (`reversal_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- platform_settings
CREATE TABLE IF NOT EXISTS `platform_settings` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `setting_key` varchar(255) DEFAULT NULL,
  `setting_value` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- portfolios
CREATE TABLE IF NOT EXISTS `portfolios` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `category` varchar(255) DEFAULT NULL,
  `created_at` varchar(255) DEFAULT NULL,
  `image_url` varchar(255) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `styler_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- referrals
CREATE TABLE IF NOT EXISTS `referrals` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` varchar(255) NOT NULL,
  `referral_code` varchar(255) NOT NULL,
  `referred_user_id` varchar(255) NOT NULL,
  `referrer_user_id` varchar(255) NOT NULL,
  `status` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- refresh_tokens
CREATE TABLE IF NOT EXISTS `refresh_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_id` varchar(64) NOT NULL,
  `created_at` datetime NOT NULL,
  `expires_at` datetime NOT NULL,
  `family_id` varchar(255) NOT NULL,
  `revoked` bit(1) NOT NULL,
  `role` varchar(64) NOT NULL,
  `token_hash` varchar(128) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_o2mlirhldriil2y7krapq4frt` (`token_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- refunds
CREATE TABLE IF NOT EXISTS `refunds` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` varchar(20) NOT NULL,
  `appointment_id` varchar(100) NOT NULL,
  `completed_at` varchar(30) DEFAULT NULL,
  `created_at` varchar(30) NOT NULL,
  `created_by` varchar(100) NOT NULL,
  `failure_code` varchar(2000) DEFAULT NULL,
  `payment_intent_id` varchar(255) DEFAULT NULL,
  `reason` varchar(1000) DEFAULT NULL,
  `refund_id` varchar(32) NOT NULL,
  `status` varchar(20) NOT NULL,
  `stripe_refund_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_9985dy9h3laa0hruo2ip09oeb` (`refund_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- reviews
CREATE TABLE IF NOT EXISTS `reviews` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `booking_id` varchar(255) DEFAULT NULL,
  `created_at` varchar(255) DEFAULT NULL,
  `message` varchar(255) DEFAULT NULL,
  `moderation_status` varchar(255) NOT NULL,
  `rating_score` int NOT NULL,
  `styler_id` varchar(255) DEFAULT NULL,
  `user_id` varchar(255) DEFAULT NULL,
  `user_name` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- saved_stylists
CREATE TABLE IF NOT EXISTS `saved_stylists` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` varchar(255) NOT NULL,
  `styler_id` varchar(255) NOT NULL,
  `user_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- service_types
CREATE TABLE IF NOT EXISTS `service_types` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `description` varchar(255) DEFAULT NULL,
  `inserted_dt` varchar(255) DEFAULT NULL,
  `service_image_url` varchar(255) DEFAULT NULL,
  `service_name` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- stylers
CREATE TABLE IF NOT EXISTS `stylers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(255) DEFAULT NULL,
  `base_travel_fee` varchar(255) DEFAULT NULL,
  `business_address` varchar(255) DEFAULT NULL,
  `business_name` varchar(255) DEFAULT NULL,
  `city` varchar(255) DEFAULT NULL,
  `connect_disabled_reason` varchar(255) DEFAULT NULL,
  `connect_onboarding_status` varchar(255) DEFAULT NULL,
  `country` varchar(255) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `email_address` varchar(255) DEFAULT NULL,
  `firstname` varchar(255) DEFAULT NULL,
  `identification_id` varchar(255) DEFAULT NULL,
  `identification_image_url` varchar(255) DEFAULT NULL,
  `included_travel_km` double DEFAULT NULL,
  `inserted_dt` varchar(255) DEFAULT NULL,
  `is_online` varchar(255) DEFAULT NULL,
  `lastname` varchar(255) DEFAULT NULL,
  `latitude` double DEFAULT NULL,
  `longitude` double DEFAULT NULL,
  `password` varchar(255) DEFAULT NULL,
  `phone_number` varchar(255) DEFAULT NULL,
  `postal_code` varchar(255) DEFAULT NULL,
  `profile_image_url` varchar(255) DEFAULT NULL,
  `province` varchar(255) DEFAULT NULL,
  `service_type_id` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `street_address` varchar(255) DEFAULT NULL,
  `stripe_connect_account_id` varchar(255) DEFAULT NULL,
  `styler_id` varchar(255) DEFAULT NULL,
  `terms_accepted_at` datetime DEFAULT NULL,
  `unit` varchar(255) DEFAULT NULL,
  `verification_status` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- sub_services
CREATE TABLE IF NOT EXISTS `sub_services` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` varchar(255) DEFAULT NULL,
  `duration_minutes` int DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `price` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `styler_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- support_tickets
CREATE TABLE IF NOT EXISTS `support_tickets` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `admin_response` varchar(2000) DEFAULT NULL,
  `created_at` varchar(255) NOT NULL,
  `message` varchar(2000) NOT NULL,
  `status` varchar(255) NOT NULL,
  `subject` varchar(255) NOT NULL,
  `updated_at` varchar(255) NOT NULL,
  `user_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- user_accounts
CREATE TABLE IF NOT EXISTS `user_accounts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(255) DEFAULT NULL,
  `country` varchar(255) DEFAULT NULL,
  `email_address` varchar(255) DEFAULT NULL,
  `firstname` varchar(255) DEFAULT NULL,
  `inserted_dt` varchar(255) DEFAULT NULL,
  `lastname` varchar(255) DEFAULT NULL,
  `notify_saved_availability` bit(1) DEFAULT NULL,
  `notify_saved_price` bit(1) DEFAULT NULL,
  `notify_saved_verification` bit(1) DEFAULT NULL,
  `password` varchar(255) DEFAULT NULL,
  `phone_number` varchar(255) DEFAULT NULL,
  `registration_method` varchar(255) DEFAULT NULL,
  `state` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `terms_accepted_at` datetime DEFAULT NULL,
  `user_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- user_feedbacks
CREATE TABLE IF NOT EXISTS `user_feedbacks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email_address` varchar(255) DEFAULT NULL,
  `feed_back_type` varchar(255) DEFAULT NULL,
  `inserted_dt` varchar(255) DEFAULT NULL,
  `message` varchar(255) DEFAULT NULL,
  `user_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- user_notifications
CREATE TABLE IF NOT EXISTS `user_notifications` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` varchar(255) NOT NULL,
  `message` varchar(1000) NOT NULL,
  `is_read` bit(1) NOT NULL,
  `styler_id` varchar(255) NOT NULL,
  `title` varchar(255) NOT NULL,
  `type` varchar(255) NOT NULL,
  `user_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

