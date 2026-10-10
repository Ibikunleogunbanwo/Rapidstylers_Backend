package com.macrotel.rapidstylers.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.auth.repo.OTPRepo;
import com.macrotel.rapidstylers.auth.service.AccountIds;
import com.macrotel.rapidstylers.auth.service.AuthService;
import com.macrotel.rapidstylers.booking.service.BookingService;
import com.macrotel.rapidstylers.catalog.repo.BlogPostRepo;
import com.macrotel.rapidstylers.catalog.repo.IdentificationRepo;
import com.macrotel.rapidstylers.catalog.repo.ServiceRepo;
import com.macrotel.rapidstylers.catalog.service.CatalogService;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.engagement.repo.FeedBackRepo;
import com.macrotel.rapidstylers.engagement.repo.LoyaltyAccountRepo;
import com.macrotel.rapidstylers.engagement.repo.NotificationRepo;
import com.macrotel.rapidstylers.engagement.repo.ReferralRepo;
import com.macrotel.rapidstylers.engagement.repo.ReviewRepo;
import com.macrotel.rapidstylers.engagement.repo.SupportTicketRepo;
import com.macrotel.rapidstylers.engagement.service.FeedbackService;
import com.macrotel.rapidstylers.engagement.service.LoyaltyService;
import com.macrotel.rapidstylers.engagement.service.NotificationService;
import com.macrotel.rapidstylers.engagement.service.ReviewService;
import com.macrotel.rapidstylers.engagement.service.SupportService;
import com.macrotel.rapidstylers.outbox.OutboxEventRepo;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.payment.repo.CardDetailsRepo;
import com.macrotel.rapidstylers.payment.service.CardService;
import com.macrotel.rapidstylers.payment.service.ConnectAccountService;
import com.macrotel.rapidstylers.payment.service.PaymentService;
import com.macrotel.rapidstylers.payment.service.StripeService;
import com.macrotel.rapidstylers.repo.AuditLogRepo;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.booking.repo.BookingSlotLockRepo;
import com.macrotel.rapidstylers.repo.SavedStylistRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import com.macrotel.rapidstylers.service.AppService;
import com.macrotel.rapidstylers.service.AuditService;
import com.macrotel.rapidstylers.service.DTOService;
import com.macrotel.rapidstylers.booking.service.IdempotencyService;
import com.macrotel.rapidstylers.service.ImageReclaimService;
import com.macrotel.rapidstylers.service.ReadCacheService;
import com.macrotel.rapidstylers.styler.repo.AvailabilityExceptionRepo;
import com.macrotel.rapidstylers.styler.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.styler.repo.StylerPortfolioRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.repo.SubServiceRepo;
import com.macrotel.rapidstylers.styler.service.AvailabilityService;
import com.macrotel.rapidstylers.styler.service.GeocodingService;
import com.macrotel.rapidstylers.styler.service.GoogleTimezoneService;
import com.macrotel.rapidstylers.styler.service.LocationCacheService;
import com.macrotel.rapidstylers.styler.service.LocationService;
import com.macrotel.rapidstylers.styler.service.PortfolioService;
import com.macrotel.rapidstylers.styler.service.SearchService;
import com.macrotel.rapidstylers.styler.service.StylerEligibility;
import com.macrotel.rapidstylers.styler.service.StylerService;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the domain services for a plain unit test, wired together the way
 * Spring wires them in the running app, but without starting Spring.
 *
 * <p>Picture a small switchboard: every collaborator a service needs (a repo,
 * a mock, a setting) is a public field here with the same name the services
 * use. Set the ones your test cares about, then ask for a service, for
 * example {@code services.bookingService().bookAppointment(data)}. Each call
 * hands the current switchboard values to every service, and services that
 * need each other get each other.
 */
public class TestServices {

