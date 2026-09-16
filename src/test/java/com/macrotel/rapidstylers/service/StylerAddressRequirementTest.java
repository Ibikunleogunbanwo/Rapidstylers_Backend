package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.entity.IdentificationEntity;
import com.macrotel.rapidstylers.entity.OTPEntity;
import com.macrotel.rapidstylers.entity.ServiceEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.dto.StylerAccountDTO;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.BookAppointmentData;
import com.macrotel.rapidstylers.pojo.StylerData;
import com.macrotel.rapidstylers.pojo.VerificationActionData;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.IdentificationRepo;
import com.macrotel.rapidstylers.repo.OTPRepo;
import com.macrotel.rapidstylers.repo.ServiceRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.repo.AvailabilityExceptionRepo;
import com.macrotel.rapidstylers.repo.AvailabilityRepo;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A business address is not decorative: visiting the professional is the default
 * delivery, so a profile with no address gives the customer nowhere to go. The
 * rule has to hold at every door — signup, admin approval, the public list and
 * booking itself — because any single gap publishes a professional nobody can
 * actually book.
 */
class StylerAddressRequirementTest {

    private AppService appService;
    private StylerRepo stylerRepo;
    private UserRepo userRepo;
    private OTPRepo otpRepo;
    private IdentificationRepo identificationRepo;
    private ServiceRepo serviceRepo;
    private BookAppointmentRepo bookAppointmentRepo;
    private SubServiceRepo subServiceRepo;
    private RateLimiterService rateLimiterService;
    private ReadCacheService readCacheService;
    private GeocodingService geocodingService;
    private StripeService stripeService;
    private DTOService dtoService;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        stylerRepo = mock(StylerRepo.class);
        userRepo = mock(UserRepo.class);
        otpRepo = mock(OTPRepo.class);
        identificationRepo = mock(IdentificationRepo.class);
        serviceRepo = mock(ServiceRepo.class);
        bookAppointmentRepo = mock(BookAppointmentRepo.class);
        subServiceRepo = mock(SubServiceRepo.class);
        rateLimiterService = mock(RateLimiterService.class);
        readCacheService = mock(ReadCacheService.class);
        geocodingService = mock(GeocodingService.class);
        stripeService = mock(StripeService.class);

        // A refused signup hands its already-uploaded images back, so the service
        // is wired here even though these tests only exercise the refusal.
        appService.imageReclaimService = mock(ImageReclaimService.class);

        appService.stylerRepo = stylerRepo;
        appService.userRepo = userRepo;
        appService.otpRepo = otpRepo;
        appService.identificationRepo = identificationRepo;
        appService.serviceRepo = serviceRepo;
        appService.bookAppointmentRepo = bookAppointmentRepo;
        appService.subServiceRepo = subServiceRepo;
        appService.rateLimiterService = rateLimiterService;
        appService.readCacheService = readCacheService;
        appService.geocodingService = geocodingService;
        appService.stripeService = stripeService;

        dtoService = new DTOService();
        dtoService.stripeService = stripeService;
        dtoService.serviceRepo = serviceRepo;
        appService.dtoService = dtoService;

