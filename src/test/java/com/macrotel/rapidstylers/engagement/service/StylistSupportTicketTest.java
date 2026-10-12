package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.engagement.entity.SupportTicketEntity;
import com.macrotel.rapidstylers.engagement.pojo.SupportTicketData;
import com.macrotel.rapidstylers.engagement.repo.SupportTicketRepo;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.support.TestServices;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Stylists open and read their own tickets; the admin list says who sent each one. */
class StylistSupportTicketTest {

    private SupportService supportService;
    private UserRepo userRepo;
    private StylerRepo stylerRepo;
    private SupportTicketRepo supportTicketRepo;

    @BeforeEach
    void setUp() {
        TestServices services = new TestServices();
        userRepo = mock(UserRepo.class);
        stylerRepo = mock(StylerRepo.class);
        supportTicketRepo = mock(SupportTicketRepo.class);
        services.userRepo = userRepo;
        services.stylerRepo = stylerRepo;
        services.supportTicketRepo = supportTicketRepo;
        supportService = services.supportService();
        when(supportTicketRepo.save(any(SupportTicketEntity.class))).thenAnswer(inv -> {
            SupportTicketEntity entity = inv.getArgument(0);
            entity.setId(7L);
            return entity;
        });
    }

    private SupportTicketData ticket() {
        SupportTicketData data = new SupportTicketData();
        data.setSubject("Payout question");
        data.setMessage("When does my payout arrive?");
        return data;
    }

    private static StylerEntity stylist() {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("CM1");
        styler.setFirstname("Chloe");
        styler.setLastname("Martin");
        styler.setEmailAddress("chloe@example.com");
        return styler;
    }

    @Test
    void aStylistTicketIsAttributedToTheStylist() {
        when(stylerRepo.findByStylerId("CM1")).thenReturn(Optional.of(stylist()));

        BaseResponse response = supportService.createSupportTicket("CM1", "STYLER", ticket());

        assertEquals("200", response.getStatusCode());
        ArgumentCaptor<SupportTicketEntity> saved = ArgumentCaptor.forClass(SupportTicketEntity.class);
        verify(supportTicketRepo).save(saved.capture());
        assertEquals("CM1", saved.getValue().getUserId());
        assertEquals("STYLIST", saved.getValue().getSenderRole());
    }

    @Test
    void aStylistIdIsNotAcceptedAsACustomerOrTheReverse() {
        when(stylerRepo.findByStylerId("CM1")).thenReturn(Optional.of(stylist()));

        assertEquals("400", supportService.createSupportTicket("CM1", "CUSTOMER", ticket()).getStatusCode());
        assertEquals("400", supportService.createSupportTicket("U1", "STYLER", ticket()).getStatusCode());
        verify(supportTicketRepo, never()).save(any());
    }

    @Test
    void aStylistListsTheirOwnTickets() {
        when(stylerRepo.findByStylerId("CM1")).thenReturn(Optional.of(stylist()));
        SupportTicketEntity mine = new SupportTicketEntity("CM1", "STYLIST", "s", "m");
        when(supportTicketRepo.findByUserIdOrderByUpdatedAtDesc("CM1")).thenReturn(List.of(mine));

        BaseResponse response = supportService.listSupportTickets("CM1", "STYLER");

        assertEquals("200", response.getStatusCode());
        assertEquals(List.of(mine), response.getData());
    }

    @Test
    void theAdminListShowsSenderDateAndTicketId() {
        UserEntity customer = new UserEntity();
        customer.setUserId("U1");
        customer.setFirstname("Ada");
        customer.setLastname("Obi");
        customer.setEmailAddress("ada@example.com");
        when(userRepo.findByUserId("U1")).thenReturn(Optional.of(customer));
        when(stylerRepo.findByStylerId("CM1")).thenReturn(Optional.of(stylist()));
        SupportTicketEntity fromCustomer = new SupportTicketEntity("U1", "Booking", "Help");
        fromCustomer.setId(1L);
        SupportTicketEntity fromStylist = new SupportTicketEntity("CM1", "STYLIST", "Payout", "Help");
        fromStylist.setId(2L);
        SupportTicketEntity legacy = new SupportTicketEntity("GONE", "Old", "Help");
        legacy.setId(3L);
        legacy.setSenderRole(null);
        when(supportTicketRepo.findAll()).thenReturn(List.of(fromCustomer, fromStylist, legacy));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) supportService.listAllSupportTickets().getData();

        assertEquals(3, rows.size());
        // Newest first.
        Map<String, Object> stylistRow = rows.get(1);
        assertEquals(2L, stylistRow.get("ticketId"));
        assertEquals("Chloe Martin", stylistRow.get("senderName"));
        assertEquals("chloe@example.com", stylistRow.get("senderEmail"));
        assertEquals("STYLIST", stylistRow.get("senderRole"));
        assertEquals(fromStylist.getCreatedAt(), stylistRow.get("createdAt"));
        assertEquals("Payout", stylistRow.get("subject"));
        Map<String, Object> customerRow = rows.get(2);
        assertEquals("Ada Obi", customerRow.get("senderName"));
        assertEquals("CUSTOMER", customerRow.get("senderRole"));
        Map<String, Object> legacyRow = rows.get(0);
        assertEquals("CUSTOMER", legacyRow.get("senderRole"));
        assertNull(legacyRow.get("senderEmail"));
    }
}
