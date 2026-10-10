package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.repo.SubServiceRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.auth.repo.OTPRepo;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.engagement.repo.ReviewRepo;
import com.macrotel.rapidstylers.engagement.repo.SupportTicketRepo;
import com.macrotel.rapidstylers.engagement.service.LoyaltyService;
import com.macrotel.rapidstylers.engagement.service.NotificationService;
import com.macrotel.rapidstylers.entity.*;
import com.macrotel.rapidstylers.outbox.OutboxEventRepo;
import com.macrotel.rapidstylers.pojo.*;
import com.macrotel.rapidstylers.repo.*;
import com.macrotel.rapidstylers.styler.service.AvailabilityService;
import com.macrotel.rapidstylers.styler.service.StylerEligibility;
import com.macrotel.rapidstylers.styler.service.StylerService;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.macrotel.rapidstylers.config.AppConstants.*;

@Service
public class AppService {
    @Autowired
    DTOService dtoService;
    private static final Logger LOG = Logger.getLogger(AppService.class.getName());
    @Autowired
    OTPRepo otpRepo;
    @Autowired
    EmailConfig emailConfig;
    @Autowired
    UserRepo userRepo;
    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    SubServiceRepo subServiceRepo;
    @Autowired
    ReviewRepo reviewRepo;
    @Autowired
    SavedStylistRepo savedStylistRepo;
    @Autowired
    SupportTicketRepo supportTicketRepo;
    @Autowired
    AuditLogRepo auditLogRepo;
    @Autowired
    BookAppointmentRepo bookAppointmentRepo;
    @Autowired
    CardDetailsRepo cardDetailsRepo;
    @Autowired
    BookingSlotLockRepo bookingSlotLockRepo;
    @Autowired
    StripeService stripeService;
    @Autowired
    IdempotencyService idempotencyService;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    OutboxEventRepo outboxEventRepo;
    @Autowired
    ReadCacheService readCacheService;
    // Payment domain ops (carved out of this class; see simplification-plan.md).
    // Initialized inline so bare construction (unit tests) behaves as before;
    // Spring replaces both with the context beans via @Autowired.
    @Autowired
    PaymentOpsService paymentOpsService = new PaymentOpsService();
    // Shared audit-log / ops-alert sink (see simplification-plan.md).
    @Autowired
    AuditService auditService = new AuditService();
    // Booking starts and slot holds use 15-minute granularity; each service's
    // configured duration determines how much of the stylist's calendar is blocked.
    private static final int SLOT_GRANULARITY_MINUTES = 15;
    @Value("${app.time-zone:America/Edmonton}")
    private String appTimeZone;
    @Value("${app.booking.completion-grace-minutes:0}")
    private long completionGraceMinutes;
    @Value("${app.booking.payment-authorization-window-days:7}")
    private long paymentAuthorizationWindowDays;
    @Value("${app.booking.payment-authorization-lead-hours:48}")
    private long paymentAuthorizationLeadHours;
    /** Stylists may cancel a completed booking only within this window (hours since completion). */
    @Value("${app.booking.styler-cancel-window-hours:24}")
    private long stylerCancelWindowHours;
    @Autowired
    LoyaltyService loyaltyService;
    @Autowired
    NotificationService notificationService;
    @Autowired
    AvailabilityService availabilityService;
    @Autowired
    StylerEligibility stylerEligibility;
    @Autowired
    StylerService stylerService;

    public BaseResponse testing(){
        BaseResponse response = new BaseResponse(true);
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage("API is working well");
        response.setData(EMPTY_DATA);
        return response;
    }