        when(stripeService.isConfigured()).thenReturn(false);
        // Serve what the loader would build, so Redis never enters the picture.
        when(readCacheService.getOrLoad(anyString(), any(Duration.class), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        // The public list stamps each row with the professional's weekly hours,
        // which needs a mapper for the per-result copy and repos for the hours
        // loader the passthrough invokes.
        appService.objectMapper = new ObjectMapper();
        appService.availabilityRepo = mock(AvailabilityRepo.class);
        appService.availabilityExceptionRepo = mock(AvailabilityExceptionRepo.class);
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private static StylerEntity approvedStyler(String id, String streetAddress) {
        StylerEntity entity = new StylerEntity();
        entity.setStylerId(id);
        entity.setFirstname("Ada");
        entity.setLastname("Lovelace");
        entity.setBusinessName("Demo " + id);
        entity.setVerificationStatus("APPROVED");
        entity.setStreetAddress(streetAddress);
        entity.setProvince("Alberta");
        return entity;
    }

    private static StylerData signupData(String streetAddress, String businessAddress) {
        StylerData data = new StylerData();
        data.setFirstname("Ada");
        data.setLastname("Lovelace");
        data.setEmailAddress("ada@example.com");
        data.setPassword("secret");
        data.setPhoneNumber("4035550000");
        data.setBusinessName("Ada Studio");
        data.setIdentificationTypeId("1");
        data.setServiceTypeId("1");
        data.setStreetAddress(streetAddress);
        data.setBusinessAddress(businessAddress);
        data.setBusinessProvince("Alberta");
        data.setCity("Calgary");
        data.setPostalCode("T2P 1J9");
        data.setCountry("Canada");
        data.setAgreeToTerms(true);
        return data;
    }

    /** Everything createStyler reads before it reaches the address rule. */
    private void stubSignupPreconditions() {
        when(otpRepo.verifyOtpSuccessForPurpose("ada@example.com", "STYLER SIGN UP"))
                .thenReturn(Optional.of(new OTPEntity()));
        when(identificationRepo.findById(1L)).thenReturn(Optional.of(new IdentificationEntity()));
        when(serviceRepo.findById(1L)).thenReturn(Optional.of(new ServiceEntity()));
        when(stylerRepo.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        when(userRepo.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
    }

    private static String messageOf(BaseResponse response) {
        return response.getMessage() == null ? "" : response.getMessage().toLowerCase();
    }

    /* ── signup ──────────────────────────────────────────────────────────── */

    @Test
    void signupRefusesAProfessionalWithNoAddress() {
        stubSignupPreconditions();

        BaseResponse response = appService.createStyler(signupData(null, "   "));

        assertEquals("400", response.getStatusCode());
        assertTrue(messageOf(response).contains("address"),
                "the refusal has to name the missing field: " + response.getMessage());
        verify(stylerRepo, never()).save(any(StylerEntity.class));
    }

    @Test
    void signupAcceptsTheGoogleFormattedAddressAlone() {
        stubSignupPreconditions();

        // The signup form writes only businessAddress (the formatted Places
        // string); rejecting that would break the real onboarding flow.
        BaseResponse response = appService.createStyler(signupData(null, "700 2 St SW, Calgary, Alberta"));

        assertEquals("200", response.getStatusCode());
        verify(stylerRepo).save(any(StylerEntity.class));
    }

    @Test
    void signupAcceptsTheStructuredStreetAlone() {
        stubSignupPreconditions();

        BaseResponse response = appService.createStyler(signupData("700 2 St SW", null));

        assertEquals("200", response.getStatusCode());
        verify(stylerRepo).save(any(StylerEntity.class));
    }

    /* ── approval ────────────────────────────────────────────────────────── */

    @Test
    void approvingAProfessionalWithNoAddressIsRefused() {
        StylerEntity pending = approvedStyler("ADDR1", null);
        pending.setVerificationStatus("PENDING");
        when(stylerRepo.findByStylerId("ADDR1")).thenReturn(Optional.of(pending));

        BaseResponse response = appService.updateStylerVerification(verification("ADDR1", "APPROVE"));

        assertEquals("400", response.getStatusCode());
        assertTrue(messageOf(response).contains("address"),
                "the admin has to be told what is missing: " + response.getMessage());
        assertEquals("PENDING", pending.getVerificationStatus(), "a refused approval must not change status");
        verify(stylerRepo, never()).save(any(StylerEntity.class));
    }

    @Test
    void approvingAProfessionalWithAnAddressPublishesThem() {
        StylerEntity pending = approvedStyler("ADDR2", null);
        pending.setVerificationStatus("PENDING");
        pending.setBusinessAddress("700 2 St SW, Calgary, Alberta");
        when(stylerRepo.findByStylerId("ADDR2")).thenReturn(Optional.of(pending));

        BaseResponse response = appService.updateStylerVerification(verification("ADDR2", "APPROVE"));

        assertEquals("200", response.getStatusCode());
        assertEquals("APPROVED", pending.getVerificationStatus());
        verify(stylerRepo).save(pending);
    }

    private static VerificationActionData verification(String stylerId, String action) {
        VerificationActionData data = new VerificationActionData();
        data.setStylerId(stylerId);
        data.setAction(action);
        return data;
    }

    /* ── public listing ──────────────────────────────────────────────────── */

    @Test
    void thePublicListOmitsAnApprovedProfessionalWithNoAddress() {
        StylerEntity unreachable = approvedStyler("ADDR3", null);
        StylerEntity reachable = approvedStyler("ADDR4", null);
        reachable.setBusinessAddress("700 2 St SW, Calgary, Alberta");
        when(stylerRepo.findAll()).thenReturn(Arrays.asList(unreachable, reachable));

        BaseResponse response = appService.listAllStylers();

        List<?> rows = (List<?>) response.getData();
        assertEquals(1, rows.size(), "only the reachable professional may be advertised");
        assertEquals("ADDR4", ((StylerAccountDTO) rows.get(0)).getStylerId(),
                "the address-less professional must not appear");
    }

    /* ── booking ─────────────────────────────────────────────────────────── */

    @Test
    void bookingIsRefusedAgainstAnApprovedProfessionalWithNoAddress() {
        when(userRepo.findByUserId("U1")).thenReturn(Optional.of(new UserEntity()));
        when(stylerRepo.findByStylerIdForUpdate("ADDR5"))
                .thenReturn(Optional.of(approvedStyler("ADDR5", null)));

        BaseResponse response = appService.bookAppointment(booking("ADDR5"));

        assertEquals("400", response.getStatusCode());
        assertTrue(messageOf(response).contains("address"),
                "the customer needs to know why the visit cannot happen: " + response.getMessage());
        verify(bookAppointmentRepo, never()).save(any(BookAppointmentEntity.class));
    }

    @Test
    void anUnapprovedProfessionalIsStillRefusedForTheOriginalReason() {
        StylerEntity pending = approvedStyler("ADDR6", "700 2 St SW");
        pending.setVerificationStatus("PENDING");
        when(userRepo.findByUserId("U1")).thenReturn(Optional.of(new UserEntity()));
        when(stylerRepo.findByStylerIdForUpdate("ADDR6")).thenReturn(Optional.of(pending));

        BaseResponse response = appService.bookAppointment(booking("ADDR6"));

        assertEquals("400", response.getStatusCode());
        assertFalse(messageOf(response).contains("address"),
                "an unapproved profile should not be blamed on a missing address: " + response.getMessage());
        verify(bookAppointmentRepo, never()).save(any(BookAppointmentEntity.class));
    }

    private static BookAppointmentData booking(String stylerId) {
        BookAppointmentData data = new BookAppointmentData();
        data.setUserId("U1");
        data.setStylerId(stylerId);
        data.setAppointmentDate(LocalDate.now().plusDays(3).toString());
        data.setArrivalTime("10:00");
        data.setSubServiceId("1");
        return data;
    }

    /* ── dashboard honesty ───────────────────────────────────────────────── */

    @Test
    @SuppressWarnings("unchecked")
    void theBusinessSummaryTellsTheProfessionalTheirAddressIsMissing() {
        when(stylerRepo.findByStylerId("ADDR7")).thenReturn(Optional.of(approvedStyler("ADDR7", null)));
        when(bookAppointmentRepo.findByStylerId("ADDR7")).thenReturn(Collections.emptyList());

        BaseResponse response = appService.getStylerBusinessSummary("ADDR7");

        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertEquals(Boolean.FALSE, data.get("addressOnFile"));
        assertEquals(Boolean.FALSE, data.get("bookable"),
                "the dashboard flag has to agree with the gate that hides the profile");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theBusinessSummaryReportsAReachableProfileAsBookable() {
        when(stylerRepo.findByStylerId("ADDR8")).thenReturn(Optional.of(approvedStyler("ADDR8", "700 2 St SW")));
        when(bookAppointmentRepo.findByStylerId("ADDR8")).thenReturn(Collections.emptyList());

        BaseResponse response = appService.getStylerBusinessSummary("ADDR8");

        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertEquals(Boolean.TRUE, data.get("addressOnFile"));
        assertEquals(Boolean.TRUE, data.get("bookable"));
    }
}
