package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.service.AppointmentRecords;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.engagement.service.NotificationService;
import com.macrotel.rapidstylers.styler.entity.AvailabilityEntity;
import com.macrotel.rapidstylers.styler.entity.AvailabilityExceptionEntity;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.pojo.AvailabilityData;
import com.macrotel.rapidstylers.styler.pojo.ExceptionData;
import com.macrotel.rapidstylers.styler.repo.AvailabilityExceptionRepo;
import com.macrotel.rapidstylers.styler.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.Locale;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * A stylist's weekly hours, days off and travel settings, and the checks that a booking time fits them.
 */
@Service
public class AvailabilityService {
    private static final Logger LOG = Logger.getLogger(AvailabilityService.class.getName());
    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    AppointmentRecords appointmentRecords;
    @Autowired
    AvailabilityRepo availabilityRepo;
    @Autowired
    AvailabilityExceptionRepo availabilityExceptionRepo;
    @Autowired
    ReadCacheService readCacheService;
    @Autowired
    NotificationService notificationService;

    /** True when no active booking overlaps the requested service duration. */
    public boolean isWindowFree(String stylerId, String appointmentDate, String arrivalTime, int requestedDurationMinutes){
        final LocalDate requestedDate;
        final LocalTime proposed;
        try {
            requestedDate = parseBookingDate(appointmentDate);
            proposed = parseBookingTime(arrivalTime);
        } catch (DateTimeParseException ex) {
            return false; // fail closed for service-layer callers that bypass DTO validation
        }

        // Canonical rows are the source of truth. The legacy query keeps old
        // rows visible until their temporal columns are backfilled.
        List<BookAppointmentEntity> dayBookings = new ArrayList<>();
        List<BookAppointmentEntity> canonical = appointmentRecords.findByStylerIdAndAppointmentDateValue(stylerId, requestedDate);
        List<BookAppointmentEntity> legacy = appointmentRecords.findByStylerIdAndAppointmentDate(stylerId, appointmentDate);
        if(canonical != null) dayBookings.addAll(canonical);
        if(legacy != null){
            for(BookAppointmentEntity booking : legacy){
                if(dayBookings.stream().noneMatch(existing -> Objects.equals(existing.getId(), booking.getId()))){
                    dayBookings.add(booking);
                }
            }
        }
        if(dayBookings.isEmpty()) return true;
        for(BookAppointmentEntity booking : dayBookings){
            String status = booking.getStatus();
            // Cancelled (4) and rejected (2) free the slot; pending/accepted/completed block it.
            if("2".equals(status) || "4".equals(status)) continue;
            LocalTime start = booking.getAppointmentStartTime();
            if(start == null){
                try {
                    start = parseBookingTime(booking.getArrivalTime());
                } catch (DateTimeParseException ex) {
                    return false; // an active malformed legacy row must never be bypassed
                }
            }
            int bookingDuration = booking.getDurationMinutes() == null
                    ? DEFAULT_SERVICE_DURATION_MINUTES : booking.getDurationMinutes();
            // Minute-of-day arithmetic, not LocalTime.plusMinutes: LocalTime wraps
            // at midnight, so a 23:30 booking "ended" at 00:30 and looked like it
            // finished before a 09:00 proposal instead of running into the night.
            int bookedStart = minuteOfDay(start);
            int bookedEnd = bookedStart + bookingDuration;
            int proposedStart = minuteOfDay(proposed);
            int proposedEnd = proposedStart + requestedDurationMinutes;
            if(proposedStart < bookedEnd && proposedEnd > bookedStart){
                return false;
            }
        }
        return true;
    }

    /** Minutes from midnight. LocalTime's own plusMinutes wraps at midnight; this never does. */
    private static int minuteOfDay(LocalTime time){
        return time.getHour() * 60 + time.getMinute();
    }

    /**
     * True when a service of {@code durationMinutes} starting at
     * {@code startMinutes} fits entirely inside a weekly window.
     *
     * Weekly windows are same-day shifts, so a window whose end is not after its
     * start is malformed rather than an overnight one, and a start whose duration
     * would cross midnight is out of hours. The old check used
     * LocalTime.plusMinutes, which wraps: 23:50 + 30 landed at 00:20, "before" a
     * 17:00 close, so a vendor who closed at 17:00 read open every night after
     * 23:30. Minute-of-day arithmetic cannot wrap, so that false positive is gone.
     */
    private static boolean windowFits(LocalTime windowStart, LocalTime windowEnd, int startMinutes, int durationMinutes){
        int open = minuteOfDay(windowStart);
        int close = minuteOfDay(windowEnd);
        if(close <= open) return false;
        return startMinutes >= open && startMinutes + durationMinutes <= close;
    }

