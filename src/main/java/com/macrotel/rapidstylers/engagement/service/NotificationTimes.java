package com.macrotel.rapidstylers.engagement.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Turns a notification's stored createdAt into an instant a browser can place.
 *
 * NotificationEntity writes createdAt with LocalDateTime.now(), so it is wall
 * time in the JVM's default zone with no zone attached (UTC in the production
 * image, which sets no TZ). Responses keep that string as createdAt and add
 * createdAtUtc, the same moment as an ISO-8601 instant ending in Z, read in the
 * zone the value was written in.
 */
public final class NotificationTimes {

    private static final DateTimeFormatter STORED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private NotificationTimes() {
    }

    /** createdAt as an instant ("2026-10-12T15:04:05Z"), or null when it is missing or unreadable. */
    public static String utc(String createdAt) {
        return utc(createdAt, ZoneId.systemDefault());
    }

    static String utc(String createdAt, ZoneId writtenIn) {
        if (createdAt == null || createdAt.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(createdAt.trim(), STORED).atZone(writtenIn).toInstant().toString();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
