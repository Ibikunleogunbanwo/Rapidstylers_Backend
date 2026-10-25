package com.macrotel.rapidstylers.booking.service;

import com.macrotel.rapidstylers.booking.entity.QuoteEntity;
import com.macrotel.rapidstylers.booking.pojo.QuoteOfferData;
import com.macrotel.rapidstylers.booking.pojo.QuoteRequestData;
import com.macrotel.rapidstylers.booking.repo.QuoteRepo;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.engagement.service.NotificationService;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.service.StylerEligibility;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuoteServiceTest {
    private static final Instant NOW = Instant.parse("2030-03-01T12:00:00Z");
    private QuoteService service;
    private QuoteRepo quoteRepo;
    private StylerRecords stylerRecords;
    private CustomerRecords customerRecords;
    private StylerEligibility eligibility;
    private NotificationService notifications;
    private final List<QuoteEntity> saved = new ArrayList<>();
    private com.macrotel.rapidstylers.booking.repo.QuoteMessageRepo messages;
    private final com.macrotel.rapidstylers.media.service.UploadOwnership ownership = new com.macrotel.rapidstylers.media.service.UploadOwnership();
    private final List<com.macrotel.rapidstylers.booking.entity.QuoteMessageEntity> thread = new ArrayList<>();

    @BeforeEach
    void setUp() {
        quoteRepo = mock(QuoteRepo.class);
        stylerRecords = mock(StylerRecords.class);
        customerRecords = mock(CustomerRecords.class);
        eligibility = mock(StylerEligibility.class);
        notifications = mock(NotificationService.class);
        service = new QuoteService();
        service.quoteRepo = quoteRepo;
        service.stylerRecords = stylerRecords;
        service.customerRecords = customerRecords;
        service.stylerEligibility = eligibility;
        service.notificationService = notifications;
        service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        messages = mock(com.macrotel.rapidstylers.booking.repo.QuoteMessageRepo.class);
        service.quoteMessageRepo = messages;
        service.uploadOwnership = ownership;
        com.macrotel.rapidstylers.media.service.CloudinaryService cloudinary = new com.macrotel.rapidstylers.media.service.CloudinaryService();
        service.cloudinaryService = cloudinary;

        UserEntity customer = new UserEntity();
        customer.setUserId("CUST1");
        customer.setFirstname("Ada");
        when(customerRecords.findByUserId("CUST1")).thenReturn(Optional.of(customer));
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("STY1");
        styler.setBusinessName("Nia Nails");
        when(stylerRecords.findByStylerId("STY1")).thenReturn(Optional.of(styler));
        when(eligibility.isApprovedStyler(styler)).thenReturn(true);
        SubServiceEntity nails = new SubServiceEntity();
        nails.setId(7L);
        nails.setName("Gel extensions");
        when(stylerRecords.findSubService("STY1", 7L)).thenReturn(Optional.of(nails));
        when(quoteRepo.findByQuoteRef(anyString())).thenReturn(Optional.empty());
        when(quoteRepo.findByUserIdAndStatusIn(anyString(), anyList())).thenReturn(List.of());
        when(messages.findByQuoteRefOrderByIdAsc(anyString())).thenAnswer(call -> List.copyOf(thread));
        when(messages.countByQuoteRef(anyString())).thenAnswer(call -> (long) thread.size());
        when(messages.save(any())).thenAnswer(call -> {
            thread.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(quoteRepo.save(any(QuoteEntity.class))).thenAnswer(call -> {
            saved.add(call.getArgument(0));
            return call.getArgument(0);
        });
    }

    @Test
    void aRequestIsSavedAndTheStylistIsTold() {
        BaseResponse response = service.request("CUST1", request());

        assertEquals("200", response.getStatusCode(), response.getMessage());
        QuoteEntity quote = saved.get(0);
        assertEquals(QuoteEntity.REQUESTED, quote.getStatus());
        assertEquals("CUST1", quote.getUserId());
        assertTrue(quote.getQuoteRef().matches("Q[A-Z0-9]{7}"), quote.getQuoteRef());
        assertEquals("visitBarber", quote.getServiceTime());
        verify(notifications).notifyQuote(eq("CUST1"), eq("STY1"), eq(true), eq("New quote request"), contains("Gel extensions"));
    }

    @Test
    void aRequestForAServiceTheStylistDoesNotOfferIsRefused() {
        QuoteRequestData data = request();
        data.setSubServiceId("99");

        BaseResponse response = service.request("CUST1", data);

        assertEquals("400", response.getStatusCode());
        assertTrue(saved.isEmpty());
    }

    @Test
    void aStylistNotYetApprovedTakesNoRequests() {
        when(eligibility.isApprovedStyler(any())).thenReturn(false);

        assertEquals("400", service.request("CUST1", request()).getStatusCode());
        assertTrue(saved.isEmpty());
    }

    @Test
    void aCustomerCannotStackRequestsWithOneStylist() {
        when(quoteRepo.findByUserIdAndStatusIn(eq("CUST1"), anyList()))
                .thenReturn(List.of(open("Q1"), open("Q2")));

        BaseResponse response = service.request("CUST1", request());

        assertEquals("400", response.getStatusCode());
        assertTrue(response.getMessage().contains("already have quote requests"), response.getMessage());
    }

    @Test
    void anOfferSetsThePriceAndHowLongItStaysOpenAndTellsTheCustomer() {
        QuoteEntity quote = open("QOPEN001");
        when(quoteRepo.findByQuoteRefForUpdate("QOPEN001")).thenReturn(Optional.of(quote));

        BaseResponse response = service.offer("STY1", offer("QOPEN001", "185.50", 150));

        assertEquals("200", response.getStatusCode(), response.getMessage());
        assertEquals(QuoteEntity.QUOTED, quote.getStatus());
        assertEquals(18550L, quote.getPriceCents());
        assertEquals(150, quote.getDurationMinutes());
        assertEquals(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(3), quote.getValidUntil());
        verify(notifications).notifyQuote(eq("CUST1"), eq("STY1"), eq(false), eq("Your quote is ready"),
                contains("$185.50 for 2 hours 30 min"));
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) response.getData();
        assertEquals("185.50", view.get("price"));
        assertEquals("Ada", view.get("clientName"));
    }

    @Test
    void anOfferOutsideTheSensibleRangeOrOffTheQuarterHourIsRefused() {
        when(quoteRepo.findByQuoteRefForUpdate("QOPEN001")).thenReturn(Optional.of(open("QOPEN001")));

        assertEquals("400", service.offer("STY1", offer("QOPEN001", "5", 60)).getStatusCode());
        assertEquals("400", service.offer("STY1", offer("QOPEN001", "9000", 60)).getStatusCode());
        assertEquals("400", service.offer("STY1", offer("QOPEN001", "100", 50)).getStatusCode());
        assertEquals("400", service.offer("STY1", offer("QOPEN001", "100", 600)).getStatusCode());
        verifyNoInteractions(notifications);
    }

    @Test
    void anotherStylistCannotPriceSomeoneElsesQuote() {
        when(quoteRepo.findByQuoteRefForUpdate("QOPEN001")).thenReturn(Optional.of(open("QOPEN001")));

        BaseResponse response = service.offer("STY2", offer("QOPEN001", "100", 60));

        assertEquals("400", response.getStatusCode());
        assertEquals("Quote not found", response.getMessage());
    }

    @Test
    void aQuotePastItsDateReadsAsExpiredAndCannotBeBooked() {
        QuoteEntity quote = quoted("QOLD0001", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusSeconds(1));
        when(quoteRepo.findByQuoteRefForUpdate("QOLD0001")).thenReturn(Optional.of(quote));

        assertEquals(QuoteEntity.EXPIRED, quote.effectiveStatus(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.forCheckout("QOLD0001", "CUST1", "STY1"));
        assertTrue(refused.getMessage().contains("expired"));
    }

    @Test
    void onlyTheCustomerItWasPricedForCanBookIt() {
        QuoteEntity quote = quoted("QLIVE001", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(1));
        when(quoteRepo.findByQuoteRefForUpdate("QLIVE001")).thenReturn(Optional.of(quote));

        assertThrows(IllegalArgumentException.class, () -> service.forCheckout("QLIVE001", "CUST2", "STY1"));
        assertSame(quote, service.forCheckout("QLIVE001", "CUST1", "STY1"));
    }

    @Test
    void aRequestNotYetPricedCannotBeBooked() {
        when(quoteRepo.findByQuoteRefForUpdate("QOPEN001")).thenReturn(Optional.of(open("QOPEN001")));

        assertThrows(IllegalArgumentException.class, () -> service.forCheckout("QOPEN001", "CUST1", "STY1"));
    }

    @Test
    void eitherSideCanCloseAnOpenQuoteButNotAClosedOne() {
        QuoteEntity mine = open("QOPEN001");
        when(quoteRepo.findByQuoteRefForUpdate("QOPEN001")).thenReturn(Optional.of(mine));
        assertEquals("200", service.decline("CUST1", "QOPEN001").getStatusCode());
        assertEquals(QuoteEntity.DECLINED, mine.getStatus());
        assertEquals("400", service.withdraw("STY1", "QOPEN001").getStatusCode());

        QuoteEntity theirs = open("QOPEN002");
        when(quoteRepo.findByQuoteRefForUpdate("QOPEN002")).thenReturn(Optional.of(theirs));
        assertEquals("400", service.decline("CUST2", "QOPEN002").getStatusCode(), "not their quote");
        assertEquals("200", service.withdraw("STY1", "QOPEN002").getStatusCode());
        assertEquals(QuoteEntity.WITHDRAWN, theirs.getStatus());
    }

    @Test
    void theStylistListCountsRequestsStillWaitingForAPrice() {
        when(quoteRepo.findByStylerIdOrderByCreatedAtDesc("STY1")).thenReturn(List.of(open("Q1"),
                quoted("Q2", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(1)), open("Q3")));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) service.listForStylist("STY1").getData();

        assertEquals(2L, data.get("waiting"));
        assertEquals(3, ((List<?>) data.get("items")).size());
    }

    @Test
    void aRequestKeepsTheCustomersOwnQuotePhotos() {
        QuoteRequestData data = request();
        String mine = photo(ownership.folderFor("CUSTOMER", "CUST1"), "nails1");
        data.setPhotoUrls(List.of(mine));

        BaseResponse response = service.request("CUST1", data);

        assertEquals("200", response.getStatusCode(), response.getMessage());
        assertEquals(List.of(mine), saved.get(0).photoList());
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) response.getData();
        assertEquals(List.of(mine), view.get("photos"));
    }

    @Test
    void someoneElsesPhotoOrAnotherFolderIsRefused() {
        QuoteRequestData theirs = request();
        theirs.setPhotoUrls(List.of(photo(ownership.folderFor("CUSTOMER", "CUST2"), "x")));
        assertEquals("400", service.request("CUST1", theirs).getStatusCode());

        QuoteRequestData profileFolder = request();
        profileFolder.setPhotoUrls(List.of("https://res.cloudinary.com/demo/image/upload/v1/rapid_stylers/profile/"
                + ownership.folderFor("CUSTOMER", "CUST1") + "/me.jpg"));
        assertEquals("400", service.request("CUST1", profileFolder).getStatusCode());

        QuoteRequestData elsewhere = request();
        elsewhere.setPhotoUrls(List.of("https://example.com/nails.jpg"));
        assertEquals("400", service.request("CUST1", elsewhere).getStatusCode());
        assertTrue(saved.isEmpty());
    }

    @Test
    void aClientMessageWaitsOnTheStylistUntilTheyReply() {
        QuoteEntity quote = open("QOPEN001");
        when(quoteRepo.findByQuoteRefForUpdate("QOPEN001")).thenReturn(Optional.of(quote));
        when(quoteRepo.findByStylerIdOrderByCreatedAtDesc("STY1")).thenReturn(List.of(quote));

        assertEquals("200", service.message("CUST1", false, message("QOPEN001", "Can you do almond shape?")).getStatusCode());
        verify(notifications).notifyQuote(eq("CUST1"), eq("STY1"), eq(true), eq("New message on a quote"), contains("almond"));
        @SuppressWarnings("unchecked")
        Map<String, Object> list = (Map<String, Object>) service.listForStylist("STY1").getData();
        assertEquals(1L, list.get("waiting"));
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) list.get("items")).get(0);
        assertEquals(true, item.get("needsReply"));

        assertEquals("200", service.message("STY1", true, message("QOPEN001", "Yes, almond works")).getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) service.listForStylist("STY1").getData();
        assertEquals(1L, after.get("waiting"), "still waiting for a price");
        assertEquals(2, ((List<?>) ((List<Map<String, Object>>) after.get("items")).get(0).get("messages")).size());
    }

    @Test
    void messagesAreForTheTwoSidesOfAnOpenQuoteAndCapped() {
        QuoteEntity quote = open("QOPEN001");
        when(quoteRepo.findByQuoteRefForUpdate("QOPEN001")).thenReturn(Optional.of(quote));
        assertEquals("Quote not found", service.message("CUST2", false, message("QOPEN001", "hi")).getMessage());
        assertEquals("Quote not found", service.message("STY2", true, message("QOPEN001", "hi")).getMessage());

        for (int i = 0; i < QuoteService.MAX_MESSAGES; i++) {
            assertEquals("200", service.message("CUST1", false, message("QOPEN001", "note " + i)).getStatusCode());
        }
        assertTrue(service.message("CUST1", false, message("QOPEN001", "one more")).getMessage().contains("message limit"));

        quote.setStatus(QuoteEntity.DECLINED);
        assertTrue(service.message("STY1", true, message("QOPEN001", "late")).getMessage().contains("closed"));
    }

    @Test
    void durationsReadNaturally() {
        assertEquals("45 min", QuoteService.durationText(45));
        assertEquals("1 hour", QuoteService.durationText(60));
        assertEquals("3 hours 15 min", QuoteService.durationText(195));
    }

    private QuoteRequestData request() {
        QuoteRequestData data = new QuoteRequestData();
        data.setStylerId("STY1");
        data.setSubServiceId("7");
        data.setDescription("Long coffin gel set with hand-painted florals for a wedding");
        data.setPreferredDates("Any weekday evening next week");
        return data;
    }

    private static String photo(String ownerFolder, String name) {
        return "https://res.cloudinary.com/demo/image/upload/v1700000000/rapid_stylers/quote/" + ownerFolder + "/" + name + ".jpg";
    }

    private static com.macrotel.rapidstylers.booking.pojo.QuoteMessageData message(String ref, String body) {
        com.macrotel.rapidstylers.booking.pojo.QuoteMessageData data = new com.macrotel.rapidstylers.booking.pojo.QuoteMessageData();
        data.setQuoteRef(ref);
        data.setBody(body);
        return data;
    }

    private QuoteOfferData offer(String ref, String price, int minutes) {
        QuoteOfferData data = new QuoteOfferData();
        data.setQuoteRef(ref);
        data.setPrice(price);
        data.setDurationMinutes(minutes);
        return data;
    }

    private QuoteEntity open(String ref) {
        QuoteEntity quote = new QuoteEntity();
        quote.setQuoteRef(ref);
        quote.setUserId("CUST1");
        quote.setStylerId("STY1");
        quote.setSubServiceId("7");
        quote.setStatus(QuoteEntity.REQUESTED);
        quote.setCreatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(1));
        return quote;
    }

    private QuoteEntity quoted(String ref, LocalDateTime validUntil) {
        QuoteEntity quote = open(ref);
        quote.setStatus(QuoteEntity.QUOTED);
        quote.setPriceCents(120_00L);
        quote.setDurationMinutes(90);
        quote.setValidUntil(validUntil);
        return quote;
    }
}
