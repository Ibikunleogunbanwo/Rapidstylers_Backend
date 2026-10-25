package com.macrotel.rapidstylers.booking.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import javax.persistence.*;
import lombok.Data;

/**
 * A customer's request for a price on a job the service list can't price, and
 * the stylist's answer. Accepting a quote books it at the quoted price and
 * duration; nothing about the payment changes from a normal booking.
 *
 * <p>Times are UTC. A quote past {@code validUntil} reads as EXPIRED even before
 * anything writes that status, so a stale price can never be booked.
 */
@Data
@Entity
@Table(name = "quotes")
public class QuoteEntity implements Serializable {
    public static final String REQUESTED = "REQUESTED";
    public static final String QUOTED = "QUOTED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String DECLINED = "DECLINED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** The id shown to people and used in URLs; the row id stays internal. */
    @Column(name = "quote_ref", nullable = false, length = 16)
    private String quoteRef;
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;
    @Column(name = "styler_id", nullable = false, length = 64)
    private String stylerId;
    /** The stylist's service this is a custom version of; the booking keeps it. */
    @Column(name = "sub_service_id", nullable = false, length = 32)
    private String subServiceId;
    @Column(nullable = false, length = 1000)
    private String description;
    @Column(name = "preferred_dates", length = 200)
    private String preferredDates;
    /** "visitBarber" or "homeService", as on a booking. */
    @Column(name = "service_time", length = 32)
    private String serviceTime;
    @Column(name = "no_of_people", nullable = false)
    private Integer noOfPeople = 1;
    @Column(nullable = false, length = 16)
    private String status = REQUESTED;
    /** The whole job's price, travel excluded: the booking adds the usual travel fee. */
    @Column(name = "price_cents")
    private Long priceCents;
    @Column(name = "duration_minutes")
    private Integer durationMinutes;
    @Column(name = "stylist_note", length = 500)
    private String stylistNote;
    @Column(name = "valid_until")
    private LocalDateTime validUntil;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "quoted_at")
    private LocalDateTime quotedAt;
    @Column(name = "closed_at")
    private LocalDateTime closedAt;
    @Column(name = "appointment_id", length = 32)
    private String appointmentId;
    /** Up to three reference photo URLs from the customer, one per line. */
    @Column(name = "reference_photos", length = 1500)
    private String referencePhotos;

    public java.util.List<String> photoList() {
        return referencePhotos == null || referencePhotos.isBlank() ? java.util.List.of()
                : java.util.Arrays.stream(referencePhotos.split("\\n")).filter(url -> !url.isBlank()).toList();
    }

    /** The status as of {@code now}: a quoted price past its date reads as expired. */
    public String effectiveStatus(LocalDateTime now) {
        if (QUOTED.equals(status) && validUntil != null && now.isAfter(validUntil)) {
            return EXPIRED;
        }
        return status;
    }

    /** Whether the request is still waiting on someone. */
    public boolean isOpen(LocalDateTime now) {
        String current = effectiveStatus(now);
        return REQUESTED.equals(current) || QUOTED.equals(current);
    }
}