    public BaseResponse singleUserData(String userId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<UserEntity> isUserExist = userRepo.findByUserId(userId);
            if(isUserExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid User Id, Kindly create account");
                response.setData(EMPTY_DATA);
                return response;
            }

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(dtoService.userDataDTO(userId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse updateUserData(UpdateData updateData, String accountId){
        BaseResponse response = new BaseResponse(true);
        try{
            // Ownership: the account being edited is the authenticated token subject, never the request body.
            Optional<UserEntity> isUserExist = userRepo.findByUserId(accountId);
            if(isUserExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid User Id, Kindly create an account");
                response.setData(EMPTY_DATA);
                return response;
            }
            UserEntity userEntity = isUserExist.get();
            userEntity.setFirstname(updateData.getFirstname().isEmpty() ? userEntity.getFirstname() : updateData.getFirstname());
            userEntity.setLastname(updateData.getLastname().isEmpty() ? userEntity.getLastname() : updateData.getLastname());
            userEntity.setAddress(updateData.getAddress().isEmpty() ? userEntity.getAddress() : updateData.getAddress());
            userEntity.setCountry(updateData.getCountry().isEmpty() ? userEntity.getCountry() : updateData.getCountry());
            userEntity.setPhoneNumber(updateData.getPhoneNumber().isEmpty() ? userEntity.getPhoneNumber() : updateData.getPhoneNumber());
            userEntity.setState(updateData.getState().isEmpty() ?  userEntity.getState() : updateData.getState());
            userRepo.save(userEntity);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Account Updated Successful");
            response.setData(EMPTY_DATA);

        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public void sendPaymentReceipt(BookAppointmentEntity appointment){
        paymentOpsService.sendPaymentReceipt(appointment);
    }

    /** Null-safe eviction — unit tests constructing AppService directly (no cache wired) never break. */
    private void evict(String... keys){
        if(readCacheService != null){
            readCacheService.evict(keys);
        }
    }

    /** Saves an approved stylist for the authenticated customer. */
    public BaseResponse saveStylist(String userId, String stylerId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(userRepo.findByUserId(userId).isEmpty()){
                return Responses.errorResponse(response, "Invalid User Id");
            }
            Optional<StylerEntity> styler = stylerRepo.findByStylerId(stylerId);
            if(styler.isEmpty() || !stylerEligibility.isBookableStyler(styler.get())){
                return Responses.errorResponse(response, "This professional is not available");
            }
            if(savedStylistRepo.findByUserIdAndStylerId(userId, stylerId).isEmpty()){
                savedStylistRepo.save(new SavedStylistEntity(userId, stylerId));
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Professional saved");
            response.setData(Collections.singletonMap("saved", true));
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Removes a saved stylist only from the authenticated customer's collection. */
    public BaseResponse removeSavedStylist(String userId, String stylerId){
        BaseResponse response = new BaseResponse(true);
        try {
            savedStylistRepo.deleteByUserIdAndStylerId(userId, stylerId);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Professional removed from saved list");
            response.setData(Collections.singletonMap("saved", false));
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Returns the authenticated customer's saved stylists with live profile DTOs. */
    public BaseResponse listSavedStylists(String userId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(userRepo.findByUserId(userId).isEmpty()){
                return Responses.errorResponse(response, "Invalid User Id");
            }
            List<Object> result = new ArrayList<>();
            for(SavedStylistEntity saved : savedStylistRepo.findByUserIdOrderByCreatedAtDesc(userId)){
                stylerRepo.findByStylerId(saved.getStylerId())
                        .filter(stylerEligibility::isBookableStyler)
                        .ifPresent(styler -> result.add(stylerService.listRowWithHours(dtoService.stylerAccountDTO(styler), styler.getStylerId())));
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Admin recovery-campaign funnel: every email that started a sign-up, with
     * the recovery stage it reached, when the last stage email was sent, and
     * whether the sign-up eventually converted to an account.
     */
    public BaseResponse listRecoveryCampaigns(){
        BaseResponse response = new BaseResponse(true);
        try {
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (Object[] r : otpRepo.findRecoveryCampaigns()) {
                Map<String, Object> row = new LinkedHashMap<>();
                String email = String.valueOf(r[0]);
                int stage = r[2] == null ? 0 : ((Number) r[2]).intValue();
                row.put("email", email);
                row.put("stage", stage);
                row.put("stageLabel", stageLabel(stage));
                row.put("attemptedAt", r[1] == null ? null : String.valueOf(r[1]));
                row.put("lastSentAt", r[3] == null ? null : String.valueOf(r[3]));
                Object rawConverted = r[4];
                boolean converted = rawConverted != null
                        && (rawConverted instanceof Boolean ? (Boolean) rawConverted : ((Number) rawConverted).intValue() == 1);
                row.put("converted", converted);
                rows.add(row);
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(rows);
        } catch(Exception ex){ LOG.warning("listRecoveryCampaigns: " + ex.getMessage()); }
        return response;
    }

    private String stageLabel(int stage){
        return switch (stage) {
            case 1 -> "24h reminder";
            case 2 -> "7-day message";
            case 3 -> "14-day nudge";
            case 4 -> "1-month final";
            default -> "None";
        };
    }

    public BaseResponse getCommissionSetting(String adminId){
        return paymentOpsService.getCommissionSetting(adminId);
    }

    public BaseResponse updateCommissionSetting(String adminId, double percent){
        return paymentOpsService.updateCommissionSetting(adminId, percent);
    }

    /** Admin-only: sends a test email through the same EmailConfig path used for real notifications. */
    public BaseResponse sendTestEmail(String adminId, String recipient){
        BaseResponse response = new BaseResponse(true);
        try{
            if(recipient == null || recipient.isBlank()
                    || !recipient.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")){
                return Responses.errorResponse(response, "Enter a valid recipient email address");
            }
            String result = emailConfig.sendSimpleMail(recipient, "RapidStylers test email",
                    "<h2>RapidStylers</h2><p>This is a test email sent from the admin console to verify email delivery.</p>");
            if("Mail Sent Successfully...".equals(result)){
                auditService.audit(adminId, "ADMIN", "SEND_TEST_EMAIL", "EMAIL", recipient, "sent");
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage("Test email sent to " + recipient);
                response.setData(EMPTY_DATA);
            } else {
                return Responses.errorResponse(response, "Email provider rejected the send — check the backend log");
            }
        } catch(Exception ex){
            LOG.warning("Test email failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not send test email");
        }
        return response;
    }

    /** Cache-effectiveness snapshot (hits/misses/loads/evictions + per-key top) since boot. */
    public BaseResponse getCacheStats(){
        BaseResponse response = new BaseResponse(true);
        try {
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(readCacheService == null ? new LinkedHashMap<String, Object>() : readCacheService.stats());
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse getAdminKpis(){
        BaseResponse response = new BaseResponse(true);
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("customers", userRepo.count());
            data.put("stylists", stylerRepo.count());
            data.put("approvedStylists", stylerRepo.findAll().stream().filter(stylerEligibility::isApprovedStyler).count());
            data.put("appointments", bookAppointmentRepo.count());
            data.put("pendingAppointments", bookAppointmentRepo.findAll().stream().filter(a -> "1".equals(a.getStatus())).count());
            data.put("completedAppointments", bookAppointmentRepo.findAll().stream().filter(a -> "0".equals(a.getStatus())).count());
            data.put("openSupportTickets", supportTicketRepo.countByStatus("OPEN"));
            data.put("reviews", reviewRepo.count());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse listAuditLogs(){
        BaseResponse response = new BaseResponse(true);
        try {
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(auditLogRepo.findTop100ByOrderByCreatedAtDesc());
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse estimateBooking(BookAppointmentData bookAppointmentData){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<StylerEntity> styler = stylerRepo.findByStylerId(bookAppointmentData.getStylerId());
            if(styler.isEmpty()){
                return Responses.errorResponse(response, "Invalid Styler Id");
            }
            if(!stylerEligibility.isApprovedStyler(styler.get())){
                return Responses.errorResponse(response, "This professional is not yet available for booking");
            }
            Optional<SubServiceEntity> subService = subServiceRepo.isServiceExistById(
                    bookAppointmentData.getStylerId(), Long.parseLong(bookAppointmentData.getSubServiceId()));
            if(subService.isEmpty()){
                return Responses.errorResponse(response, "Invalid service for this stylist");
            }
            TravelPricing pricing = calculateTravelPricing(bookAppointmentData, styler.get(), subService.get().getPrice());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(pricing.toMap());
        } catch(NumberFormatException ex){
            return Responses.errorResponse(response, "Invalid service for this stylist");
        } catch(IllegalArgumentException ex){
            return Responses.errorResponse(response, ex.getMessage());
        }
        return response;
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse bookAppointment(BookAppointmentData bookAppointmentData){
        BaseResponse response = new BaseResponse(true);
        IdempotencyService.Claim idempotencyClaim = null;
        try{
            if (idempotencyService != null && bookAppointmentData.getIdempotencyKey() != null
                    && !bookAppointmentData.getIdempotencyKey().trim().isEmpty()) {
                idempotencyClaim = idempotencyService.claim("book-appointment", bookAppointmentData.getUserId(),
                        bookAppointmentData.getIdempotencyKey(), Duration.ofHours(24));
                if (idempotencyClaim.isDuplicate()) {
                    // Check for a stored response from a previous successful booking
                    String storedResponse = idempotencyService.getStoredResponse(
                            "book-appointment", bookAppointmentData.getUserId(), bookAppointmentData.getIdempotencyKey());
                    if (storedResponse != null) {
                        LOG.info("Replaying stored idempotency response for booking");
                        return new BaseResponse(); // The controller will deserialize the stored JSON
                    }
                    return Responses.errorResponse(response, "This booking request is already being processed or was already submitted");
                }
            }
            LocalDate appointmentDate = availabilityService.parseBookingDate(bookAppointmentData.getAppointmentDate());
            LocalTime appointmentStart = availabilityService.parseBookingTime(bookAppointmentData.getArrivalTime());
            if(appointmentDate.isBefore(LocalDate.now(applicationZone()))){
                return Responses.errorResponse(response, "Appointment date cannot be in the past");
            }
            // The marketplace exposes quarter-hour boundaries; reject unsupported API values too.
            if(appointmentStart.getMinute() % SLOT_GRANULARITY_MINUTES != 0){
                return Responses.errorResponse(response, "Arrival time must start on a 15-minute boundary");
            }

            Optional<UserEntity> isUserExist = userRepo.findByUserId(bookAppointmentData.getUserId());
            if(isUserExist.isEmpty()){
                return Responses.errorResponse(response, "Invalid User Id, Kindly create account");
            }
            // This row lock serializes all booking attempts for one stylist before
            // the availability/conflict read and the unique slot inserts.
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerIdForUpdate(bookAppointmentData.getStylerId());
            if(isStylerExist.isEmpty()){
                return Responses.errorResponse(response, "Invalid Styler Id");
            }
            if(!stylerEligibility.isApprovedStyler(isStylerExist.get())){
                return Responses.errorResponse(response, "This professional is not yet available for booking");
            }
            // Visiting the professional is the default delivery, and a customer
            // with no destination cannot travel. Approval already refuses an
            // address-less profile, so this only catches legacy rows approved
            // before that rule — but a booking must not be creatable against a
            // profile the customer could never reach.
            if(!StylerEligibility.hasStreetAddress(isStylerExist.get())){
                return Responses.errorResponse(response, "This professional has no address published, so a visit cannot be booked with them yet");
            }
            // With payments live, a stylist must have finished Connect onboarding
            // to receive payouts — the marketplace blocks booking until then.
            if(stripeService.isConfigured() && !"COMPLETE".equals(isStylerExist.get().getConnectOnboardingStatus())){
                return Responses.errorResponse(response, "This professional is not yet available for online booking");
            }

            String subServiceId = bookAppointmentData.getSubServiceId();
            if(subServiceId == null || subServiceId.trim().isEmpty()){
                return Responses.errorResponse(response, "A service must be selected");
            }
            String price;
            int durationMinutes;
            TravelPricing pricing;
            try {
                Optional<SubServiceEntity> subService = subServiceRepo.isServiceExistById(
                        bookAppointmentData.getStylerId(), Long.parseLong(subServiceId));
                if(subService.isEmpty()){
                    return Responses.errorResponse(response, "Invalid service for this stylist");
                }
                // The client price and duration are never trusted.
                price = subService.get().getPrice();
                durationMinutes = serviceDuration(subService.get().getDurationMinutes());
                pricing = calculateTravelPricing(bookAppointmentData, isStylerExist.get(), price);
            } catch(NumberFormatException ex){
                return Responses.errorResponse(response, "Invalid service for this stylist");
            }

            List<String> activeStatuses = Arrays.asList("1", "3");
            boolean canonicalDuplicate = !bookAppointmentRepo
                    .findByUserIdAndStylerIdAndAppointmentDateValueAndAppointmentStartTimeAndStatusIn(
                            bookAppointmentData.getUserId(), bookAppointmentData.getStylerId(),
                            appointmentDate, appointmentStart, activeStatuses)
                    .isEmpty();
            boolean legacyDuplicate = !bookAppointmentRepo.findDuplicateBooking(
                    bookAppointmentData.getUserId(), bookAppointmentData.getStylerId(),
                    bookAppointmentData.getAppointmentDate(), bookAppointmentData.getArrivalTime()).isEmpty();
            if(canonicalDuplicate || legacyDuplicate){
                return Responses.errorResponse(response, "You already have a booking request for this date and time");
            }
            if(!availabilityService.isWindowFree(bookAppointmentData.getStylerId(),
                    bookAppointmentData.getAppointmentDate(), bookAppointmentData.getArrivalTime(), durationMinutes)){
                return Responses.errorResponse(response, "The stylist already has a booking in this time window — please pick a different time");
            }
            if(!availabilityService.timeWithinAvailability(bookAppointmentData.getStylerId(),
                    bookAppointmentData.getAppointmentDate(), bookAppointmentData.getArrivalTime(), durationMinutes)){
                return Responses.errorResponse(response, "The stylist is not available at this time — please pick a time within their working hours");
            }
            if(!availabilityService.isDateNotException(bookAppointmentData.getStylerId(), bookAppointmentData.getAppointmentDate())){
                return Responses.errorResponse(response, "The stylist is unavailable on this date — please choose another day");
            }

            bookAppointmentData.setPrice(pricing.totalPrice);
            BookAppointmentEntity appointment = new BookAppointmentEntity(bookAppointmentData, durationMinutes);
            appointment.setServicePrice(pricing.servicePrice);
            appointment.setTravelFee(pricing.travelFee);
            appointment.setIncludedTravelKm(pricing.includedTravelKm);
            appointment.setTravelDistanceKm(pricing.travelDistanceKm);
            appointment.setBillableTravelKm(pricing.billableTravelKm);
            appointment.setBaseTravelFee(pricing.baseTravelFee);
            appointment.setPrice(pricing.totalPrice);
            // Snapshot the commission at booking time so the payout is deterministic
            // even if the admin later changes the platform rate.
            appointment.setCommissionPercent(effectiveCommissionPercent());

            // Stripe: authorize near-term bookings immediately with the one-time
            // card the booking modal collected via Elements. We never persist the
            // card for near-term bookings — the authorization is one-off. Far-future
            // bookings keep the scheduler: we persist only a Stripe token reference
            // (customer + payment-method ids, never the card itself) so the payment
            // scheduler can authorize inside Stripe's safe card-authorization window.
            if(stripeService.isConfigured()){
                String pm = bookAppointmentData.getPaymentMethodId();
                UserEntity buyer = isUserExist.get();
                if(pm == null || pm.trim().isEmpty()){
                    return Responses.paymentErrorResponse(response, "NO_PAYMENT_METHOD",
                            "A card is required to confirm this booking");
                }
                String connectAccountId = isStylerExist.get().getStripeConnectAccountId();
                String destination = connectAccountId == null || connectAccountId.isBlank() ? null : connectAccountId;
                long amountCents = MoneyUtils.centsFromPrice(pricing.totalPrice);

                if(shouldAuthorizeNow(appointmentDate, appointmentStart)){
                    // Near-term: authorize immediately with the one-time payment method.
                    // Reuse an existing Stripe customer id if the user has one; otherwise
                    // create one purely to host the authorization (no card persisted).
                    try {
                        String customerId = cardDetailsRepo.findByUserId(bookAppointmentData.getUserId())
                                .map(CardDetailsEntity::getStripeCustomerId)
                                .filter(cid -> cid != null && !cid.isEmpty())
                                .orElseGet(() -> {
                                    try {
                                        return stripeService.getOrCreateCustomer(null,
                                                buyer.getEmailAddress(),
                                                (buyer.getFirstname() + " " + buyer.getLastname()).trim()).getId();
                                    } catch(Exception e){
                                        LOG.warning("Stripe customer create failed: " + e.getMessage());
                                        return null;
                                    }
                                });
                        if(customerId == null){
                            return Responses.paymentErrorResponse(response, "PAYMENT_ERROR",
                                    "Payment could not be authorized — please try again");
                        }
                        PaymentIntent intent = stripeService.authorizeBookingPayment(
                                customerId, pm, amountCents, appointment.getAppointmentId(),
                                destination, commissionCents(amountCents));
                        appointment.setPaymentIntentId(intent.getId());
                        appointment.setPaymentStatus("AUTHORIZED");
                        appointment.setPaymentAmount(pricing.totalPrice);
                        appointment.setPaymentAuthorizationDueAt(null);
                    } catch(com.stripe.exception.CardException ex){
                        if("expired_card".equals(ex.getCode())){
                            return Responses.paymentErrorResponse(response, "CARD_EXPIRED",
                                    "Your card has expired. Please try another card.");
                        }
                        String detail = ex.getDeclineCode() == null || ex.getDeclineCode().isBlank()
                                ? "" : " (" + ex.getDeclineCode().replace('_', ' ') + ")";
                        return Responses.paymentErrorResponse(response, "CARD_DECLINED",
                                "Your card was declined" + detail + ". Please try another card.");
                    } catch(com.stripe.exception.StripeException ex){
                        LOG.warning("Payment authorization failed: " + ex.getMessage());
                        return Responses.paymentErrorResponse(response, "PAYMENT_ERROR",
                                "Payment could not be authorized — please check your card or try again");
                    }
                } else {
                    // Far-future: schedule the authorization. Persist a Stripe token
                    // reference (never the raw card) so the scheduler can authorize
                    // within the window; capture-on-acceptance still uses the intent id.
                    try {
                        String existingCustomerId = cardDetailsRepo.findByUserId(bookAppointmentData.getUserId())
                                .map(CardDetailsEntity::getStripeCustomerId)
                                .filter(cid -> cid != null && !cid.isEmpty())
                                .orElse(null);
                        Customer customer = stripeService.getOrCreateCustomer(existingCustomerId,
                                buyer.getEmailAddress(),
                                (buyer.getFirstname() + " " + buyer.getLastname()).trim());
                        upsertPaymentReference(bookAppointmentData.getUserId(), customer.getId(), pm);
                        appointment.setPaymentStatus("PAYMENT_SCHEDULED");
                        appointment.setPaymentAmount(pricing.totalPrice);
                        appointment.setPaymentAuthorizationDueAt(paymentAuthorizationDueAt(appointmentDate, appointmentStart));
                    } catch(com.stripe.exception.StripeException ex){
                        LOG.warning("Payment reference save failed: " + ex.getMessage());
                        return Responses.paymentErrorResponse(response, "PAYMENT_ERROR",
                                "Payment could not be set up — please try again");
                    }
                }
            }
            bookAppointmentRepo.saveAndFlush(appointment);
            reserveBookingSlots(appointment, appointmentDate, appointmentStart, durationMinutes);
            auditService.audit(bookAppointmentData.getUserId(), "CUSTOMER", "CREATE_BOOKING", "APPOINTMENT",
                    appointment.getAppointmentId(), "Booking request created");

            notificationService.sendAppointmentNotification(appointment, "Request",
                    "Booking request received",
                    "Your booking request has been received and is awaiting the professional's confirmation.",
                    "New booking request",
                    "A client has requested an appointment. Please confirm or decline it from your dashboard.");

            // The stylist's profile shows booked slots — refresh it for the next viewer.
            evict(ReadCacheService.KEY_STYLER_APPOINTMENTS + bookAppointmentData.getStylerId());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Appointment booked successfully");
            response.setData(EMPTY_DATA);

            // Store response for idempotency replay on duplicate requests
            if (idempotencyClaim != null && idempotencyClaim.isAcquired() && idempotencyService != null
                    && bookAppointmentData.getIdempotencyKey() != null) {
                try {
                    idempotencyService.storeResponse("book-appointment", bookAppointmentData.getUserId(),
                            bookAppointmentData.getIdempotencyKey(), objectMapper.writeValueAsString(response));
                } catch (Exception ignored) { }
            }
        }
        catch(DataIntegrityViolationException ex){
            // Let the transactional proxy roll back the appointment and slot
            // rows together. The controller advice returns the conflict response.
            LOG.warning("Booking slot collision: " + ex.getMessage());
            if (idempotencyClaim != null && idempotencyClaim.isAcquired() && idempotencyService != null) {
                idempotencyService.release(idempotencyClaim);
            }
            throw ex;
        }
        catch(DateTimeParseException ex){
            if (idempotencyClaim != null && idempotencyClaim.isAcquired() && idempotencyService != null) {
                idempotencyService.release(idempotencyClaim);
            }
            return Responses.errorResponse(response, "Invalid appointment date or arrival time");
        }
        catch(IllegalArgumentException ex){
            return Responses.errorResponse(response, ex.getMessage());
        }
        return response;
    }

    private boolean shouldAuthorizeNow(LocalDate appointmentDate, LocalTime appointmentStart){
        ZonedDateTime scheduled = ZonedDateTime.of(appointmentDate, appointmentStart, applicationZone());
        return !scheduled.isAfter(ZonedDateTime.now(applicationZone()).plusDays(paymentAuthorizationWindowDays));
    }

    private LocalDateTime paymentAuthorizationDueAt(LocalDate appointmentDate, LocalTime appointmentStart){
        ZonedDateTime scheduled = ZonedDateTime.of(appointmentDate, appointmentStart, applicationZone())
                .minusHours(paymentAuthorizationLeadHours);
        ZonedDateTime now = ZonedDateTime.now(applicationZone());
        return (scheduled.isBefore(now) ? now : scheduled).toLocalDateTime();
    }

    private LocalDateTime paymentAuthorizationDueAt(BookAppointmentEntity appointment){
        if(appointment.getPaymentAuthorizationDueAt() != null){
            return appointment.getPaymentAuthorizationDueAt();
        }
        LocalDate date = appointment.getAppointmentDateValue();
        LocalTime start = appointment.getAppointmentStartTime();
        if(date == null || start == null){
            date = availabilityService.parseBookingDate(appointment.getAppointmentDate());
            start = availabilityService.parseBookingTime(appointment.getArrivalTime());
        }
        return paymentAuthorizationDueAt(date, start);
    }

    /**
     * Authorizes a scheduled booking exactly once. When captureAfterAuthorization
     * is true, the stylist has already accepted and the authorized amount is
     * captured immediately after Stripe confirms the hold.
     */
    @Transactional(rollbackFor = Exception.class)
    PaymentAttemptResult authorizeScheduledPayment(BookAppointmentEntity appointment, boolean captureAfterAuthorization){
        try {
            Optional<CardDetailsEntity> card = cardDetailsRepo.findByUserId(appointment.getUserId());
            if(card.isEmpty() || card.get().getStripeCustomerId() == null || card.get().getStripePaymentMethodId() == null){
                appointment.setPaymentStatus("PAYMENT_FAILED");
                appointment.setPaymentFailureCode("NO_PAYMENT_METHOD");
                bookAppointmentRepo.save(appointment);
                return PaymentAttemptResult.failure("NO_PAYMENT_METHOD", "Please add a payment method to complete this booking");
            }
            Optional<StylerEntity> styler = stylerRepo.findByStylerId(appointment.getStylerId());
            String destination = styler.filter(s -> s.getStripeConnectAccountId() != null && !s.getStripeConnectAccountId().isBlank())
                    .map(StylerEntity::getStripeConnectAccountId).orElse(null);
            long amountCents = MoneyUtils.centsFromPrice(appointment.getPaymentAmount() == null ? appointment.getPrice() : appointment.getPaymentAmount());
            PaymentIntent intent = stripeService.authorizeBookingPayment(
                    card.get().getStripeCustomerId(), card.get().getStripePaymentMethodId(),
                    amountCents, appointment.getAppointmentId(), destination, commissionCents(amountCents));
            appointment.setPaymentIntentId(intent.getId());
            appointment.setPaymentStatus("AUTHORIZED");
            appointment.setPaymentFailureCode(null);
            appointment.setPaymentAuthorizationDueAt(null);
            if(captureAfterAuthorization){
                stripeService.captureBookingPayment(intent.getId());
                appointment.setPaymentStatus("CAPTURED");
                sendPaymentReceipt(appointment);
            }
            bookAppointmentRepo.save(appointment);
            return PaymentAttemptResult.success();
        } catch(com.stripe.exception.CardException ex){
            String code = "authentication_required".equals(ex.getCode()) ? "PAYMENT_REQUIRES_ACTION"
                    : "expired_card".equals(ex.getCode()) ? "CARD_EXPIRED" : "CARD_DECLINED";
            appointment.setPaymentStatus("PAYMENT_REQUIRES_ACTION".equals(code) ? "PAYMENT_REQUIRES_ACTION" : "PAYMENT_FAILED");
            appointment.setPaymentFailureCode(code);
            bookAppointmentRepo.save(appointment);
            String message = "PAYMENT_REQUIRES_ACTION".equals(code)
                    ? "Please return to RapidStylers to confirm your payment."
                    : "Your saved card could not be charged. Please update it and try again.";
            return PaymentAttemptResult.failure(code, message);
        } catch(Exception ex){
            LOG.warning("Scheduled payment authorization failed: " + ex.getMessage());
            appointment.setPaymentStatus("PAYMENT_FAILED");
            appointment.setPaymentFailureCode("PAYMENT_ERROR");
            bookAppointmentRepo.save(appointment);
            return PaymentAttemptResult.failure("PAYMENT_ERROR", "Payment could not be completed. Please try again.");
        }
    }

    @Scheduled(fixedDelayString = "${app.booking.payment-scheduler-delay-ms:60000}")
    @Transactional(rollbackFor = Exception.class)
    public void processDuePaymentAuthorizations(){
        if(!stripeService.isConfigured()) return;
        LocalDateTime now = LocalDateTime.now(applicationZone());
        List<BookAppointmentEntity> due = bookAppointmentRepo
                .findByPaymentStatusAndPaymentAuthorizationDueAtBefore("PAYMENT_ACCEPTED_SCHEDULED", now);
        for(BookAppointmentEntity appointment : due){
            if(!"3".equals(appointment.getStatus())) continue;
            authorizeScheduledPayment(appointment, true);
        }
    }

    private static class PaymentAttemptResult {
        private final boolean success;
        private final String code;
        private final String message;
        private PaymentAttemptResult(boolean success, String code, String message){
            this.success = success;
            this.code = code;
            this.message = message;
        }
        private static PaymentAttemptResult success(){ return new PaymentAttemptResult(true, null, null); }
        private static PaymentAttemptResult failure(String code, String message){ return new PaymentAttemptResult(false, code, message); }
    }

    private int serviceDuration(Integer durationMinutes){
        int duration = durationMinutes == null ? DEFAULT_SERVICE_DURATION_MINUTES : durationMinutes;
        if(duration < MIN_SERVICE_DURATION_MINUTES || duration > MAX_SERVICE_DURATION_MINUTES
                || duration % SLOT_GRANULARITY_MINUTES != 0){
            throw new IllegalArgumentException("This service has an invalid duration configuration");
        }
        return duration;
    }

    private TravelPricing calculateTravelPricing(BookAppointmentData data, StylerEntity styler, String rawServicePrice){
        int people = parsePeople(data.getNoOfPeople());
        BigDecimal serviceUnitPrice = MoneyUtils.amount(rawServicePrice);
        BigDecimal servicePrice = serviceUnitPrice.multiply(BigDecimal.valueOf(people)).setScale(2, RoundingMode.HALF_UP);
        double includedKm = styler.getIncludedTravelKm() == null ? 15.0 : styler.getIncludedTravelKm();
        BigDecimal baseTravelFee = MoneyUtils.amount(styler.getBaseTravelFee() == null ? "0.00" : styler.getBaseTravelFee());
        double distanceKm = 0.0;
        double billableKm = 0.0;
        BigDecimal travelFee = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        if(isHomeService(data.getServiceTime())){
            if(data.getTravelDistanceKm() == null){
                throw new IllegalArgumentException("Travel distance is required for home service bookings");
            }
            distanceKm = roundKm(data.getTravelDistanceKm());
            if(distanceKm < 0){
                throw new IllegalArgumentException("Travel distance cannot be negative");
            }
            billableKm = roundKm(Math.max(0.0, distanceKm - includedKm));
            // Flat home-visit fee beyond the included radius — no per-km pricing.
            if(billableKm > 0){
                travelFee = baseTravelFee.setScale(2, RoundingMode.HALF_UP);
            }
        }

        BigDecimal total = servicePrice.add(travelFee).setScale(2, RoundingMode.HALF_UP);
        return new TravelPricing(
                MoneyUtils.money(servicePrice),
                MoneyUtils.money(travelFee),
                includedKm,
                distanceKm,
                billableKm,
                MoneyUtils.money(baseTravelFee),
                MoneyUtils.money(total));
    }

    private int parsePeople(String noOfPeople){
        try {
            int people = Integer.parseInt(noOfPeople == null ? "1" : noOfPeople.trim());
            return Math.max(people, 1);
        } catch(NumberFormatException ex){
            return 1;
        }
    }

    private boolean isHomeService(String serviceTime){
        return "homeService".equalsIgnoreCase(serviceTime);
    }

    private double roundKm(double value){
        return Math.round(value * 10.0) / 10.0;
    }

    public void handleStripeWebhook(String payload, String signatureHeader){
        paymentOpsService.handleStripeWebhook(payload, signatureHeader);
    }

    private double effectiveCommissionPercent(){
        return paymentOpsService.effectiveCommissionPercent();
    }

    private long commissionCents(long amountCents){
        return paymentOpsService.commissionCents(amountCents);
    }

    long commissionCents(long amountCents, double percent){
        return paymentOpsService.commissionCents(amountCents, percent);
    }

    double effectiveCommissionPercentForBooking(BookAppointmentEntity appointment){
        return paymentOpsService.effectiveCommissionPercentForBooking(appointment);
    }

    void handleAccountUpdated(Account account){
        paymentOpsService.handleAccountUpdated(account);
    }

    private static class TravelPricing {
        private final String servicePrice;
        private final String travelFee;
        private final Double includedTravelKm;
        private final Double travelDistanceKm;
        private final Double billableTravelKm;
        private final String baseTravelFee;
        private final String totalPrice;

        private TravelPricing(String servicePrice, String travelFee, Double includedTravelKm,
                              Double travelDistanceKm, Double billableTravelKm,
                              String baseTravelFee, String totalPrice) {
            this.servicePrice = servicePrice;
            this.travelFee = travelFee;
            this.includedTravelKm = includedTravelKm;
            this.travelDistanceKm = travelDistanceKm;
            this.billableTravelKm = billableTravelKm;
            this.baseTravelFee = baseTravelFee;
            this.totalPrice = totalPrice;
        }

        private Map<String, Object> toMap(){
            Map<String, Object> data = new HashMap<>();
            data.put("servicePrice", servicePrice);
            data.put("travelFee", travelFee);
            data.put("includedTravelKm", includedTravelKm);
            data.put("travelDistanceKm", travelDistanceKm);
            data.put("billableTravelKm", billableTravelKm);
            data.put("baseTravelFee", baseTravelFee);
            data.put("totalPrice", totalPrice);
            return data;
        }
    }

    private void reserveBookingSlots(BookAppointmentEntity appointment, LocalDate date, LocalTime start, int durationMinutes){
        List<BookingSlotLockEntity> locks = new ArrayList<>();
        for(int offset = 0; offset < durationMinutes; offset += SLOT_GRANULARITY_MINUTES){
            LocalDateTime slotDateTime = LocalDateTime.of(date, start).plusMinutes(offset);
            locks.add(new BookingSlotLockEntity(appointment.getStylerId(),
                    slotDateTime.toLocalDate(), slotDateTime.toLocalTime(), appointment.getAppointmentId()));
        }
        // Unique(styler_id, date, slot_start) is the final database-level race guard.
        bookingSlotLockRepo.saveAllAndFlush(locks);
    }

    private boolean appointmentStartHasPassed(BookAppointmentEntity appointment){
        LocalDate date = appointment.getAppointmentDateValue();
        LocalTime start = appointment.getAppointmentStartTime();
        if(date == null || start == null){
            try {
                date = availabilityService.parseBookingDate(appointment.getAppointmentDate());
                start = availabilityService.parseBookingTime(appointment.getArrivalTime());
            } catch(Exception ex){
                return false;
            }
        }
        ZonedDateTime scheduled = ZonedDateTime.of(date, start, applicationZone());
        return !ZonedDateTime.now(applicationZone()).isBefore(scheduled.plusMinutes(completionGraceMinutes));
    }

    private void autoRefundCapturedPayment(BookAppointmentEntity appointment, String reason, String actorId){
        paymentOpsService.autoRefundCapturedPayment(appointment, reason, actorId);
    }

    private ZoneId applicationZone(){
        try {
            return ZoneId.of(appTimeZone == null || appTimeZone.isBlank() ? "America/Edmonton" : appTimeZone);
        } catch(Exception ex){
            return ZoneId.of("America/Edmonton");
        }
    }

    public BaseResponse stylerAppointments(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerId(stylerId);
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            List<BookAppointmentEntity> appointments = bookAppointmentRepo.findByStylerId(stylerId);
            List<Object> result = new ArrayList<>();
            for(BookAppointmentEntity entity : appointments){
                result.add(dtoService.appointmentDTO(entity));
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch(Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse acceptAppointment(String stylerId, String appointmentId){
        return acceptAppointment(stylerId, appointmentId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse acceptAppointment(String stylerId, String appointmentId, String decisionNote){
        return transitionAppointment(stylerId, ActorRole.STYLER, appointmentId, "3", new String[]{"1"},
                "Appointment confirmed", decisionNote);
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse declineAppointment(String stylerId, String appointmentId){
        return declineAppointment(stylerId, appointmentId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse declineAppointment(String stylerId, String appointmentId, String decisionNote){
        return transitionAppointment(stylerId, ActorRole.STYLER, appointmentId, "2", new String[]{"1"},
                "Appointment rejected", decisionNote);
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse completeAppointment(String stylerId, String appointmentId){
        return transitionAppointment(stylerId, ActorRole.STYLER, appointmentId, "0", new String[]{"3"}, "Appointment marked as completed");
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse cancelAppointment(String userId, String appointmentId){
        return transitionAppointment(userId, ActorRole.CUSTOMER, appointmentId, "4", new String[]{"1", "3"}, "Appointment cancelled");
    }

    /**
     * Stylist-initiated cancellation of their own appointment. Covers the
     * completes-then-cancels edge case: a booking already marked completed
     * (payment captured) is cancelled by the stylist who completed it, and
     * the captured payment is refunded automatically — exactly once.
     */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse stylerCancelAppointment(String stylerId, String appointmentId){
        return transitionAppointment(stylerId, ActorRole.STYLER, appointmentId, "4", new String[]{"3", "0"}, "Appointment cancelled");
    }

    /** Customer retry path for a deferred or failed payment. */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse retryAppointmentPayment(String userId, String appointmentId){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<BookAppointmentEntity> found = bookAppointmentRepo.findByAppointmentId(appointmentId);
            if(found.isEmpty()) return Responses.errorResponse(response, "Invalid Appointment Id");
            BookAppointmentEntity appointment = found.get();
            if(!userId.equals(appointment.getUserId())) return Responses.errorResponse(response, "You do not have permission to update this appointment");
            if(!Arrays.asList("PAYMENT_FAILED", "PAYMENT_REQUIRES_ACTION", "PAYMENT_ACCEPTED_SCHEDULED").contains(appointment.getPaymentStatus())){
                return Responses.errorResponse(response, "This appointment does not need a payment retry");
            }
            PaymentAttemptResult payment = authorizeScheduledPayment(appointment, "3".equals(appointment.getStatus()));
            if(!payment.success){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage(payment.message);
                response.setData(Collections.singletonMap("paymentError", payment.code));
                return response;
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Payment completed successfully");
            response.setData(EMPTY_DATA);
        } catch(Exception ex){
            LOG.warning("Payment retry failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Payment could not be completed. Please try again.");
        }
        return response;
    }

    /** List failed outbox events for admin review. */
    public BaseResponse listFailedOutboxEvents() {
        BaseResponse response = new BaseResponse(true);
        try {
            List<com.macrotel.rapidstylers.outbox.OutboxEventEntity> failed =
                outboxEventRepo.findByStatus(com.macrotel.rapidstylers.outbox.OutboxStatus.FAILED);
            List<Map<String, Object>> result = new ArrayList<>();
            for (var event : failed) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", event.getId());
                map.put("eventId", event.getEventId());
                map.put("eventType", event.getEventType() != null ? event.getEventType().name() : "UNKNOWN");
                map.put("aggregateType", event.getAggregateType());
                map.put("aggregateId", event.getAggregateId());
                map.put("status", event.getStatus().name());
                map.put("attempts", event.getAttempts());
                map.put("lastError", event.getLastError());
                map.put("createdAt", event.getCreatedAt().toString());
                result.add(map);
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        } catch (Exception ex) {
            LOG.warning("Failed to list outbox events: " + ex.getMessage());
        }
        return response;
    }

    /** Reset a failed outbox event back to PENDING for retry. */
    public BaseResponse retryFailedOutboxEvent(Long eventId, String adminId) {
        BaseResponse response = new BaseResponse(true);
        try {
            var event = outboxEventRepo.findById(eventId);
            if (event.isEmpty()) {
                return Responses.errorResponse(response, "Event not found");
            }
            var entity = event.get();
            if (entity.getStatus() != com.macrotel.rapidstylers.outbox.OutboxStatus.FAILED) {
                return Responses.errorResponse(response, "Only failed events can be retried");
            }
            entity.setStatus(com.macrotel.rapidstylers.outbox.OutboxStatus.PENDING);
            entity.setAttempts(0);
            entity.setLastError(null);
            entity.setNextAttemptAt(LocalDateTime.now());
            outboxEventRepo.save(entity);
            auditService.audit(adminId, "ADMIN", "RETRY_OUTBOX_EVENT", "OUTBOX_EVENT",
                    String.valueOf(eventId), "Admin retried failed outbox event");
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Event queued for retry");
            response.setData(EMPTY_DATA);
        } catch (Exception ex) {
            LOG.warning("Failed to retry outbox event: " + ex.getMessage());
        }
        return response;
    }

    public BaseResponse adminRefund(String adminId, RefundRequestData data){
        return paymentOpsService.adminRefund(adminId, data);
    }

    public BaseResponse adminRefunds(String adminId){
        return paymentOpsService.adminRefunds(adminId);
    }

    private enum ActorRole { STYLER, CUSTOMER }

    /**
     * Loads an appointment, verifies the actor owns the correct side of it,
     * and applies a status transition only when the current state is allowed.
     * Status codes: 1 pending, 3 accepted, 2 rejected, 0 completed, 4 cancelled.
     */
    private BaseResponse transitionAppointment(String ownerId, ActorRole actorRole, String appointmentId,
                                               String newStatus, String[] allowedFrom, String successMessage){
        return transitionAppointment(ownerId, actorRole, appointmentId, newStatus, allowedFrom, successMessage, null);
    }

    private BaseResponse transitionAppointment(String ownerId, ActorRole actorRole, String appointmentId,
                                               String newStatus, String[] allowedFrom, String successMessage,
                                               String decisionNote){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<BookAppointmentEntity> isExist = bookAppointmentRepo.findByAppointmentIdForUpdate(appointmentId);
            if(isExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Appointment Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            BookAppointmentEntity appointment = isExist.get();
            boolean ownsAppointment = actorRole == ActorRole.STYLER
                    ? ownerId.equals(appointment.getStylerId())
                    : ownerId.equals(appointment.getUserId());
            if(!ownsAppointment){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("You do not have permission to update this appointment");
                response.setData(EMPTY_DATA);
                return response;
            }
            if("0".equals(newStatus) && !appointmentStartHasPassed(appointment)){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("An appointment can only be completed after its scheduled start time");
                response.setData(EMPTY_DATA);
                return response;
            }
            boolean allowed = false;
            for(String from : allowedFrom){
                if(from.equals(appointment.getStatus())){
                    allowed = true;
                    break;
                }
            }
            if(!allowed){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("This appointment cannot be updated from its current state");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Completed bookings can only be cancelled by the stylist within a
            // short window of completion — after that, refunds go through admin.
            if("4".equals(newStatus) && "0".equals(appointment.getStatus())){
                LocalDateTime completedAt = appointment.getCompletedAt();
                if(completedAt == null){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("This completed appointment cannot be cancelled — completion time is unknown. Please contact support.");
                    response.setData(EMPTY_DATA);
                    return response;
                }
                if(completedAt.isBefore(LocalDateTime.now(applicationZone()).minusHours(stylerCancelWindowHours))){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("Appointments can only be cancelled within " + stylerCancelWindowHours + " hours of completion");
                    response.setData(EMPTY_DATA);
                    return response;
                }
            }
            if("3".equals(newStatus) && stripeService.isConfigured()
                    && "PAYMENT_SCHEDULED".equals(appointment.getPaymentStatus())
                    && paymentAuthorizationDueAt(appointment).isAfter(LocalDateTime.now(applicationZone()))){
                // The stylist can accept an advance booking before the payment
                // window opens. The scheduler will authorize it later.
                appointment.setPaymentStatus("PAYMENT_ACCEPTED_SCHEDULED");
                appointment.setPaymentFailureCode(null);
            } else if("3".equals(newStatus) && stripeService.isConfigured()
                    && "PAYMENT_SCHEDULED".equals(appointment.getPaymentStatus())){
                PaymentAttemptResult payment = authorizeScheduledPayment(appointment, true);
                if(!payment.success){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage(payment.message);
                    response.setData(Collections.singletonMap("paymentError", payment.code));
                    return response;
                }
            }
            if("3".equals(newStatus) && appointment.getPaymentIntentId() != null && stripeService.isConfigured()
                    && "AUTHORIZED".equals(appointment.getPaymentStatus())){
                try {
                    stripeService.captureBookingPayment(appointment.getPaymentIntentId());
                    appointment.setPaymentStatus("CAPTURED");
                    sendPaymentReceipt(appointment);
                } catch(Exception ex){
                    LOG.warning("Payment capture failed: " + ex.getMessage());
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("Payment capture failed — please try again or update your payment method.");
                    response.setData(EMPTY_DATA);
                    return response;
                }
            }
            if("0".equals(newStatus)){
                appointment.setCompletedAt(LocalDateTime.now(applicationZone()));
            }
            appointment.setStatus(newStatus);
            if(decisionNote != null && !decisionNote.isBlank()){
                appointment.setStylerNote(AppUtils.sanitizeText(decisionNote.trim()));
            }
            bookAppointmentRepo.save(appointment);
            if("2".equals(newStatus) || "4".equals(newStatus)){
                // Rejected and customer-cancelled bookings release their held slots.
                bookingSlotLockRepo.deleteByAppointmentId(appointment.getAppointmentId());
                // A captured payment is refunded automatically; otherwise the
                // authorized hold is released so the card is never charged.
                if(appointment.getPaymentIntentId() != null && stripeService.isConfigured()){
                    if("CAPTURED".equals(appointment.getPaymentStatus())){
                        autoRefundCapturedPayment(appointment,
                                "Appointment " + ("2".equals(newStatus) ? "rejected" : "cancelled"), "SYSTEM");
                    } else {
                        try {
                            stripeService.releaseBookingPayment(appointment.getPaymentIntentId());
                            appointment.setPaymentStatus("RELEASED");
                            bookAppointmentRepo.save(appointment);
                        } catch(Exception ex){
                            LOG.warning("Payment hold release failed: " + ex.getMessage());
                        }
                    }
                }
            }
            if("0".equals(newStatus)){
                if(stripeService.isConfigured() && "CAPTURED".equals(appointment.getPaymentStatus())
                        && appointment.getStripeTransferId() == null){
                    try {
                        Optional<StylerEntity> styler = stylerRepo.findByStylerId(appointment.getStylerId());
                        String destination = styler.map(StylerEntity::getStripeConnectAccountId).orElse(null);
                        long totalCents = MoneyUtils.centsFromPrice(appointment.getPaymentAmount() == null
                                ? appointment.getPrice() : appointment.getPaymentAmount());
                        double percent = effectiveCommissionPercentForBooking(appointment);
                        long commission = commissionCents(totalCents, percent);
                        long stylistShareCents = totalCents - commission;
                        appointment.setCommissionPercent(appointment.getCommissionPercent() == null
                                ? percent : appointment.getCommissionPercent());
                        appointment.setPlatformFeeCents(commission);
                        appointment.setStylistShareCents(stylistShareCents);
                        if(destination != null && stylistShareCents > 0){
                            com.stripe.model.Transfer transfer = stripeService.transferStylistShare(
                                    destination, stylistShareCents, appointment.getAppointmentId());
                            if(transfer != null){
                                appointment.setStripeTransferId(transfer.getId());
                            }
                        }
                        // Persist the fee breakdown (and transfer id, previously never saved here).
                        bookAppointmentRepo.save(appointment);
                    } catch(Exception ex){
                        LOG.warning("Stylist payout transfer failed: " + ex.getMessage());
                        response.setStatusCode(ERROR_STATUS_CODE);
                        response.setMessage("Payout transfer failed — the appointment was not completed. Please contact support.");
                        response.setData(EMPTY_DATA);
                        return response;
                    }
                }
                loyaltyService.awardCompletionPoints(appointment.getUserId(), appointment.getAppointmentId());
            }
            auditService.audit(ownerId, actorRole == ActorRole.STYLER ? "STYLER" : "CUSTOMER", "APPOINTMENT_" + newStatus,
                    "APPOINTMENT", appointmentId, successMessage);

            // Notify both parties about the outcome.
            if("3".equals(newStatus)){
                notificationService.sendAppointmentNotification(appointment, "Confirmed",
                        "Your appointment has been confirmed",
                        "The professional has accepted your booking request. See you at your appointment!",
                        "Appointment confirmed",
                        "You have confirmed this client's booking request.");
            } else if("2".equals(newStatus)){
                notificationService.sendAppointmentNotification(appointment, "Declined",
                        "Your appointment request was declined",
                        "The professional could not take this booking. Please book another slot.",
                        "Appointment declined",
                        "You have declined this client's booking request.");
            } else if("0".equals(newStatus)){
                notificationService.sendAppointmentNotification(appointment, "Completed",
                        "Your appointment has been completed",
                        "Thanks for using RapidStylers. We hope you enjoyed your session — a review is welcome.",
                        "Appointment completed",
                        "You have marked this appointment as completed.");
            } else if("4".equals(newStatus)){
                notificationService.sendAppointmentNotification(appointment, "Cancelled",
                        "Your appointment was cancelled",
                        "Your appointment has been cancelled. Check your dashboard for updates.",
                        "Appointment cancelled",
                        actorRole == ActorRole.STYLER
                                ? "You have cancelled this appointment."
                                : "This appointment has been cancelled by the client.");
            }

            // Booked-slot cache is display data; evict so the next profile view is fresh.
            evict(ReadCacheService.KEY_STYLER_APPOINTMENTS + appointment.getStylerId());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(successMessage);
            response.setData(EMPTY_DATA);
        }
        catch (RuntimeException ex){
            // Persistence failures must escape the transaction so status changes
            // and slot releases roll back together.
            LOG.warning(ex.getMessage());
            throw ex;
        }
        catch(Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listUserAppointment(String userId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<UserEntity> isUserExist = userRepo.findByUserId(userId);
            if(isUserExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid User Id, Kindly create account");
                response.setData(EMPTY_DATA);
                return response;
            }
            List<BookAppointmentEntity> getUserAppointment = bookAppointmentRepo.findByUserId(userId);
            List<Object> result = new ArrayList<>();
           for(BookAppointmentEntity bookAppointmentEntity : getUserAppointment){
               result.add(dtoService.appointmentDTO(bookAppointmentEntity));
           }
           response.setStatusCode(SUCCESS_STATUS_CODE);
           response.setMessage(SUCCESS_MESSAGE);
           response.setData(result);
        }
        catch(Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listUserPendingAppointment(String userId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<UserEntity> isUserExist = userRepo.findByUserId(userId);
            if(isUserExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid User Id, Kindly create account");
                response.setData(EMPTY_DATA);
                return response;
            }
            List<BookAppointmentEntity> getUserAppointment = bookAppointmentRepo.userPendingAppointment(userId);
            List<Object> result = new ArrayList<>();
            for(BookAppointmentEntity bookAppointmentEntity : getUserAppointment){
                result.add(dtoService.appointmentDTO(bookAppointmentEntity));
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch(Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Creates (or reuses) a Stripe Connect Express account for the stylist and
     * returns a hosted onboarding link. returnUrl/refreshUrl come from the
     * frontend (the stylist dashboard) and are where Stripe sends the stylist
     * after finishing or abandoning onboarding.
     */
    public BaseResponse createStylerConnectAccount(String stylerId, String returnUrl, String refreshUrl){
        BaseResponse response = new BaseResponse(true);
        try{
            if(!stripeService.isConfigured()){
                return Responses.errorResponse(response, "Payments are not configured yet");
            }
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(stylerId);
            if(stylerOpt.isEmpty()){
                return Responses.errorResponse(response, "Invalid Styler Id");
            }
            StylerEntity styler = stylerOpt.get();
            String accountId = styler.getStripeConnectAccountId();
            if(accountId == null || accountId.isBlank()){
                Account account = stripeService.createExpressAccount(stylerId, styler.getEmailAddress(),
                        styler.getBusinessName(), styler.getFirstname(), styler.getLastname());
                accountId = account.getId();
                styler.setStripeConnectAccountId(accountId);
                styler.setConnectOnboardingStatus("PENDING");
                stylerRepo.save(styler);
            }
            AccountLink link = stripeService.createAccountLink(accountId, refreshUrl, returnUrl);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", accountId);
            data.put("onboardingUrl", link.getUrl());
            data.put("status", styler.getConnectOnboardingStatus());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch(Exception ex){
            LOG.warning("Connect account setup failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not start payout setup — please try again");
        }
        return response;
    }

    public BaseResponse getStylerPayouts(String stylerId){
        return paymentOpsService.getStylerPayouts(stylerId);
    }

    /**
     * Business summary for a stylist dashboard: real appointment, client,
     * revenue, and popular-service stats (replaces the hardcoded card).
     * Status map: 1 pending, 3 accepted, 0 completed, 2 rejected, 4 cancelled.
     */
    public BaseResponse getStylerBusinessSummary(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(stylerId);
            if(stylerOpt.isEmpty()){
                return Responses.errorResponse(response, "Invalid Styler Id");
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(businessSummaryData(stylerOpt.get()));
        } catch(Exception ex){
            LOG.warning("Business summary failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not load business summary");
        }
        return response;
    }

    /**
     * Admin view: per-stylist business stats for every professional, most
     * appointments first. Reuses the same computation as the stylist dashboard.
     */
    public BaseResponse getAdminStylerBusinessSummaries(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<Map<String, Object>> rows = new ArrayList<>();
            for(StylerEntity styler : stylerRepo.findAll()){
                Map<String, Object> row = new LinkedHashMap<>(businessSummaryData(styler));
                row.put("stylerId", styler.getStylerId());
                row.put("businessName", styler.getBusinessName());
                row.put("name", (styler.getFirstname() + " " + styler.getLastname()).trim());
                row.put("emailAddress", styler.getEmailAddress());
                row.put("verificationStatus", styler.getVerificationStatus());
                rows.add(row);
            }
            rows.sort((a, b) -> Long.compare(((Number) b.get("totalAppointments")).longValue(),
                    ((Number) a.get("totalAppointments")).longValue()));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(rows);
        } catch(Exception ex){
            LOG.warning("Admin business summaries failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not load business summaries");
        }
        return response;
    }

    /**
     * Shared per-stylist stats computation. Revenue counts the captured payment
     * amount when the payment lifecycle is active (paymentStatus CAPTURED),
     * otherwise the completed appointment price. netRevenue is the stylist's
     * share after the platform commission, consistent with the payout page.
     */
    private Map<String, Object> businessSummaryData(StylerEntity styler){
        String stylerId = styler.getStylerId();
        List<BookAppointmentEntity> appointments = bookAppointmentRepo.findByStylerId(stylerId);
        if(appointments == null) appointments = new ArrayList<>();

        long total = appointments.size();
        long pending = 0, confirmed = 0, finished = 0, cancelled = 0;
        Set<String> clients = new HashSet<>();
        BigDecimal grossRevenue = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        Map<String, Integer> serviceCounts = new LinkedHashMap<>();
        Map<String, String> serviceNames = new HashMap<>();
        for(SubServiceEntity service : subServiceRepo.findByStylerId(stylerId)){
            serviceNames.put(String.valueOf(service.getId()), service.getName());
        }

        for(BookAppointmentEntity appointment : appointments){
            if("1".equals(appointment.getStatus())) pending++;
            else if("3".equals(appointment.getStatus())) confirmed++;
            else if("0".equals(appointment.getStatus())) finished++;
            else if("4".equals(appointment.getStatus())) cancelled++;
            if("2".equals(appointment.getStatus()) || "4".equals(appointment.getStatus())) continue;
            if(appointment.getUserId() != null) clients.add(appointment.getUserId());
            if("0".equals(appointment.getStatus())){
                boolean captured = "CAPTURED".equals(appointment.getPaymentStatus());
                grossRevenue = grossRevenue.add(MoneyUtils.amount(
                        captured && appointment.getPaymentAmount() != null
                                ? appointment.getPaymentAmount() : appointment.getPrice()));
            }
            if(appointment.getSubServiceId() != null){
                serviceCounts.merge(appointment.getSubServiceId(), 1, Integer::sum);
            }
        }

        List<Map<String, Object>> popularServices = new ArrayList<>();
        serviceCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", serviceNames.getOrDefault(entry.getKey(), "Service"));
                    row.put("count", entry.getValue());
                    popularServices.add(row);
                });

        BigDecimal commission = grossRevenue.multiply(BigDecimal.valueOf(effectiveCommissionPercent()))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal netRevenue = grossRevenue.subtract(commission);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalAppointments", total);
        data.put("clients", clients.size());
        data.put("pending", pending);
        data.put("confirmed", confirmed);
        data.put("finished", finished);
        data.put("cancelled", cancelled);
        data.put("totalRevenue", MoneyUtils.money(grossRevenue));
        // Listing health, so the dashboard can explain a profile that is not
        // reachable instead of leaving the professional to guess why nobody
        // books. `bookable` is the same gate public search uses.
        data.put("addressOnFile", StylerEligibility.hasStreetAddress(styler));
        data.put("bookable", stylerEligibility.isBookableStyler(styler));
        data.put("totalCommission", MoneyUtils.money(commission));
        data.put("netRevenue", MoneyUtils.money(netRevenue));
        data.put("popularServices", popularServices);
        return data;
    }

    public BaseResponse getStylerConnectStatus(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(stylerId);
            if(stylerOpt.isEmpty()){
                return Responses.errorResponse(response, "Invalid Styler Id");
            }
            StylerEntity styler = stylerOpt.get();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", styler.getStripeConnectAccountId());
            data.put("status", styler.getConnectOnboardingStatus() == null
                    ? "NOT_STARTED" : styler.getConnectOnboardingStatus());
            data.put("disabledReason", styler.getConnectDisabledReason());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch(Exception ex){
            LOG.warning("Connect status lookup failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not load payout status");
        }
        return response;
    }

    /**
     * Admin support view: every stylist's Connect payout status, ordered so
     * problems surface first (REJECTED, then PENDING, NOT_STARTED, COMPLETE).
     * Pure DB read — no Stripe calls, so it stays fast regardless of account count.
     */
    public BaseResponse getAdminStylerConnectStatuses(){
        BaseResponse response = new BaseResponse(true);
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for(StylerEntity styler : stylerRepo.findAll()){
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stylerId", styler.getStylerId());
                row.put("name", (styler.getFirstname() + " " + styler.getLastname()).trim());
                row.put("businessName", styler.getBusinessName());
                row.put("emailAddress", styler.getEmailAddress());
                row.put("verificationStatus", styler.getVerificationStatus() == null
                        ? "PENDING" : styler.getVerificationStatus());
                row.put("accountActive", "0".equals(styler.getStatus()));
                row.put("connectStatus", styler.getConnectOnboardingStatus() == null
                        ? "NOT_STARTED" : styler.getConnectOnboardingStatus());
                row.put("connectAccountId", styler.getStripeConnectAccountId());
                row.put("disabledReason", styler.getConnectDisabledReason());
                row.put("registered", styler.getInsertedDt());
                rows.add(row);
            }
            rows.sort((a, b) -> {
                int rankA = connectStatusRank((String) a.get("connectStatus"));
                int rankB = connectStatusRank((String) b.get("connectStatus"));
                int byStatus = Integer.compare(rankA, rankB);
                if(byStatus != 0) return byStatus;
                return String.valueOf(a.get("stylerId")).compareTo(String.valueOf(b.get("stylerId")));
            });
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(rows);
        } catch(Exception ex){
            LOG.warning("Connect status list failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not load stylist payout statuses");
        }
        return response;
    }

    /** Sort rank for the admin overview: rejected and stuck payouts first. */
    private int connectStatusRank(String status){
        switch(status == null ? "NOT_STARTED" : status){
            case "REJECTED": return 0;
            case "PENDING": return 1;
            case "NOT_STARTED": return 2;
            default: return 3;
        }
    }

    public BaseResponse getCardSetupIntent(String userId){
        BaseResponse response = new BaseResponse(true);
        try{
            if(!stripeService.isConfigured()){
                return Responses.errorResponse(response, "Payments are not configured yet");
            }
            Optional<UserEntity> isUserExist = userRepo.findByUserId(userId);
            if(isUserExist.isEmpty()){
                return Responses.errorResponse(response, "Invalid User Id");
            }
            UserEntity user = isUserExist.get();
            Optional<CardDetailsEntity> existing = cardDetailsRepo.findByUserId(userId);
            String customerId = existing.filter(c -> c.getStripeCustomerId() != null)
                    .map(CardDetailsEntity::getStripeCustomerId).orElse(null);
            Customer customer = stripeService.getOrCreateCustomer(customerId, user.getEmailAddress(),
                    (user.getFirstname() + " " + user.getLastname()).trim());
            String clientSecret = stripeService.createSetupIntentClientSecret(customer.getId());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("clientSecret", clientSecret);
            data.put("stripeCustomerId", customer.getId());
            response.setData(data);
        } catch(Exception ex){
            LOG.warning("Setup intent failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not start card setup — please try again");
        }
        return response;
    }

    /**
     * Persists only the Stripe token references needed for a far-future booking
     * so the payment scheduler can authorize when the window opens. Never stores
     * the card itself (number/expiry/cvv) — only customer + payment-method ids.
     */
    private void upsertPaymentReference(String userId, String stripeCustomerId, String paymentMethodId){
        Optional<CardDetailsEntity> existing = cardDetailsRepo.findByUserId(userId);
        CardDetailsEntity entity = existing.orElseGet(CardDetailsEntity::new);
        entity.setUserId(userId);
        entity.setStripeCustomerId(stripeCustomerId);
        entity.setStripePaymentMethodId(paymentMethodId);
        cardDetailsRepo.save(entity);
    }

    /**
     * Persists only Stripe references and display metadata for a card collected
     * inside Stripe's Elements iframe. Raw card numbers, CVVs and expiry dates
     * are never sent to or stored by this application.
     */
    public BaseResponse updateUserCardDetails(CardDetailsData cardDetailsData){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<UserEntity> isUserExist = userRepo.findByUserId(cardDetailsData.getUserId());
            if(isUserExist.isEmpty()){
                return Responses.errorResponse(response, "Invalid User Id");
            }
            if(!stripeService.isConfigured()){
                return Responses.errorResponse(response, "Payments are not configured yet");
            }
            UserEntity user = isUserExist.get();
            Optional<CardDetailsEntity> existing = cardDetailsRepo.findByUserId(cardDetailsData.getUserId());
            String customerId = existing.filter(c -> c.getStripeCustomerId() != null)
                    .map(CardDetailsEntity::getStripeCustomerId).orElse(null);
            Customer customer = stripeService.getOrCreateCustomer(customerId, user.getEmailAddress(),
                    (user.getFirstname() + " " + user.getLastname()).trim());
            StripeService.CardDisplay display = stripeService.attachPaymentMethod(customer.getId(),
                    cardDetailsData.getPaymentMethodId());

            CardDetailsEntity entity = existing.orElseGet(CardDetailsEntity::new);
            entity.setUserId(cardDetailsData.getUserId());
            entity.setCardName(cardDetailsData.getCardName().trim());
            entity.setStripeCustomerId(customer.getId());
            entity.setStripePaymentMethodId(cardDetailsData.getPaymentMethodId());
            entity.setLast4(display.last4);
            entity.setBrand(display.brand);
            entity.setExpMonth(display.expMonth);
            entity.setExpYear(display.expYear);
            cardDetailsRepo.save(entity);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(existing.isPresent() ? "Card details updated successfully" : "Card details added successfully");
            response.setData(EMPTY_DATA);
        } catch(Exception ex){
            LOG.warning("Card update failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not save your card — please try again");
        }
        return response;
    }
}