    public AccountIds accountIds;
    public String appTimeZone;
    public AppUtils appUtils = new AppUtils();
    public AuditLogRepo auditLogRepo;
    public AuditService auditService = new AuditService();
    public AuthService authService;
    public AvailabilityExceptionRepo availabilityExceptionRepo;
    public AvailabilityRepo availabilityRepo;
    public BlogPostRepo blogPostRepo;
    public BookAppointmentRepo bookAppointmentRepo;
    public BookingSlotLockRepo bookingSlotLockRepo;
    public CardDetailsRepo cardDetailsRepo;
    public Long completionGraceMinutes;
    public DTOService dtoService;
    public EmailConfig emailConfig;
    public FeedBackRepo feedBackRepo;
    public GeocodingService geocodingService;
    public GoogleTimezoneService googleTimezoneService = new GoogleTimezoneService();
    public IdempotencyService idempotencyService;
    public IdentificationRepo identificationRepo;
    public ImageReclaimService imageReclaimService;
    public LocationCacheService locationCacheService;
    public LocationService locationService;
    public LoyaltyAccountRepo loyaltyAccountRepo;
    public NotificationRepo notificationRepo;
    public ObjectMapper objectMapper;
    public OTPRepo otpRepo;
    public OutboxEventRepo outboxEventRepo;
    public OutboxEventService outboxEventService;
    public Long paymentAuthorizationLeadHours;
    public Long paymentAuthorizationWindowDays;
    public PaymentService paymentService = new PaymentService();
    public ReadCacheService readCacheService;
    public ReferralRepo referralRepo;
    public ReviewRepo reviewRepo;
    public SavedStylistRepo savedStylistRepo;
    public ServiceRepo serviceRepo;
    public StripeService stripeService;
    public Long stylerCancelWindowHours;
    public StylerPortfolioRepo stylerPortfolioRepo;
    public StylerRepo stylerRepo;
    public SubServiceRepo subServiceRepo;
    public SupportTicketRepo supportTicketRepo;
    public UserRepo userRepo;
    public Integer warmMaxStylers;
    public Boolean warmReadCachesEnabled;

    private static final List<Class<?>> SERVICES = List.of(
            BookingService.class,
            CatalogService.class,
            FeedbackService.class,
            LoyaltyService.class,
            NotificationService.class,
            ReviewService.class,
            SupportService.class,
            CardService.class,
            ConnectAccountService.class,
            AppService.class,
            AvailabilityService.class,
            PortfolioService.class,
            SearchService.class,
            StylerEligibility.class,
            StylerService.class);

    private final Map<Class<?>, Object> built = new LinkedHashMap<>();

    public BookingService bookingService() {
        return get(BookingService.class);
    }

    public CatalogService catalogService() {
        return get(CatalogService.class);
    }

    public FeedbackService feedbackService() {
        return get(FeedbackService.class);
    }

    public LoyaltyService loyaltyService() {
        return get(LoyaltyService.class);
    }

    public NotificationService notificationService() {
        return get(NotificationService.class);
    }

    public ReviewService reviewService() {
        return get(ReviewService.class);
    }

    public SupportService supportService() {
        return get(SupportService.class);
    }

    public CardService cardService() {
        return get(CardService.class);
    }

    public ConnectAccountService connectAccountService() {
        return get(ConnectAccountService.class);
    }

    public AppService appService() {
        return get(AppService.class);
    }

    public AvailabilityService availabilityService() {
        return get(AvailabilityService.class);
    }

    public PortfolioService portfolioService() {
        return get(PortfolioService.class);
    }

    public SearchService searchService() {
        return get(SearchService.class);
    }

    public StylerEligibility stylerEligibility() {
        return get(StylerEligibility.class);
    }

    public StylerService stylerService() {
        return get(StylerService.class);
    }

    private synchronized <T> T get(Class<T> type) {
        if (built.isEmpty()) {
            for (Class<?> c : SERVICES) {
                try {
                    built.put(c, c.getDeclaredConstructor().newInstance());
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
        built.values().forEach(this::wire);
        return type.cast(built.get(type));
    }

    private void wire(Object service) {
        for (Field target : service.getClass().getDeclaredFields()) {
            int mod = target.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) continue;
            Object value = valueFor(target);
            if (value == null) continue;
            try {
                target.setAccessible(true);
                target.set(service, value);
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private Object valueFor(Field target) {
        try {
            Object mine = TestServices.class.getField(target.getName()).get(this);
            if (mine != null) return mine;
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // not a switchboard field: maybe another service
        }
        return built.get(target.getType());
    }
}
