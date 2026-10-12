package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.engagement.entity.SupportTicketEntity;
import com.macrotel.rapidstylers.engagement.pojo.SupportTicketData;
import com.macrotel.rapidstylers.engagement.repo.SupportTicketRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.support.TestServices;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A ticket carries the category its sender picked; anything missing or unknown is OTHER. */
class SupportTicketCategoryTest {

    private SupportService supportService;
    private SupportTicketRepo supportTicketRepo;

    @BeforeEach
    void setUp() {
        TestServices services = new TestServices();
        UserRepo userRepo = mock(UserRepo.class);
        services.userRepo = userRepo;
        services.stylerRepo = mock(StylerRepo.class);
        supportTicketRepo = mock(SupportTicketRepo.class);
        services.supportTicketRepo = supportTicketRepo;
        supportService = services.supportService();
        UserEntity ada = new UserEntity();
        ada.setUserId("U1");
        when(userRepo.findByUserId("U1")).thenReturn(Optional.of(ada));
        when(supportTicketRepo.save(any(SupportTicketEntity.class))).thenAnswer(inv -> {
            SupportTicketEntity entity = inv.getArgument(0);
            entity.setId(9L);
            return entity;
        });
    }

    private String savedCategory(String requested) {
        SupportTicketData data = new SupportTicketData();
        data.setSubject("Help");
        data.setMessage("Something happened");
        data.setCategory(requested);
        assertEquals("200", supportService.createSupportTicket("U1", "CUSTOMER", data).getStatusCode());
        ArgumentCaptor<SupportTicketEntity> saved = ArgumentCaptor.forClass(SupportTicketEntity.class);
        verify(supportTicketRepo).save(saved.capture());
        return saved.getValue().getCategory();
    }

    @Test
    void aPickedCategoryIsStored() {
        assertEquals("PAYMENT", savedCategory(" payment "));
    }

    @Test
    void noCategoryIsOther() {
        assertEquals("OTHER", savedCategory(null));
    }

    @Test
    void anUnknownCategoryIsOther() {
        assertEquals("OTHER", savedCategory("REFUNDS"));
    }

    @Test
    void everyListedCategoryIsAccepted() {
        for (String category : List.of("BOOKING", "PAYMENT", "ACCOUNT", "TECHNICAL", "OTHER")) {
            assertEquals(category, SupportService.category(category.toLowerCase()));
        }
    }

    private static SupportTicketEntity ticket(long id, String category) {
        SupportTicketEntity ticket = new SupportTicketEntity("U1", "Subject " + id, "Message");
        ticket.setId(id);
        ticket.setCategory(category);
        return ticket;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAdminListShowsAndFiltersByCategory() {
        when(supportTicketRepo.findAll()).thenReturn(List.of(ticket(1, "BOOKING"), ticket(2, "PAYMENT"), ticket(3, null)));

        List<Map<String, Object>> all = (List<Map<String, Object>>) supportService.listAllSupportTickets().getData();
        assertEquals(3, all.size());
        assertEquals("OTHER", all.get(0).get("category"));
        assertEquals("PAYMENT", all.get(1).get("category"));

        List<Map<String, Object>> payments = (List<Map<String, Object>>) supportService.listAllSupportTickets("payment").getData();
        assertEquals(1, payments.size());
        assertEquals(2L, payments.get(0).get("id"));

        assertEquals(3, ((List<?>) supportService.listAllSupportTickets(" ").getData()).size());
        assertEquals(0, ((List<?>) supportService.listAllSupportTickets("NOPE").getData()).size());
    }
}
