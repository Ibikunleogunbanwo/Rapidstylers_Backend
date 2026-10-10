package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.pojo.ServiceUpdateData;
import com.macrotel.rapidstylers.styler.pojo.StylerData;
import com.macrotel.rapidstylers.styler.pojo.SubServiceData;
import com.macrotel.rapidstylers.styler.pojo.VerificationActionData;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.repo.SubServiceRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.auth.entity.OTPEntity;
import com.macrotel.rapidstylers.auth.repo.OTPRepo;
import com.macrotel.rapidstylers.auth.service.AccountIds;
import com.macrotel.rapidstylers.auth.service.AuthService;
import com.macrotel.rapidstylers.catalog.entity.IdentificationEntity;
import com.macrotel.rapidstylers.catalog.entity.ServiceEntity;
import com.macrotel.rapidstylers.catalog.repo.IdentificationRepo;
import com.macrotel.rapidstylers.catalog.repo.ServiceRepo;
import com.macrotel.rapidstylers.catalog.service.CatalogService;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.styler.dto.StylerAccountDTO;
import com.macrotel.rapidstylers.engagement.dto.StylerReviewDTO;
import com.macrotel.rapidstylers.engagement.service.NotificationService;
import com.macrotel.rapidstylers.engagement.service.ReviewService;
import com.macrotel.rapidstylers.entity.*;
import com.macrotel.rapidstylers.pojo.*;
import com.macrotel.rapidstylers.repo.*;
import com.macrotel.rapidstylers.service.DTOService;
import com.macrotel.rapidstylers.media.service.ImageReclaimService;
import com.macrotel.rapidstylers.service.ReadCacheService;
import com.stripe.model.Account;
import java.time.Duration;
import java.util.*;
import java.util.Locale;
import java.util.logging.Logger;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Stylist accounts and profiles: sign-up, verification, sub-services, the public profile page and its read cache.
 */
@Service
public class StylerService {
    AppUtils appUtils = new AppUtils();
    @Autowired
    DTOService dtoService;
    @Autowired
    ImageReclaimService imageReclaimService;
    private static final Logger LOG = Logger.getLogger(StylerService.class.getName());
    @Autowired
    OTPRepo otpRepo;
    @Autowired
    UserRepo userRepo;
    @Autowired
    IdentificationRepo identificationRepo;
    @Autowired
    ServiceRepo serviceRepo;
    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    SubServiceRepo subServiceRepo;
    @Autowired
    BookAppointmentRepo bookAppointmentRepo;
    @Autowired
    GeocodingService geocodingService;
    // Initialized inline so bare construction (unit tests) behaves as a
    // no-op (null key → null zone); Spring replaces it with the context bean.
    @Autowired
    GoogleTimezoneService googleTimezoneService = new GoogleTimezoneService();
    @Autowired
    LocationCacheService locationCacheService;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ReadCacheService readCacheService;
    @Autowired
    AccountIds accountIds;
    @Autowired
    AuthService authService;
    // Read-cache warm-up bounds (see warmReadCaches). warmInFlight prevents a
    // concurrent re-run from doubling the load when warming overlaps startup.
    @Value("${app.cache.warm-enabled:true}")
    private boolean warmReadCachesEnabled = true;
    @Value("${app.cache.warm-max-stylers:25}")
    private int warmMaxStylers = 25;
    private final java.util.concurrent.atomic.AtomicBoolean warmInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
    // Booking starts and slot holds use 15-minute granularity; each service's
    // configured duration determines how much of the stylist's calendar is blocked.
    private static final int SLOT_GRANULARITY_MINUTES = 15;
    @Autowired
    CatalogService catalogService;
    @Autowired
    NotificationService notificationService;
    @Autowired
    ReviewService reviewService;
    @Autowired
    AvailabilityService availabilityService;
    @Autowired
    PortfolioService portfolioService;
    @Autowired
    StylerEligibility stylerEligibility;

