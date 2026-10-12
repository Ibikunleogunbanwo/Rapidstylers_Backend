package com.macrotel.rapidstylers.booking.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.engagement.service.NotificationService;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import com.macrotel.rapidstylers.styler.service.VendorZoneResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Emails each customer once, about a day before an accepted appointment.
 *
 * <p>Every {@code app.booking-reminder.interval-ms} the job reads accepted,
 * not-yet-reminded bookings dated within the next few days, applies the
 * stylist's zone to each one's wall-clock start, and asks
 * {@link BookingReminderPolicy} whether it is due. For each due booking one
 * short transaction claims it ({@code UPDATE ... SET reminder_sent_at WHERE
 * reminder_sent_at IS NULL}), queues the email in the outbox and adds the
 * customer's in-app notification. Only the transaction whose claim updated the
 * row goes on, so two instances, two overlapping runs or a retry send once;
 * the outbox then delivers through Kafka with the consumer's per-event dedup,
 * like every other booking email.
 *
 * <p>The read-only demo copy (app.demo.read-only) never runs it.
 */
@Service
public class BookingReminderService {

    private static final Logger LOG = Logger.getLogger(BookingReminderService.class.getName());
    /** Customer account status that may sign in and receive mail ("SUSPENDED" may not). */
    static final String ACTIVE_CUSTOMER = "0";

    @Autowired
    BookAppointmentRepo bookAppointmentRepo;
    @Autowired
    StylerRecords stylerRecords;
    @Autowired
    CustomerRecords customerRecords;
    @Autowired
    OutboxEventService outboxEventService;
    @Autowired
    NotificationService notificationService;
    @Autowired
    PlatformTransactionManager transactionManager;

    @Value("${app.booking-reminder.enabled:true}")
    boolean enabled = true;
    @Value("${app.booking-reminder.lead-hours:24}")
    long leadHours = 24;
    @Value("${app.booking-reminder.min-notice-hours:2}")
    long minNoticeHours = 2;
    @Value("${app.booking-reminder.batch-size:500}")
    int batchSize = 500;
    @Value("${app.booking-reminder.bookings-url:https://rapidstylers.ca/dashboard}")
    String bookingsUrl = "https://rapidstylers.ca/dashboard";
    @Value("${app.demo.read-only:false}")
    boolean readOnly;
    /** Clock for timing a stylist whose row names no zone; the email then prints the time without a label. */
    @Value("${app.time-zone:America/Edmonton}")
    String appTimeZone = "America/Edmonton";

    @Scheduled(fixedDelayString = "${app.booking-reminder.interval-ms:600000}",
            initialDelayString = "${app.booking-reminder.initial-delay-ms:60000}")
    public void scheduledRun() {
        if (!enabled || readOnly) return;
        try {
            int sent = runOnce(Instant.now());
            if (sent > 0) LOG.info("Booking reminders queued: " + sent);
        } catch (Exception ex) {
            LOG.warning("Booking reminder run failed: " + ex.getMessage());
        }
    }

    /** One pass at {@code now}. Returns how many reminders this call claimed and queued. */
    public int runOnce(Instant now) {
        if (readOnly) return 0;
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        // Wide enough for every Canadian zone either side of UTC midnight plus the lead.
        List<BookAppointmentEntity> candidates = bookAppointmentRepo.findReminderCandidates(
                today.minusDays(1), today.plusDays(leadHours / 24 + 2), PageRequest.of(0, batchSize));
        Map<String, Optional<StylerEntity>> stylers = new HashMap<>();
        int sent = 0;
        for (BookAppointmentEntity appointment : candidates) {
            try {
                Optional<StylerEntity> styler = stylers.computeIfAbsent(appointment.getStylerId(),
                        id -> id == null ? Optional.empty() : stylerRecords.findByStylerId(id));
                ZoneId zone = timingZone(styler.orElse(null));
                BookingReminderPolicy.Outcome outcome = BookingReminderPolicy.decide(appointment.getStatus(),
                        appointment.getServiceDisputeStatus(), appointment.getPaymentStatus(),
                        appointment.getReminderSentAt(), appointment.getAppointmentDateValue(),
                        appointment.getAppointmentStartTime(), zone, now, leadHours, minNoticeHours);
                if (outcome != BookingReminderPolicy.Outcome.SEND) continue;
                if (!customerCanReceive(appointment.getUserId())) continue;
                if (claimAndQueue(appointment, styler.orElse(null), now)) sent++;
            } catch (RuntimeException ex) {
                LOG.warning("Booking reminder for " + appointment.getAppointmentId() + " failed: " + ex.getMessage());
            }
        }
        return sent;
    }

