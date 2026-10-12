package com.macrotel.rapidstylers.outbox;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.util.HtmlUtils;

/**
 * The customer's day-before reminder email. The admin's published
 * booking_reminder template wins when there is one; otherwise this built-in
 * wording is used. Either way the email ends up naming the place and linking
 * to the customer's bookings: if the template left out {{location}} or
 * {{bookingsLink}}, a server-written line is added for it. Every value is
 * escaped; the only HTML is HTML written here.
 */
final class BookingReminderMail {

    static final String TEMPLATE = "booking_reminder";
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.CANADA);

    private BookingReminderMail() {
    }

    /** Placeholder values, as SiteContentRules lists them for booking_reminder. */
    static Map<String, String> values(String firstName, String serviceName, String stylistName, String isoDate,
                                      String time, String zoneSuffix, String location, String bookingsUrl) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("firstName", text(firstName));
        v.put("serviceName", text(serviceName).isEmpty() ? "Appointment" : text(serviceName));
        v.put("stylistName", text(stylistName).isEmpty() ? "your stylist" : text(stylistName));
        v.put("appointmentDate", longDate(isoDate));
        // zoneSuffix is VendorZoneResolver's " (Mountain Time)", or "" when the stylist's clock is unknown.
        v.put("appointmentTime", text(time) + (zoneSuffix == null ? "" : zoneSuffix));
        v.put("location", text(location));
        v.put("bookingsLink", text(bookingsUrl));
        return v;
    }

    static String defaultSubject(Map<String, String> v) {
        return "Reminder: " + v.get("serviceName") + " with " + v.get("stylistName") + " on " + v.get("appointmentDate");
    }

    static String defaultHtml(Map<String, String> v, boolean homeVisit) {
        String name = v.get("firstName");
        StringBuilder html = new StringBuilder();
        html.append("<p>Hi").append(name.isEmpty() ? "" : " " + esc(name)).append(",</p>")
                .append("<p>This is a reminder of your upcoming appointment.</p>")
                .append("<p><strong>Service:</strong> ").append(esc(v.get("serviceName"))).append("<br>")
                .append("<strong>Stylist:</strong> ").append(esc(v.get("stylistName"))).append("<br>")
                .append("<strong>Date:</strong> ").append(esc(v.get("appointmentDate"))).append("<br>")
                .append("<strong>Time:</strong> ").append(esc(v.get("appointmentTime"))).append("</p>")
                .append(details(v, homeVisit, true, true))
                .append("<p>Need to make a change? Please do it from your bookings as early as you can.</p>")
                .append("<p>– The RapidStylers Team</p>");
        return html.toString();
    }

    /** A rendered admin template, plus the place and the link when the template did not include them. */
    static String completeTemplate(String renderedHtml, Map<String, String> v, boolean homeVisit) {
        boolean needsPlace = !v.get("location").isEmpty() && !renderedHtml.contains(esc(v.get("location")));
        boolean needsLink = !v.get("bookingsLink").isEmpty() && !renderedHtml.contains(esc(v.get("bookingsLink")));
        return renderedHtml + details(v, homeVisit, needsPlace, needsLink);
    }

    private static String details(Map<String, String> v, boolean homeVisit, boolean place, boolean link) {
        StringBuilder html = new StringBuilder();
        if (place && !v.get("location").isEmpty()) {
            html.append("<p><strong>").append(homeVisit ? "Your stylist will come to:" : "Where:").append("</strong> ")
                    .append(esc(v.get("location"))).append("</p>");
        }
        if (link && !v.get("bookingsLink").isEmpty()) {
            html.append("<p><a href=\"").append(esc(v.get("bookingsLink")))
                    .append("\" style=\"background:#9381FF;color:#ffffff;padding:12px 22px;border-radius:8px;"
                            + "text-decoration:none;font-weight:600\">View your bookings</a></p>");
        }
        return html.toString();
    }

    static String longDate(String isoDate) {
        try {
            return LocalDate.parse(text(isoDate)).format(LONG_DATE);
        } catch (Exception ex) {
            return text(isoDate).isEmpty() ? "your booked date" : text(isoDate);
        }
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
