package com.macrotel.rapidstylers.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.auth.repo.OTPRepo;
import com.macrotel.rapidstylers.auth.service.AccountIds;
import com.macrotel.rapidstylers.auth.service.AuthService;
import com.macrotel.rapidstylers.catalog.service.CatalogService;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.outbox.OutboxEventRepo;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.repo.AuditLogRepo;
import com.macrotel.rapidstylers.repo.AvailabilityExceptionRepo;
import com.macrotel.rapidstylers.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.catalog.repo.BlogPostRepo;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.BookingSlotLockRepo;
import com.macrotel.rapidstylers.repo.CardDetailsRepo;
import com.macrotel.rapidstylers.repo.FeedBackRepo;
import com.macrotel.rapidstylers.catalog.repo.IdentificationRepo;
import com.macrotel.rapidstylers.repo.LoyaltyAccountRepo;
import com.macrotel.rapidstylers.repo.NotificationRepo;
import com.macrotel.rapidstylers.repo.ReferralRepo;
import com.macrotel.rapidstylers.repo.ReviewRepo;
import com.macrotel.rapidstylers.repo.SavedStylistRepo;
import com.macrotel.rapidstylers.catalog.repo.ServiceRepo;
import com.macrotel.rapidstylers.repo.StylerPortfolioRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import com.macrotel.rapidstylers.repo.SupportTicketRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import com.macrotel.rapidstylers.service.AppService;
import com.macrotel.rapidstylers.service.AuditService;
import com.macrotel.rapidstylers.service.DTOService;
import com.macrotel.rapidstylers.service.GeocodingService;
import com.macrotel.rapidstylers.service.GoogleTimezoneService;
import com.macrotel.rapidstylers.service.IdempotencyService;
import com.macrotel.rapidstylers.service.ImageReclaimService;
import com.macrotel.rapidstylers.service.LocationCacheService;
import com.macrotel.rapidstylers.service.LocationService;
import com.macrotel.rapidstylers.service.PaymentOpsService;
import com.macrotel.rapidstylers.service.ReadCacheService;
import com.macrotel.rapidstylers.service.StripeService;

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
    public PaymentOpsService paymentOpsService = new PaymentOpsService();
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
            AppService.class,
            CatalogService.class);

    private final Map<Class<?>, Object> built = new LinkedHashMap<>();

    public AppService appService() {
        return get(AppService.class);
    }

    public CatalogService catalogService() {
        return get(CatalogService.class);
    }

    private <T> T get(Class<T> type) {
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
