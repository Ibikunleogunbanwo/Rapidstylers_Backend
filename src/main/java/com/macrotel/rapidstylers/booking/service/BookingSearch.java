package com.macrotel.rapidstylers.booking.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * The admin's booking filters, turned into one database query. Every field is
 * optional; a null field does not narrow the result. {@code stylerIds} is how
 * other domains narrow by things the appointment row does not hold (the
 * stylist's province or service category): they look the stylists up and pass
 * their ids, and an empty collection means "no stylist matched", so no rows.
 */
public class BookingSearch {

    public static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("appointmentDateValue"),
            Sort.Order.desc("appointmentStartTime"), Sort.Order.desc("id"));

    public List<String> statuses;
    public LocalDate from;
    public LocalDate to;
    public String subServiceId;
    public String stylerId;
    public String userId;
    public String appointmentId;
    public String paymentStatus;
    public Collection<String> stylerIds;
    /** Card charged at or after this moment (UTC). */
    public java.time.LocalDateTime capturedFrom;
    /** Card charged before this moment (UTC). */
    public java.time.LocalDateTime capturedBefore;

    Specification<BookAppointmentEntity> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (statuses != null && !statuses.isEmpty()) where.add(root.get("status").in(statuses));
            if (from != null) where.add(cb.greaterThanOrEqualTo(root.get("appointmentDateValue"), from));
            if (to != null) where.add(cb.lessThanOrEqualTo(root.get("appointmentDateValue"), to));
            if (subServiceId != null) where.add(cb.equal(root.get("subServiceId"), subServiceId));
            if (stylerId != null) where.add(cb.equal(root.get("stylerId"), stylerId));
            if (userId != null) where.add(cb.equal(root.get("userId"), userId));
            if (appointmentId != null) where.add(cb.equal(root.get("appointmentId"), appointmentId));
            if (paymentStatus != null) where.add(cb.equal(root.get("paymentStatus"), paymentStatus));
            if (capturedFrom != null) where.add(cb.greaterThanOrEqualTo(root.get("paymentCapturedAt"), capturedFrom));
            if (capturedBefore != null) where.add(cb.lessThan(root.get("paymentCapturedAt"), capturedBefore));
            if (stylerIds != null) {
                where.add(stylerIds.isEmpty() ? cb.disjunction() : root.get("stylerId").in(stylerIds));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
    }
}