    /** True when the complete requested service fits inside weekly hours. */
    public boolean timeWithinAvailability(String stylerId, String appointmentDate, String arrivalTime, int requestedDurationMinutes){
        List<AvailabilityEntity> rows = availabilityRepo.findByStylerId(stylerId);
        if(rows == null || rows.isEmpty()){
            return true; // no hours set — the stylist confirms each request manually
        }
        try{
            int jsWeekday = LocalDate.parse(appointmentDate).getDayOfWeek().getValue() % 7; // 0 = Sunday
            String targetDay = String.valueOf(jsWeekday);
            LocalTime requested = parseBookingTime(arrivalTime);
            for(AvailabilityEntity row : rows){
                if(targetDay.equals(row.getDayOfWeek())){
                    LocalTime start = parseAvailabilityTime(row.getStartTime());
                    LocalTime end = parseAvailabilityTime(row.getEndTime());
                    if(windowFits(start, end, minuteOfDay(requested), requestedDurationMinutes)){
                        return true;
                    }
                }
            }
            return false;
        }
        catch (DateTimeParseException ex){
            return false; // fail closed: malformed date/time or hours can never bypass availability
        }
    }

    public LocalDate parseBookingDate(String value){
        return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Canonical booking/search times are 24-hour HH:mm (aligned with the
     * availability API). The 12-hour h:mm a fallback only tolerates legacy
     * rows written before the format alignment so they never hard-block
     * availability/conflict checks.
     */
    public LocalTime parseBookingTime(String value){
        String normalized = value.trim().toUpperCase(Locale.ENGLISH);
        try {
            return LocalTime.parse(normalized, DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH));
        } catch (DateTimeParseException ex) {
            return LocalTime.parse(normalized, DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH));
        }
    }

    private LocalTime parseAvailabilityTime(String value){
        return LocalTime.parse(value.trim(), DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH));
    }

    /** Returns the stylist's weekly availability as [{dayOfWeek, startTime, endTime}] sorted by day. */
    public BaseResponse stylerAvailability(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerId(stylerId);
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(cachedAvailabilitySlots(stylerId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Replaces the stylist's weekly availability with the supplied slots (delete-all + insert). */
    public BaseResponse updateStylerAvailability(String stylerId, List<AvailabilityData> slots){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerId(stylerId);
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            if(slots == null || slots.isEmpty()){
                availabilityRepo.deleteByStylerId(stylerId);
                evictAvailabilityCache(stylerId);
                notificationService.notifySavedCustomers(stylerId, "AVAILABILITY", "Saved professional availability changed",
                        "The working hours for your saved professional have been cleared. Open their profile to view the latest schedule.");
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage("Availability cleared — no working hours set");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Validate every slot before touching the DB. Element-level bean
            // constraints do not cascade into a @Valid List, so enforce them
            // here: day 0-6, HH:mm 24-hour times, end strictly after start.
            for(AvailabilityData slot : slots){
                String day = slot.getDayOfWeek();
                String start = slot.getStartTime();
                String end = slot.getEndTime();
                if(day == null || !day.trim().matches("[0-6]")){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("Day of week must be 0 (Sunday) to 6 (Saturday)");
                    response.setData(EMPTY_DATA);
                    return response;
                }
                if(start == null || end == null
                        || !start.matches("^([01]?[0-9]|2[0-3]):[0-5][0-9]$")
                        || !end.matches("^([01]?[0-9]|2[0-3]):[0-5][0-9]$")){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("Start and end times must be in HH:mm 24-hour format");
                    response.setData(EMPTY_DATA);
                    return response;
                }
                if(start.compareTo(end) >= 0){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("End time must be after start time for every working day");
                    response.setData(EMPTY_DATA);
                    return response;
                }
            }
            availabilityRepo.deleteByStylerId(stylerId);
            List<AvailabilityEntity> toSave = new ArrayList<>();
            for(AvailabilityData slot : slots){
                toSave.add(new AvailabilityEntity(stylerId, slot));
            }
            availabilityRepo.saveAll(toSave);
            evictAvailabilityCache(stylerId);
            notificationService.notifySavedCustomers(stylerId, "AVAILABILITY", "Saved professional availability changed",
                    "The working hours for your saved professional have been updated. Open their profile to view the latest schedule.");
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Availability updated successfully");
            response.setData(availabilitySlots(stylerId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Returns the stylist's date-based exceptions [{blockedDate, reason}] sorted by date. */
    public BaseResponse stylerAvailabilityExceptions(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerId(stylerId);
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(cachedExceptionSlots(stylerId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Adds a date-based exception (vacation, sick day) for the stylist. */
    public BaseResponse addAvailabilityException(String stylerId, ExceptionData exceptionData){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerId(stylerId);
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Check for duplicate date
            if(availabilityExceptionRepo.findByStylerIdAndBlockedDate(stylerId, exceptionData.getBlockedDate()).isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("This date is already marked as unavailable");
                response.setData(EMPTY_DATA);
                return response;
            }
            AvailabilityExceptionEntity entity = new AvailabilityExceptionEntity(stylerId, exceptionData);
            availabilityExceptionRepo.save(entity);
            evictAvailabilityCache(stylerId);
            notificationService.notifySavedCustomers(stylerId, "AVAILABILITY", "Saved professional availability changed",
                    "Your saved professional marked " + exceptionData.getBlockedDate() + " as unavailable.");
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Date marked as unavailable");
            response.setData(exceptionSlots(stylerId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Removes a date-based exception (restores availability for that date). */
    public BaseResponse deleteAvailabilityException(String stylerId, Long exceptionId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerId(stylerId);
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            availabilityExceptionRepo.deleteByStylerIdAndId(stylerId, exceptionId);
            evictAvailabilityCache(stylerId);
            notificationService.notifySavedCustomers(stylerId, "AVAILABILITY", "Saved professional availability changed",
                    "A previously unavailable date was restored for your saved professional.");
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Date restored to available");
            response.setData(exceptionSlots(stylerId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** True when the requested date is not blocked by a vacation/sick exception. */
    public boolean isDateNotException(String stylerId, String appointmentDate){
        return !availabilityExceptionRepo.findByStylerIdAndBlockedDate(stylerId, appointmentDate).isPresent();
    }

    /** Shared helper: stylist's exception dates as a sorted list of maps. */
    private List<Object> exceptionSlots(String stylerId){
        List<AvailabilityExceptionEntity> rows = availabilityExceptionRepo.findByStylerId(stylerId);
        List<Object> result = new ArrayList<>();
        if(rows == null || rows.isEmpty()){
            return result;
        }
        rows.sort(Comparator.comparing(AvailabilityExceptionEntity::getBlockedDate));
        for(AvailabilityExceptionEntity row : rows){
            HashMap<String, String> slot = new HashMap<>();
            slot.put("id", String.valueOf(row.getId()));
            slot.put("blockedDate", row.getBlockedDate());
            slot.put("reason", row.getReason());
            result.add(slot);
        }
        return result;
    }

    /** Shared helper: stylist's weekly slots as a sorted list of maps, or empty list when none set. */
    private List<Object> availabilitySlots(String stylerId){
        List<AvailabilityEntity> rows = availabilityRepo.findByStylerId(stylerId);
        List<Object> result = new ArrayList<>();
        if(rows == null || rows.isEmpty()){
            return result;
        }
        rows.sort(Comparator.comparing(AvailabilityEntity::getDayOfWeek));
        for(AvailabilityEntity row : rows){
            HashMap<String, String> slot = new HashMap<>();
            slot.put("dayOfWeek", row.getDayOfWeek());
            slot.put("startTime", row.getStartTime());
            slot.put("endTime", row.getEndTime());
            result.add(slot);
        }
        return result;
    }

    /** Returns the stylist's current home-visit travel settings (free radius + flat fee). */
    public BaseResponse stylerTravelSettings(String stylerId){
        BaseResponse response = new BaseResponse(true);
        Optional<StylerEntity> styler = stylerRepo.findByStylerId(stylerId);
        if(styler.isEmpty()){
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Invalid Styler Id");
            response.setData(EMPTY_DATA);
            return response;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("includedTravelKm", styler.get().getIncludedTravelKm() == null ? 15.0 : styler.get().getIncludedTravelKm());
        data.put("baseTravelFee", styler.get().getBaseTravelFee() == null ? "0.00" : styler.get().getBaseTravelFee());
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(data);
        return response;
    }

    /** Updates the stylist's flat home-visit fee and the included free radius. */
    public BaseResponse updateStylerTravelSettings(String stylerId, Double includedTravelKm, String baseTravelFee){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerId(stylerId);
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            if(includedTravelKm != null && includedTravelKm < 0){
                return Responses.errorResponse(response, "Included travel distance cannot be negative");
            }
            Double includedKm = includedTravelKm == null ? 15.0 : includedTravelKm;
            String fee;
            String rawFee = baseTravelFee == null ? "0.00" : baseTravelFee.trim().replace(",", "");
            try {
                BigDecimal feeAmount = new BigDecimal(rawFee);
                if(feeAmount.signum() < 0){
                    return Responses.errorResponse(response, "Home visit fee cannot be negative");
                }
                fee = feeAmount.setScale(2, RoundingMode.HALF_UP).toPlainString();
            } catch(NumberFormatException ex){
                return Responses.errorResponse(response, "Home visit fee must be a valid amount");
            }
            StylerEntity styler = isStylerExist.get();
            String previousFee = styler.getBaseTravelFee();
            styler.setIncludedTravelKm(includedKm);
            styler.setBaseTravelFee(fee);
            stylerRepo.save(styler);
            evict(ReadCacheService.KEY_STYLER_DTO + stylerId);
            if(!Objects.equals(previousFee, fee)){
                notificationService.notifySavedCustomers(stylerId, "TRAVEL_FEE", "Saved professional updated their home-visit fee",
                        "Your saved professional's home-visit fee is now $" + fee
                                + ". Check their profile for the latest details.");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("includedTravelKm", includedKm);
            data.put("baseTravelFee", fee);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Home visit settings updated");
            response.setData(data);
            return response;
        } catch(Exception ex){
            LOG.warning("Home visit settings update failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not update home visit settings");
        }
    }

    /**
     * The blocked dates that can still affect a booking decision.
     *
     * A professional's exception history grows for years while only the days
     * ahead of them matter to a card, so the rest is left out of list responses
     * rather than shipped to every visitor in every search. A day of margin is
     * kept because a Canadian professional's date can be one behind the server's
     * UTC date.
     */
    public List<Object> upcomingExceptions(String stylerId){
        String keepFrom = LocalDate.now().minusDays(1).toString();
        List<Object> upcoming = new ArrayList<>();
        for(Object slot : cachedExceptionSlots(stylerId)){
            if(!(slot instanceof Map)) continue;
            Object blockedDate = ((Map<?, ?>) slot).get("blockedDate");
            if(blockedDate != null && String.valueOf(blockedDate).compareTo(keepFrom) >= 0){
                upcoming.add(slot);
            }
        }
        return upcoming;
    }

    /** Weekly availability through the read cache; evicted on availability/exception writes. */
    public List<Object> cachedAvailabilitySlots(String stylerId){
        return readCacheService.getOrLoad(
                ReadCacheService.KEY_STYLER_AVAILABILITY + stylerId,
                ReadCacheService.AVAILABILITY_TTL, ReadCacheService.AVAILABILITY_JITTER,
                () -> availabilitySlots(stylerId));
    }

    /** Date-based exceptions through the read cache; evicted with the weekly cache. */
    public List<Object> cachedExceptionSlots(String stylerId){
        return readCacheService.getOrLoad(
                ReadCacheService.KEY_STYLER_AVAILABILITY + stylerId + ":exceptions",
                ReadCacheService.AVAILABILITY_TTL, ReadCacheService.AVAILABILITY_JITTER,
                () -> exceptionSlots(stylerId));
    }

    /** Clears both weekly and exception availability cache keys for a stylist. */
    private void evictAvailabilityCache(String stylerId){
        evict(ReadCacheService.KEY_STYLER_AVAILABILITY + stylerId,
                ReadCacheService.KEY_STYLER_AVAILABILITY + stylerId + ":exceptions");
    }

    /** Null-safe eviction — unit tests that build this service without a cache never break. */
    private void evict(String... keys){
        if(readCacheService != null){
            readCacheService.evict(keys);
        }
    }

    /**
     * Inside the stylist's set hours at this moment, and not a day off. Unlike
     * {@link #isOpenAt}, an appointment in progress does not count as closed:
     * the stylist is still open for business, just busy.
     */
    public boolean isWithinHours(String stylerId, LocalDate date, LocalTime time){
        if(!isDateNotException(stylerId, date.toString())) return false;
        List<AvailabilityEntity> rows = availabilityRepo.findByStylerId(stylerId);
        if(rows == null || rows.isEmpty()) return false;
        int weekday = date.getDayOfWeek().getValue() % 7;
        int nowMinutes = minuteOfDay(time);
        return rows.stream().filter(row -> Integer.toString(weekday).equals(row.getDayOfWeek())).anyMatch(row -> {
            try {
                return windowFits(parseAvailabilityTime(row.getStartTime()), parseAvailabilityTime(row.getEndTime()), nowMinutes, 1);
            } catch(Exception ex){ return false; }
        });
    }

    public boolean isOpenAt(String stylerId, LocalDate date, LocalTime time, int durationMinutes){
        if(!isDateNotException(stylerId, date.toString())) return false;
        List<AvailabilityEntity> rows = availabilityRepo.findByStylerId(stylerId);
        if(rows == null || rows.isEmpty()) return false;
        int weekday = date.getDayOfWeek().getValue() % 7;
        int nowMinutes = minuteOfDay(time);
        return rows.stream().filter(row -> Integer.toString(weekday).equals(row.getDayOfWeek())).anyMatch(row -> {
            try {
                LocalTime start = parseAvailabilityTime(row.getStartTime());
                LocalTime end = parseAvailabilityTime(row.getEndTime());
                return windowFits(start, end, nowMinutes, durationMinutes)
                        && isWindowFree(stylerId, date.toString(), time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)), durationMinutes);
            } catch(Exception ex){ return false; }
        });
    }
}