    /**
     * Creates a professional account, and gives the images back if it cannot.
     *
     * Both files were already uploaded to Cloudinary by the browser, because image
     * bytes never pass through this server. That makes this call the first moment
     * anything here learns their names, and therefore the only place that can
     * discard them when the account is not created: a duplicate email, a rejected
     * password or a missing field would otherwise leave two images in the bucket
     * that no record will ever point at. No authorization question arises, because
     * reclaiming only ever destroys an asset that nothing references.
     */
    public BaseResponse createStyler(StylerData stylerData){
        BaseResponse response = createStylerAccount(stylerData);
        if(!SUCCESS_STATUS_CODE.equals(response.getStatusCode())){
            imageReclaimService.reclaimIfUnreferenced(stylerData.getProfileImageUrl());
            imageReclaimService.reclaimIfUnreferenced(stylerData.getIdentificationImageUrl());
        }
        return response;
    }

    private BaseResponse createStylerAccount(StylerData stylerData){
        BaseResponse response = new BaseResponse(true);
        try{
            if (!stylerData.isAgreeToTerms()) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("You must agree to the Terms and Conditions");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Check email across BOTH tables
            Optional<StylerEntity> isStylerEmailExist = stylerRepo.findByEmailAddress(stylerData.getEmailAddress());
            Optional<UserEntity> isUserEmailExist = userRepo.findByEmailAddress(stylerData.getEmailAddress());
            if(isStylerEmailExist.isPresent() || isUserEmailExist.isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Email Address already exists, Kindly choose another email");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Locked-out email (failed logins/verifies) cannot create an account.
            if (authService.isLockedOut(stylerData.getEmailAddress())) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many failed attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Enforce email verification: a STYLER SIGN UP OTP must have been verified.
            Optional<OTPEntity> verifiedOtp = otpRepo.verifyOtpSuccessForPurpose(stylerData.getEmailAddress(), "STYLER SIGN UP");
            if(verifiedOtp.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Email Address is yet to be verified");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Check phone number across both tables
            Optional<StylerEntity> isStylerPhoneExist = stylerRepo.findByPhoneNumber(stylerData.getPhoneNumber());
            Optional<UserEntity> isUserPhoneExist = userRepo.findByPhoneNumber(stylerData.getPhoneNumber());
            if(isStylerPhoneExist.isPresent() || isUserPhoneExist.isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Phone number already registered");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Check if identificationId and ServiceId exist
            Optional<IdentificationEntity> isIdentificationExist = identificationRepo.findById(Long.parseLong(stylerData.getIdentificationTypeId()));
            if(isIdentificationExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Identification Type, Contact Admin");
                response.setData(EMPTY_DATA);
                return response;
            }
            Optional<ServiceEntity> isServiceTypeExist = serviceRepo.findById(Long.parseLong(stylerData.getServiceTypeId()));
            if(isServiceTypeExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Service Type, Contact Admin");
                response.setData(EMPTY_DATA);
                return response;
            }
            // A street address is what makes the profile bookable at all: the
            // default delivery is a visit, and the bookability gate keeps an
            // address-less professional out of search and off their own
            // profile, while approvals refuse them outright. Reject at signup
            // so nobody completes onboarding into an invisible account.
            if(!StylerEligibility.isPresentText(stylerData.getStreetAddress()) && !StylerEligibility.isPresentText(stylerData.getBusinessAddress())){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Business address is required. Clients travel to it when they book a visit, so it has to be the place they come to.");
                response.setData(EMPTY_DATA);
                return response;
            }
            StylerEntity stylerEntity = new StylerEntity(stylerData);
            stylerEntity.setStylerId(accountIds.freeAccountId(stylerEntity.getStylerId(),
                    () -> stylerData.getFirstname().toUpperCase().charAt(0) + ""
                            + stylerData.getLastname().toUpperCase().charAt(0) + appUtils.randomDigit(4)));

            // Geocode address → lat/lng if not already provided
            if(stylerEntity.getLatitude() == null || stylerEntity.getLongitude() == null){
                String addressToGeocode = buildAddress(stylerData);
                java.util.Map<String, Object> geo = geocodingService.geocode(addressToGeocode);
                if(geo != null){
                    stylerEntity.setLatitude((Double) geo.get("latitude"));
                    stylerEntity.setLongitude((Double) geo.get("longitude"));
                    if(stylerEntity.getCity() == null || stylerEntity.getCity().isEmpty()){
                        stylerEntity.setCity((String) geo.getOrDefault("city", ""));
                    }
                }
            }

            // Derive the vendor's time zone from their address so their weekly
            // hours are always read on their own clock. Geocode-derived wins;
            // fall back to the province map, then leave NULL (= app default).
            if(stylerEntity.getLatitude() != null && stylerEntity.getLongitude() != null){
                String derived = googleTimezoneService.timeZoneId(stylerEntity.getLatitude(), stylerEntity.getLongitude());
                stylerEntity.setTimeZone(derived != null ? derived : VendorZoneResolver.zoneForProvince(stylerEntity.getProvince()).getId());
                // Record which answer this was: an exact lookup, or a province
                // guess worth retrying. The startup backfill reads this marker.
                stylerEntity.setTimeZoneSource(derived != null
                        ? VendorZoneResolver.SOURCE_GOOGLE : VendorZoneResolver.SOURCE_PROVINCE);
            } else if (stylerEntity.getTimeZone() == null || stylerEntity.getTimeZone().isBlank()) {
                stylerEntity.setTimeZone(VendorZoneResolver.zoneForProvince(stylerEntity.getProvince()).getId());
                stylerEntity.setTimeZoneSource(VendorZoneResolver.SOURCE_PROVINCE);
            }

            stylerRepo.save(stylerEntity);

            // Index in Redis geospatial cache for fast radius search
            if(stylerEntity.getLatitude() != null && stylerEntity.getLongitude() != null){
                locationCacheService.indexStyler(stylerEntity.getStylerId(), stylerEntity.getLongitude(), stylerEntity.getLatitude());
            }

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Stylers Account Created Successful");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    private String stylerServiceName(String serviceTypeId){
        if(serviceTypeId == null || serviceTypeId.isEmpty()) return "";
        try{
            Optional<ServiceEntity> svc = serviceRepo.findById(Long.valueOf(serviceTypeId));
            return svc.map(ServiceEntity::getServiceName).orElse("");
        }
        catch (NumberFormatException e){
            return "";
        }
    }

    public BaseResponse getStylerVerificationQueue(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<StylerEntity> allStylers = stylerRepo.findAll();
            List<Object> result = new ArrayList<>();
            for(StylerEntity styler : allStylers){
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("stylerId", styler.getStylerId());
                entry.put("firstname", styler.getFirstname());
                entry.put("lastname", styler.getLastname());
                entry.put("emailAddress", styler.getEmailAddress());
                entry.put("phoneNumber", styler.getPhoneNumber());
                entry.put("businessName", styler.getBusinessName());
                entry.put("serviceTypeId", styler.getServiceTypeId());
                entry.put("serviceTypeName", stylerServiceName(styler.getServiceTypeId()));
                entry.put("city", styler.getCity());
                entry.put("province", styler.getProvince());
                // Approval is refused without a street address, so the reviewer
                // has to be able to see whether there is one.
                entry.put("businessAddress", styler.getBusinessAddress());
                entry.put("addressOnFile", StylerEligibility.hasStreetAddress(styler));
                entry.put("identificationId", styler.getIdentificationId());
                entry.put("identificationImageUrl", styler.getIdentificationImageUrl());
                entry.put("profileImageUrl", styler.getProfileImageUrl());
                entry.put("verificationStatus", styler.getVerificationStatus());
                entry.put("dateRegistered", styler.getInsertedDt());
                result.add(entry);
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse updateStylerVerification(VerificationActionData verificationActionData){
        BaseResponse response = new BaseResponse(true);
        try{
            String stylerId = verificationActionData.getStylerId();
            String action = verificationActionData.getAction() == null ? "" : verificationActionData.getAction().trim().toUpperCase();
            // Accept both the verb (APPROVE) and the state (APPROVED) forms.
            if(VERIFICATION_APPROVED.equals(action) || "APPROVE".equals(action)){
                action = VERIFICATION_APPROVED;
            } else if(VERIFICATION_REJECTED.equals(action) || "REJECT".equals(action)){
                action = VERIFICATION_REJECTED;
            } else if(VERIFICATION_SUSPENDED.equals(action) || "SUSPEND".equals(action)){
                action = VERIFICATION_SUSPENDED;
            } else {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid verification action. Use APPROVE, REJECT or SUSPEND");
                response.setData(EMPTY_DATA);
                return response;
            }
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(stylerId);
            if(stylerOpt.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            String pastTense = action.equals(VERIFICATION_APPROVED) ? "approved" : action.equals(VERIFICATION_REJECTED) ? "rejected" : "suspended";
            StylerEntity styler = stylerOpt.get();
            // Approving a professional with no street address would publish
            // nothing: the bookability gate keeps them out of search and off
            // their own profile. Better to refuse and say what is missing than
            // to complete an approval that silently does not list them.
            if(VERIFICATION_APPROVED.equals(action) && !StylerEligibility.hasStreetAddress(styler)){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("This professional has no business address on file, so approving them would leave them unlisted and unbookable. Ask them to add their address first.");
                response.setData(EMPTY_DATA);
                return response;
            }
            String previousStatus = styler.getVerificationStatus();
            styler.setVerificationStatus(action);
            stylerRepo.save(styler);
            evict(ReadCacheService.KEY_STYLER_DTO + stylerId);
            if(!Objects.equals(previousStatus, action)){
                notificationService.notifySavedCustomers(stylerId, "VERIFICATION", "Professional verification updated",
                        "The verification status for " + (styler.getBusinessName() == null ? "your saved professional" : styler.getBusinessName())
                                + " is now " + action.toLowerCase(Locale.ROOT) + ".");
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Styler " + pastTense + " successfully");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse createSubService(SubServiceData subServiceData){
        BaseResponse response = new BaseResponse(true);
        try{
           //Check if styler account
           Optional<StylerEntity> isStylerExist= stylerRepo.findByStylerId(subServiceData.getStylerId()) ;
           if(isStylerExist.isEmpty()){
               response.setStatusCode(ERROR_STATUS_CODE);
               response.setMessage("Invalid STyler Id");
               response.setData(EMPTY_DATA);
               return response;
           }
           //CHeck if sub service around exist with styler
            Optional<SubServiceEntity> isSubServiceExist = subServiceRepo.isServiceExist(subServiceData.getStylerId(), subServiceData.getName());
           if(isSubServiceExist.isPresent()){
               response.setStatusCode(ERROR_STATUS_CODE);
               response.setMessage("Sub Service name already exit for styler");
               response.setData(EMPTY_DATA);
               return response;
           }
           int durationMinutes = subServiceData.getDurationMinutes();
           if(durationMinutes % SLOT_GRANULARITY_MINUTES != 0){
               return Responses.errorResponse(response, "Duration must be in 15-minute increments");
           }
           SubServiceEntity subServiceEntity = new SubServiceEntity(subServiceData);
           subServiceRepo.save(subServiceEntity);
           evict(ReadCacheService.KEY_STYLER_SUBSERVICES + subServiceData.getStylerId());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Sub Service created successful");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Updates a service owned by the authenticated stylist and notifies saved customers when its price changes. */
    public BaseResponse updateSubService(String stylerId, ServiceUpdateData data){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<SubServiceEntity> existing = subServiceRepo.isServiceExistById(stylerId, data.getId());
            if(existing.isEmpty()) return Responses.errorResponse(response, "Service not found");
            int duration = data.getDurationMinutes() == null ? DEFAULT_SERVICE_DURATION_MINUTES : data.getDurationMinutes();
            if(duration < MIN_SERVICE_DURATION_MINUTES || duration > MAX_SERVICE_DURATION_MINUTES || duration % SLOT_GRANULARITY_MINUTES != 0){
                return Responses.errorResponse(response, "Duration must be in 15-minute increments between 15 and 480 minutes");
            }
            SubServiceEntity service = existing.get();
            String previousPrice = service.getPrice();
            service.setName(AppUtils.sanitizeText(data.getName().trim()));
            service.setPrice(appUtils.currencyFormat(data.getPrice()));
            service.setDurationMinutes(duration);
            subServiceRepo.save(service);
            evict(ReadCacheService.KEY_STYLER_SUBSERVICES + stylerId);
            if(!Objects.equals(previousPrice, service.getPrice())){
                notificationService.notifySavedCustomers(stylerId, "PRICE", "Saved professional price changed",
                        "The price for " + service.getName() + " is now $" + service.getPrice() + ".");
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Service updated successfully");
            response.setData(dtoService.subServiceDTO(service));
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listOwnStylerSubService(String stylerId){
        return listStylerSubService(stylerId);
    }

    public BaseResponse listStylerSubService(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            //Check if styler account exist
            Optional<StylerEntity> isStylerExist= stylerRepo.findByStylerId(stylerId) ;
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(cachedSubServices(stylerId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    // ---- Read-cache helpers (single-flight + jittered TTL, see ReadCacheService) ----

    /** Styler profile DTO through the read cache; evicted on verification/travel/review writes. */
    public StylerAccountDTO cachedStylerAccountDTO(StylerEntity styler){
        return readCacheService.getOrLoad(
                ReadCacheService.KEY_STYLER_DTO + styler.getStylerId(),
                ReadCacheService.DTO_TTL, ReadCacheService.DTO_JITTER,
                () -> dtoService.stylerAccountDTO(styler));
    }

    /**
     * A list row carrying the professional's trading hours and the blocked dates
     * still ahead of them, read through the same per-styler caches the profile
     * uses. That is what lets a search result or a featured card say "Open now,
     * closes 5:00 PM" or "Closed, opens Saturday 9:00 AM" on the professional's
     * own clock instead of falling back to a bare presence badge.
     *
     * Two deliberate choices here:
     *
     *   - The DTO is **copied** rather than decorated in place. The instance
     *     coming out of the read cache is shared by every surface, and writing
     *     hours onto it would put one professional's schedule into a cache entry
     *     other callers reuse.
     *   - The open state itself is **not** computed here. Whether a professional
     *     is open depends on "now", and this response is cached for minutes; a
     *     frozen boolean could sit in a card claiming "Open now" long after
     *     closing. The client computes it from these two lists every render, and
     *     it is the same implementation the profile and the Open now filter use,
     *     so the surfaces cannot disagree.
     */
    public StylerAccountDTO listRowWithHours(StylerAccountDTO dto, String stylerId){
        if(dto == null || stylerId == null){
            return dto;
        }
        StylerAccountDTO row = objectMapper.convertValue(dto, StylerAccountDTO.class);
        attachHours(row, stylerId);
        return row;
    }

    /**
     * Stamps the trading hours onto a DTO the caller has already copied, for the
     * endpoints that make their own copy (the nearby search stamps a distance on
     * one). Keeping the write separate from the copy is what stops a shared
     * cached instance ever being modified.
     */
    public void attachHours(StylerAccountDTO row, String stylerId){
        if(row == null || stylerId == null){
            return;
        }
        row.setAvailability(availabilityService.cachedAvailabilitySlots(stylerId));
        row.setExceptions(availabilityService.upcomingExceptions(stylerId));
    }

    /** Sub-service list through the read cache; evicted on sub-service writes. */
    private List<Object> cachedSubServices(String stylerId){
        return readCacheService.getOrLoad(
                ReadCacheService.KEY_STYLER_SUBSERVICES + stylerId,
                ReadCacheService.STYLER_PART_TTL, ReadCacheService.STYLER_PART_JITTER,
                () -> {
                    List<SubServiceEntity> rows = subServiceRepo.findByStylerId(stylerId);
                    List<Object> built = new ArrayList<>();
                    for(SubServiceEntity entity : rows){
                        built.add(dtoService.subServiceDTO(entity));
                    }
                    Collections.reverse(built);
                    return built;
                });
    }

    /**
     * Appointment count, finished count + active booked slots; evicted on every
     * booking-lifecycle write. The finished count rides along on the rows already
     * loaded here, so the profile's badges cost no extra query.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> cachedAppointmentSummary(String stylerId){
        return readCacheService.getOrLoad(
                ReadCacheService.KEY_STYLER_APPOINTMENTS + stylerId,
                ReadCacheService.APPOINTMENTS_TTL, ReadCacheService.APPOINTMENTS_JITTER,
                () -> {
                    List<BookAppointmentEntity> rows = bookAppointmentRepo.findByStylerId(stylerId);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("appointmentCount", String.valueOf(rows == null ? 0 : rows.size()));
                    List<Object> bookedSlots = new ArrayList<>();
                    long completedCount = 0;
                    if(rows != null){
                        for(BookAppointmentEntity appointment : rows){
                            // Status 0 is a finished job; the profile's badges use the tally.
                            if("0".equals(appointment.getStatus())) completedCount++;
                            // Cancelled (4) and rejected (2) appointments free the slot.
                            if("2".equals(appointment.getStatus()) || "4".equals(appointment.getStatus())) continue;
                            HashMap<String, String> slot = new HashMap<>();
                            slot.put("appointmentDate", appointment.getAppointmentDate());
                            slot.put("arrivalTime", appointment.getArrivalTime());
                            slot.put("status", appointment.getStatus());
                            slot.put("durationMinutes", String.valueOf(appointment.getDurationMinutes() == null
                                    ? DEFAULT_SERVICE_DURATION_MINUTES : appointment.getDurationMinutes()));
                            bookedSlots.add(slot);
                        }
                    }
                    payload.put("completedCount", completedCount);
                    payload.put("bookedSlots", bookedSlots);
                    return payload;
                });
    }

    /**
     * Warm the read cache after a cold start (deploy / Redis flush) so the first
     * real users don't pay the full DB load. Bounded and intended to be called on
     * a background thread (see CacheWarmer) so it never delays startup or readiness.
     *
     * Warms the bounded catalog keys always (services, identifications, blogs),
     * then at most {@code max-warable-stylers} approved stylist profiles. If a
     * warmer invocation is in-flight, this is a no-op (never doubles the load).
     * All failures are swallowed — the cache is a speed-up, never a dependency.
     */
    public void warmReadCaches(){
        if(readCacheService == null || !warmReadCachesEnabled){
            return;
        }
        if(!warmInFlight.compareAndSet(false, true)){
            return; // a previous warm is still running — never double the load
        }
        try{
            // Bounded catalog keys — cheap and read on every page load.
            readCacheService.getOrLoad(ReadCacheService.KEY_CATALOG_SERVICES,
                    ReadCacheService.CATALOG_TTL, null, catalogService::loadCatalogServices);
            readCacheService.getOrLoad(ReadCacheService.KEY_CATALOG_IDENTIFICATIONS,
                    ReadCacheService.CATALOG_TTL, null, catalogService::loadCatalogIdentifications);
            readCacheService.getOrLoad(ReadCacheService.KEY_CATALOG_BLOGS,
                    ReadCacheService.BLOG_TTL, null, catalogService::loadCatalogBlogs);

            // Bounded set of approved stylists, most active first. We cannot know
            // prior cross-restart hit frequency (counters reset on boot), so we warm
            // the most recently updated approved profiles, capped by config.
            if(stylerRepo != null){
                List<StylerEntity> approved = new ArrayList<>();
                for(StylerEntity styler : stylerRepo.findAll()){
                    if(stylerEligibility.isApprovedStyler(styler)){
                        approved.add(styler);
                    }
                }
                approved.sort((a, b) -> {
                    String da = a.getInsertedDt() != null ? a.getInsertedDt() : "";
                    String db = b.getInsertedDt() != null ? b.getInsertedDt() : "";
                    return db.compareTo(da);
                });
                int limit = Math.max(0, warmMaxStylers);
                for(StylerEntity styler : approved){
                    if(limit-- <= 0){
                        break;
                    }
                    warmStylerCache(styler);
                }
            }
            LOG.info("Read cache warmed (catalog + up to " + warmMaxStylers + " approved profiles)");
        }
        catch(Exception ex){
            LOG.warning("Read cache warm-up failed (best-effort): " + ex.getMessage());
        }
        finally{
            warmInFlight.set(false);
        }
    }

    /** Warms every read-cache partition for one stylist (best-effort, never throws). */
    private void warmStylerCache(StylerEntity styler){
        try{
            cachedStylerAccountDTO(styler);
            cachedSubServices(styler.getStylerId());
            portfolioService.cachedPortfolio(styler.getStylerId());
            reviewService.cachedReviews(styler.getStylerId());
            availabilityService.cachedAvailabilitySlots(styler.getStylerId());
            availabilityService.cachedExceptionSlots(styler.getStylerId());
        } catch(Exception ignored){
            // Individual stylist partitions are not worth aborting the whole warm for.
        }
    }

    /** Null-safe eviction — unit tests that build this service without a cache never break. */
    private void evict(String... keys){
        if(readCacheService != null){
            readCacheService.evict(keys);
        }
    }

    public BaseResponse getStylerDetails(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try {
            //Check if styler account exist
            Optional<StylerEntity> isStylerExist= stylerRepo.findByStylerId(stylerId) ;
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Only approved professionals are publicly visible
            if(!stylerEligibility.isBookableStyler(isStylerExist.get())){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("This professional is not yet available");
                response.setData(EMPTY_DATA);
                return response;
            }
            int ratingPercentage = 0;
            // Get Styler Information — every part below is read through its own
            // cache (single-flight + jittered TTL), so a warm profile costs exactly
            // one DB query (the styler row above) plus N Redis reads.
            StylerEntity stylerEntity = isStylerExist.get();
            List<Object> subServiceResult = cachedSubServices(stylerId);
            List<Object> stylerPortfolioResult = portfolioService.cachedPortfolio(stylerId);
            List<Object> stylerReviewResult = reviewService.cachedReviews(stylerId);
            int totalRating = 0;
            double ratingCount = 0;
            for(Object reviewObj : stylerReviewResult){
                if(reviewObj instanceof StylerReviewDTO){
                    ratingCount += 1;
                    try {
                        totalRating += Integer.parseInt(((StylerReviewDTO) reviewObj).getRatingScore());
                    } catch(NumberFormatException ignored){ }
                }
            }
            ratingPercentage = ratingCount == 0 ? 0 : (int) ((totalRating/(ratingCount * 5)) *100);
            StylerAccountDTO stylerAccount = cachedStylerAccountDTO(stylerEntity);
            HashMap<String, Object> stylerInformationMap = new HashMap<>();
            stylerInformationMap.put("stylerInformation", stylerAccount);
            stylerInformationMap.put("stylerSubService" , subServiceResult);
            stylerInformationMap.put("stylerPortfolio" , stylerPortfolioResult);
            stylerInformationMap.put("stylerReviews" , stylerReviewResult);
            stylerInformationMap.put("ratingPercentage", String.valueOf(ratingPercentage));
            // Real appointment tally + active booked slots (cached, evicted on booking writes).
            Map<String, Object> appointmentSummary = cachedAppointmentSummary(stylerId);
            stylerInformationMap.put("appointmentCount", appointmentSummary.get("appointmentCount"));
            // What this profile has earned, decided in one place (ProfileBadgeRules).
            // The finished tally comes from the same cached summary; if a cached
            // entry predates it, the stage badges stay off rather than guessing.
            stylerInformationMap.put("badges", ProfileBadgeRules.earned(
                    stylerAccount == null ? null : stylerAccount.getReviewCount(),
                    stylerAccount == null ? null : stylerAccount.getAverageRating(),
                    parseCount(appointmentSummary.get("appointmentCount")),
                    appointmentSummary.get("completedCount") instanceof Number
                            ? ((Number) appointmentSummary.get("completedCount")).longValue() : null,
                    stylerAccount == null ? null : stylerAccount.getDateRegistered()));
            // Weekly availability + date-based exceptions so the booking modal can
            // show real working hours and gray out blocked/taken windows.
            stylerInformationMap.put("availability", availabilityService.cachedAvailabilitySlots(stylerId));
            stylerInformationMap.put("exceptions", availabilityService.cachedExceptionSlots(stylerId));
            stylerInformationMap.put("bookedSlots", appointmentSummary.get("bookedSlots"));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(stylerInformationMap);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** A count the payload may carry as text; anything unreadable is unknown, not zero. */
    private static Long parseCount(Object value){
        if(value == null){
            return null;
        }
        if(value instanceof Number){
            return ((Number) value).longValue();
        }
        try{
            return Long.parseLong(String.valueOf(value).trim());
        }
        catch(NumberFormatException ex){
            return null;
        }
    }

    @PostConstruct
    void rebuildStylerLocationIndex(){
        if (locationCacheService == null || stylerRepo == null) return;
        try {
            locationCacheService.clearIndex();
            int indexed = 0;
            for (StylerEntity styler : stylerRepo.findAll()) {
                if (stylerEligibility.isApprovedStyler(styler) && styler.getLatitude() != null && styler.getLongitude() != null) {
                    locationCacheService.indexStyler(styler.getStylerId(), styler.getLongitude(), styler.getLatitude());
                    indexed++;
                }
            }
            LOG.info("Rebuilt stylist location index: " + indexed + " stylists");
        } catch (Exception ex) {
            LOG.warning("Could not rebuild stylist location index: " + ex.getMessage());
        }
    }

    /**
     * Periodic reconciliation: rebuild the Redis geo index from MySQL every
     * 30 minutes so that stale entries (deleted stylists, coordinate changes
     * outside the normal write path) are corrected without a restart.
     */
    @Scheduled(fixedDelayString = "${app.geo.reconcile-interval-ms:1800000}")
    void reconcileStylerLocationIndex() {
        rebuildStylerLocationIndex();
    }

    /**
     * Build a full address string from structured StylerData fields for geocoding.
     */
    private String buildAddress(StylerData stylerData){
        StringBuilder sb = new StringBuilder();
        if(stylerData.getStreetAddress() != null && !stylerData.getStreetAddress().isEmpty()){
            sb.append(stylerData.getStreetAddress());
        }
        if(stylerData.getUnit() != null && !stylerData.getUnit().isEmpty()){
            sb.append(" ").append(stylerData.getUnit());
        }
        if(stylerData.getCity() != null && !stylerData.getCity().isEmpty()){
            sb.append(", ").append(stylerData.getCity());
        }
        if(stylerData.getBusinessProvince() != null && !stylerData.getBusinessProvince().isEmpty()){
            sb.append(", ").append(stylerData.getBusinessProvince());
        }
        if(stylerData.getPostalCode() != null && !stylerData.getPostalCode().isEmpty()){
            sb.append(" ").append(stylerData.getPostalCode());
        }
        if(stylerData.getCountry() != null && !stylerData.getCountry().isEmpty()){
            sb.append(", ").append(stylerData.getCountry());
        }
        return sb.toString();
    }
}