    /**
     * Claims the booking's reminder and, only if this call won the claim,
     * queues the email and the in-app entry in the same transaction.
     */
    boolean claimAndQueue(BookAppointmentEntity appointment, StylerEntity styler, Instant now) {
        LocalDateTime sentAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        Boolean won = new TransactionTemplate(transactionManager).execute(status -> {
            if (bookAppointmentRepo.claimReminder(appointment.getId(), sentAt) != 1) return false;
            outboxEventService.bookingReminder(appointment, emailExtras(appointment, styler));
            String zoneLabel = styler == null ? ""
                    : VendorZoneResolver.labelForStyler(styler.getTimeZone(), styler.getProvince());
            String stylist = styler == null ? "your stylist" : displayName(styler);
            notificationService.recordBookingInApp(appointment, "Appointment reminder",
                    "Your appointment with " + stylist + " is coming up"
                            + (zoneLabel.isEmpty() ? "." : " (times in " + zoneLabel + ")."));
            return true;
        });
        return Boolean.TRUE.equals(won);
    }

    /** Where to go and where to manage it, resolved now so the email shows what the booking says today. */
    Map<String, String> emailExtras(BookAppointmentEntity appointment, StylerEntity styler) {
        Map<String, String> extra = new LinkedHashMap<>();
        boolean homeVisit = "homeService".equalsIgnoreCase(appointment.getServiceTime());
        extra.put("visitType", homeVisit ? "HOME" : "SALON");
        extra.put("location", homeVisit ? text(appointment.getServiceAddress()) : salonAddress(styler));
        extra.put("bookingsUrl", bookingsUrl == null ? "" : bookingsUrl);
        return extra;
    }

    static String salonAddress(StylerEntity styler) {
        if (styler == null) return "";
        if (!text(styler.getBusinessAddress()).isEmpty()) return styler.getBusinessAddress().trim();
        StringBuilder out = new StringBuilder();
        String street = text(styler.getStreetAddress());
        String unit = text(styler.getUnit());
        if (!street.isEmpty()) out.append(unit.isEmpty() ? street : unit + " - " + street);
        for (String part : new String[]{styler.getCity(), styler.getProvince(), styler.getPostalCode()}) {
            if (!text(part).isEmpty()) out.append(out.length() == 0 ? "" : ", ").append(part.trim());
        }
        return out.toString();
    }

    private boolean customerCanReceive(String userId) {
        if (userId == null) return false;
        Optional<UserEntity> user = customerRecords.findByUserId(userId);
        return user.isPresent() && ACTIVE_CUSTOMER.equals(user.get().getStatus())
                && !text(user.get().getEmailAddress()).isEmpty();
    }

    private ZoneId timingZone(StylerEntity styler) {
        ZoneId zone = styler == null ? null : VendorZoneResolver.zoneForStyler(styler.getTimeZone(), styler.getProvince());
        if (zone != null) return zone;
        try {
            return ZoneId.of(appTimeZone);
        } catch (Exception ex) {
            return VendorZoneResolver.DEFAULT_ZONE;
        }
    }

    private static String displayName(StylerEntity styler) {
        if (!text(styler.getBusinessName()).isEmpty()) return styler.getBusinessName().trim();
        String name = (text(styler.getFirstname()) + " " + text(styler.getLastname())).trim();
        return name.isEmpty() ? "your stylist" : name;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
